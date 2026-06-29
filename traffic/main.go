// Command traffic is a live traffic aggregation service for GPSinfo.
//
// It polls the national DATEX II traffic feeds (Belgium first), normalizes
// every situationRecord into a WGS84 [Incident] (reprojecting Belgian
// Lambert 72 geometry), keeps the latest snapshot in memory, and serves it
// to clients two ways:
//
//   GET /traffic[?bbox=minLon,minLat,maxLon,maxLat]  → JSON snapshot
//   GET /events                                      → Server-Sent Events
//   GET /healthz                                     → liveness + per-source status
//
// Push (SSE) is the primary channel for "live rerouting due to traffic";
// the snapshot endpoint is the polling fallback and the initial load. It
// sits BESIDE the Valhalla routing service (it doesn't replace it): clients
// keep routing via Valhalla and use this layer to warn, to colour the
// route, and to ask Valhalla for a reroute (avoid areas) when a blocking
// incident lands on the path ahead.
//
// Run a one-shot fetch + analysis (no server) with:  traffic -once
package main

import (
	"compress/gzip"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Source is one upstream traffic feed.
type Source struct {
	ID       string
	URL      string
	SRS      string // "EPSG:31370" (BE Lambert 72) or "EPSG:4326"
	Interval time.Duration
	Gzip     bool
	Enabled  bool
	// HTTP basic-auth, for feeds that require it (e.g. Wallonia/SOFICO).
	// Filled from env TRAFFIC_<ID>_USER / TRAFFIC_<ID>_PASS at startup.
	User, Pass string
}

// loadCreds fills each source's basic-auth from the environment
// (TRAFFIC_BE_WALLONIA_USER / _PASS, '-'→'_', upper-cased) so secrets stay
// out of the repo, and auto-enables a configured source once it has both a
// URL and (if needed) credentials.
func loadCreds() {
	for i := range sources {
		key := "TRAFFIC_" + strings.ToUpper(strings.ReplaceAll(sources[i].ID, "-", "_"))
		if u := os.Getenv(key + "_USER"); u != "" {
			sources[i].User = u
			sources[i].Pass = os.Getenv(key + "_PASS")
		}
	}
}

// The national access points. Belgium is wired and verified; the
// neighbours are pre-configured — enable once their exact resource URL /
// key is confirmed (most are DATEX II Situation feeds and parse as-is;
// Germany's Autobahn API is JSON and needs a separate fetcher).
var sources = []Source{
	{
		ID:       "be-flanders",
		URL:      "https://www.verkeerscentrum.be/uitwisseling/datex2v3full",
		SRS:      "EPSG:31370",
		Interval: 60 * time.Second,
		Enabled:  true,
	},
	{
		// NDW migrated off the old opendata.ndw.nu file host (now 404);
		// disabled until the current DATEX II v3 incident URL is confirmed
		// via docs.ndw.nu. Parser + gzip path are ready.
		ID:       "nl-ndw-incidents",
		URL:      "http://opendata.ndw.nu/incidents.xml.gz",
		SRS:      "EPSG:4326",
		Interval: 60 * time.Second,
		Gzip:     true,
		Enabled:  false,
	},
	// ── Pre-configured, disabled until the exact resource URL is confirmed ──
	{ID: "be-wallonia", URL: "", SRS: "EPSG:4326", Interval: 60 * time.Second}, // transportdata.be walloon-road-traffic-events (CC0)
	{ID: "be-brussels", URL: "", SRS: "EPSG:4326", Interval: 60 * time.Second}, // Brussels Mobility (NAP)
	{ID: "lu-cita", URL: "", SRS: "EPSG:4326", Interval: 60 * time.Second},     // data.public.lu CITA DATEX II v3.6 (CC0)
	{ID: "fr-dir", URL: "", SRS: "EPSG:4326", Interval: 6 * time.Minute},       // transport.data.gouv.fr non-toll national network
	// de-autobahn: JSON REST (github.com/bundesAPI/autobahn-api) — separate fetcher, not DATEX II.
}

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	once := flag.Bool("once", false, "fetch each source once, print a summary, and exit")
	flag.Parse()
	loadCreds()

	store := newStore()
	if *once {
		runOnce(store)
		return
	}

	hub := newHub()
	// Phase 2: resolve incidents to Valhalla edges + target live speeds.
	// Inert unless TRAFFIC_VALHALLA_URL points at a Valhalla instance.
	edgeIndex := newEdgeSpeedIndex(os.Getenv("TRAFFIC_VALHALLA_URL"))
	for _, src := range sources {
		if !src.Enabled || src.URL == "" {
			continue
		}
		go pollLoop(src, store, hub, edgeIndex)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", store.handleHealth)
	mux.HandleFunc("/traffic", store.handleSnapshot)
	mux.HandleFunc("/events", hub.handleSSE)
	mux.HandleFunc("/edgespeeds", edgeIndex.handle)
	log.Printf("traffic service listening on %s", *addr)
	log.Fatal(http.ListenAndServe(*addr, mux))
}

// ── Fetch + poll ─────────────────────────────────────────────────────

var httpClient = &http.Client{Timeout: 45 * time.Second}

func fetch(src Source) ([]Incident, time.Time, error) {
	req, _ := http.NewRequest(http.MethodGet, src.URL, nil)
	req.Header.Set("User-Agent", "gpsinfo-traffic/0.1 (+https://appmire.be)")
	if src.User != "" {
		req.SetBasicAuth(src.User, src.Pass)
	}
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, time.Time{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, time.Time{}, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var r io.Reader = resp.Body
	if src.Gzip || strings.HasSuffix(src.URL, ".gz") ||
		strings.Contains(resp.Header.Get("Content-Encoding"), "gzip") {
		gz, err := gzip.NewReader(resp.Body)
		if err != nil {
			return nil, time.Time{}, err
		}
		defer gz.Close()
		r = gz
	}
	data, err := io.ReadAll(io.LimitReader(r, 64<<20)) // 64 MB cap
	if err != nil {
		return nil, time.Time{}, err
	}
	return parseDATEX(data, src.ID, src.SRS)
}

func pollLoop(src Source, store *Store, hub *Hub, edgeIndex *EdgeSpeedIndex) {
	t := time.NewTicker(src.Interval)
	defer t.Stop()
	for {
		inc, pub, err := fetch(src)
		if err != nil {
			log.Printf("[%s] fetch failed: %v", src.ID, err)
			store.markError(src.ID, err)
		} else {
			store.set(src.ID, inc, pub)
			log.Printf("[%s] %d incidents (pub %s)", src.ID, len(inc), pub.Format(time.RFC3339))
			hub.broadcast(sseUpdate{Source: src.ID, Count: len(inc), Pub: pub})
			// Refresh the Valhalla edge→speed map (resolves only new incidents).
			edgeIndex.update(store.allIncidents())
		}
		<-t.C
	}
}

// ── In-memory store ──────────────────────────────────────────────────

type sourceState struct {
	incidents []Incident
	pub       time.Time
	fetched   time.Time
	err       string
}

type Store struct {
	mu  sync.RWMutex
	src map[string]*sourceState
}

func newStore() *Store { return &Store{src: map[string]*sourceState{}} }

func (s *Store) set(id string, inc []Incident, pub time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.src[id] = &sourceState{incidents: inc, pub: pub, fetched: time.Now(), err: ""}
}

func (s *Store) markError(id string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	st := s.src[id]
	if st == nil {
		st = &sourceState{}
		s.src[id] = st
	}
	st.err = err.Error()
	st.fetched = time.Now()
}

func (s *Store) allIncidents() []Incident {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := []Incident{}
	for _, st := range s.src {
		out = append(out, st.incidents...)
	}
	return out
}

func (s *Store) snapshot(b *bbox) []Incident {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := []Incident{}
	for _, st := range s.src {
		for _, inc := range st.incidents {
			if b == nil || b.touches(inc.Geometry) {
				out = append(out, inc)
			}
		}
	}
	return out
}

func (s *Store) handleSnapshot(w http.ResponseWriter, r *http.Request) {
	b, err := parseBBox(r.URL.Query().Get("bbox"))
	if err != nil {
		http.Error(w, "bad bbox: "+err.Error(), http.StatusBadRequest)
		return
	}
	inc := s.snapshot(b)
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	_ = json.NewEncoder(w).Encode(map[string]any{"count": len(inc), "incidents": inc})
}

func (s *Store) handleHealth(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	type srcHealth struct {
		Incidents int    `json:"incidents"`
		Pub       string `json:"pub,omitempty"`
		AgeSec    int    `json:"age_sec"`
		Err       string `json:"error,omitempty"`
	}
	h := map[string]srcHealth{}
	for id, st := range s.src {
		age := -1
		if !st.fetched.IsZero() {
			age = int(time.Since(st.fetched).Seconds())
		}
		ph := ""
		if !st.pub.IsZero() {
			ph = st.pub.Format(time.RFC3339)
		}
		h[id] = srcHealth{Incidents: len(st.incidents), Pub: ph, AgeSec: age, Err: st.err}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"status": "ok", "sources": h})
}

// ── SSE hub ──────────────────────────────────────────────────────────

type sseUpdate struct {
	Source string    `json:"source"`
	Count  int       `json:"count"`
	Pub    time.Time `json:"pub"`
}

type Hub struct {
	mu   sync.Mutex
	subs map[chan []byte]struct{}
}

func newHub() *Hub { return &Hub{subs: map[chan []byte]struct{}{}} }

func (h *Hub) broadcast(u sseUpdate) {
	msg, _ := json.Marshal(u)
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs {
		select {
		case ch <- msg:
		default: // drop for a slow client rather than block the poller
		}
	}
}

func (h *Hub) handleSSE(w http.ResponseWriter, r *http.Request) {
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	ch := make(chan []byte, 8)
	h.mu.Lock()
	h.subs[ch] = struct{}{}
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(h.subs, ch)
		h.mu.Unlock()
	}()

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	fmt.Fprintf(w, "event: hello\ndata: {}\n\n")
	fl.Flush()

	ka := time.NewTicker(25 * time.Second)
	defer ka.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case msg := <-ch:
			fmt.Fprintf(w, "event: update\ndata: %s\n\n", msg)
			fl.Flush()
		case <-ka.C:
			fmt.Fprintf(w, ": keep-alive\n\n")
			fl.Flush()
		}
	}
}

// ── bbox ─────────────────────────────────────────────────────────────

type bbox struct{ minLon, minLat, maxLon, maxLat float64 }

func parseBBox(s string) (*bbox, error) {
	if strings.TrimSpace(s) == "" {
		return nil, nil
	}
	p := strings.Split(s, ",")
	if len(p) != 4 {
		return nil, fmt.Errorf("want minLon,minLat,maxLon,maxLat")
	}
	v := make([]float64, 4)
	for i := range p {
		f, err := strconv.ParseFloat(strings.TrimSpace(p[i]), 64)
		if err != nil {
			return nil, err
		}
		v[i] = f
	}
	return &bbox{v[0], v[1], v[2], v[3]}, nil
}

func (b *bbox) touches(geo [][2]float64) bool {
	for _, pt := range geo {
		if pt[0] >= b.minLon && pt[0] <= b.maxLon && pt[1] >= b.minLat && pt[1] <= b.maxLat {
			return true
		}
	}
	return false
}

// ── -once analysis mode ──────────────────────────────────────────────

func runOnce(store *Store) {
	now := time.Now()
	for _, src := range sources {
		if src.URL == "" {
			fmt.Printf("• %-18s (no URL configured — skipped)\n", src.ID)
			continue
		}
		inc, pub, err := fetch(src)
		if err != nil {
			fmt.Printf("• %-18s FETCH FAILED: %v\n", src.ID, err)
			continue
		}
		byCat := map[string]int{}
		var sample *Incident
		for i := range inc {
			byCat[inc[i].Category]++
			if sample == nil && len(inc[i].Geometry) > 0 {
				sample = &inc[i]
			}
		}
		age := "—"
		if !pub.IsZero() {
			age = fmt.Sprintf("%.0fs old", now.Sub(pub).Seconds())
		}
		fmt.Printf("• %-18s %d incidents | pub %s (%s)\n", src.ID, len(inc),
			pub.Format(time.RFC3339), age)
		fmt.Printf("    by category: %s\n", fmtCounts(byCat))
		if sample != nil {
			fmt.Printf("    sample: %s [%s/%s] sev=%q start=%s geo[0]=%.5f,%.5f (%d pts)\n",
				sample.ID, sample.Category, sample.Subtype, sample.Severity,
				sample.Start, sample.Geometry[0][0], sample.Geometry[0][1], len(sample.Geometry))
		}
	}
}

func fmtCounts(m map[string]int) string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	parts := make([]string, 0, len(keys))
	for _, k := range keys {
		parts = append(parts, fmt.Sprintf("%s=%d", k, m[k]))
	}
	return strings.Join(parts, " ")
}
