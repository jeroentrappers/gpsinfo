package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"
)

// Phase-2 groundwork: turn DATEX incidents into per-edge live speeds for
// Valhalla's native traffic (traffic.tar). Valhalla resolves geometry to
// directed-edge ids via /trace_attributes; we assign each affected edge a
// target live speed by incident category. The result is served at
// /edgespeeds — the contract a thin traffic.tar writer consumes to poke
// TrafficSpeed records into Valhalla's memory-mapped overlay.
//
// This is read-only against Valhalla (no router change), so it can run and
// be validated before the overlay/writer exist.

// EdgeSpeed is one directed edge's target live speed (km/h; 0 = closed).
type EdgeSpeed struct {
	Edge  int64 `json:"edge"`
	Speed int   `json:"speed"`
}

// EdgeSpeedIndex resolves incidents to edges (cached) and keeps the current
// edge→speed map. Safe for concurrent reads.
type EdgeSpeedIndex struct {
	valhallaURL string
	client      *http.Client

	mu      sync.RWMutex
	cache   map[string][]int64 // incident key → resolved edge ids
	current map[int64]int      // edge id → min target speed (km/h)
}

func newEdgeSpeedIndex(valhallaURL string) *EdgeSpeedIndex {
	return &EdgeSpeedIndex{
		valhallaURL: valhallaURL,
		client:      &http.Client{Timeout: 12 * time.Second},
		cache:       map[string][]int64{},
		current:     map[int64]int{},
	}
}

func (x *EdgeSpeedIndex) enabled() bool { return x.valhallaURL != "" }

/** Recompute the edge→speed map from the current incidents, resolving only
 *  incidents not already cached (by id+updated) so each cycle is cheap. */
func (x *EdgeSpeedIndex) update(incidents []Incident) {
	if !x.enabled() {
		return
	}
	seen := map[string]bool{}
	next := map[int64]int{}
	for _, inc := range incidents {
		target, relevant := targetSpeedFor(inc.Category)
		if !relevant || len(inc.Geometry) == 0 {
			continue
		}
		key := inc.ID + "@" + inc.Updated
		seen[key] = true

		x.mu.RLock()
		edges, cached := x.cache[key]
		x.mu.RUnlock()
		if !cached {
			edges = x.resolve(inc.Geometry)
			x.mu.Lock()
			x.cache[key] = edges
			x.mu.Unlock()
		}
		for _, e := range edges {
			// Lowest speed wins (a closure on a congested edge stays closed).
			if cur, ok := next[e]; !ok || target < cur {
				next[e] = target
			}
		}
	}
	// Drop cache entries for incidents that are gone.
	x.mu.Lock()
	for k := range x.cache {
		if !seen[k] {
			delete(x.cache, k)
		}
	}
	x.current = next
	x.mu.Unlock()
}

func (x *EdgeSpeedIndex) snapshot() []EdgeSpeed {
	x.mu.RLock()
	defer x.mu.RUnlock()
	out := make([]EdgeSpeed, 0, len(x.current))
	for e, s := range x.current {
		out = append(out, EdgeSpeed{Edge: e, Speed: s})
	}
	return out
}

// resolve maps an incident polyline to Valhalla directed-edge ids via
// /trace_attributes (map-snap). Returns empty on any error.
func (x *EdgeSpeedIndex) resolve(geom [][2]float64) []int64 {
	shape := make([]map[string]float64, 0, len(geom))
	step := 1
	if len(geom) > 24 { // keep the request modest
		step = len(geom) / 24
	}
	for i := 0; i < len(geom); i += step {
		shape = append(shape, map[string]float64{"lat": geom[i][1], "lon": geom[i][0]})
	}
	if len(shape) < 1 {
		return nil
	}
	body, _ := json.Marshal(map[string]any{
		"shape":       shape,
		"costing":     "auto",
		"shape_match": "map_snap",
		"filters": map[string]any{
			"attributes": []string{"edge.id"},
			"action":     "include",
		},
	})
	req, _ := http.NewRequest(http.MethodPost, x.valhallaURL+"/trace_attributes", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	resp, err := x.client.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	var parsed struct {
		Edges []struct {
			ID int64 `json:"id"`
		} `json:"edges"`
	}
	if json.NewDecoder(resp.Body).Decode(&parsed) != nil {
		return nil
	}
	ids := make([]int64, 0, len(parsed.Edges))
	for _, e := range parsed.Edges {
		ids = append(ids, e.ID)
	}
	return ids
}

// targetSpeedFor maps an incident category to a target live speed (km/h) and
// whether it should affect routing. Closures stop the edge; jams/works slow
// it. (Without per-edge free-flow here we use absolute targets; a richer
// version can scale by the edge's own speed.)
func targetSpeedFor(category string) (speed int, relevant bool) {
	switch category {
	case "laneClosure", "accident":
		return 0, true // closed
	case "congestion":
		return 15, true
	case "roadworks":
		return 40, true
	default:
		return 0, false
	}
}

func (x *EdgeSpeedIndex) handle(w http.ResponseWriter, r *http.Request) {
	edges := x.snapshot()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"enabled": x.enabled(),
		"count":   len(edges),
		"edges":   edges,
	})
}

func (x *EdgeSpeedIndex) String() string {
	return fmt.Sprintf("EdgeSpeedIndex(enabled=%v, edges=%d)", x.enabled(), len(x.snapshot()))
}
