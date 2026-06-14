// gpsinfo-extract — tiny HTTP service that cuts a regional .pmtiles out of
// the planet on demand, for the app's offline region/corridor downloads.
//
//	GET /extract?bbox=minLon,minLat,maxLon,maxLat&minzoom=6&maxzoom=14
//	→ streams a .pmtiles (application/octet-stream)
//
// It shells out to the `pmtiles` CLI (protomaps/go-pmtiles), caches results
// by a hash of the normalised params, and single-flights concurrent identical
// requests. Auth + rate-limiting are handled by Caddy in front (it validates
// ?key= and proxies here), so this service trusts its caller.
//
// Abuse guard: the requested area × zoom is capped so nobody can ask it to
// re-extract the whole planet.
//
// ⚠️ UNTESTED SCAFFOLD — review and `go build` on the box before trusting it.
//
// Env:
//
//	PLANET   path to planet.pmtiles            (default /data/planet.pmtiles)
//	CACHE    dir for extracted region files    (default /cache)
//	ADDR     listen address                    (default :8081)
//	PMTILES  pmtiles CLI binary                 (default pmtiles)
package main

import (
	"crypto/sha256"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
)

const (
	maxZoomCap   = 15      // never extract deeper than this
	maxAreaSqDeg = 12.0    // bbox area cap (deg²) — ~ a large country
)

var (
	planet  = env("PLANET", "/data/planet.pmtiles")
	cache   = env("CACHE", "/cache")
	addr    = env("ADDR", ":8081")
	pmtiles = env("PMTILES", "pmtiles")

	// One in-flight extraction per cache key; everyone else waits on it.
	flights   = map[string]*sync.WaitGroup{}
	flightsMu sync.Mutex
)

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	if err := os.MkdirAll(cache, 0o755); err != nil {
		log.Fatalf("cache dir: %v", err)
	}
	http.HandleFunc("/extract", handleExtract)
	http.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("ok")) })
	log.Printf("gpsinfo-extract listening on %s (planet=%s cache=%s)", addr, planet, cache)
	log.Fatal(http.ListenAndServe(addr, nil))
}

func handleExtract(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	bbox := q.Get("bbox")
	lons, lats, err := parseBbox(bbox)
	if err != nil {
		http.Error(w, "bad bbox: "+err.Error(), http.StatusBadRequest)
		return
	}
	minzoom := clampInt(q.Get("minzoom"), 0, 0, maxZoomCap)
	maxzoom := clampInt(q.Get("maxzoom"), 14, minzoom, maxZoomCap)

	area := (lons[1] - lons[0]) * (lats[1] - lats[0])
	if area <= 0 || area > maxAreaSqDeg {
		http.Error(w, fmt.Sprintf("area %.2f deg² out of range (0,%.0f]", area, maxAreaSqDeg), http.StatusBadRequest)
		return
	}

	key := hashKey(bbox, minzoom, maxzoom)
	out := filepath.Join(cache, key+".pmtiles")

	if _, err := os.Stat(out); err != nil {
		if err := extractOnce(key, out, bbox, minzoom, maxzoom); err != nil {
			log.Printf("extract %s failed: %v", key, err)
			http.Error(w, "extract failed", http.StatusBadGateway)
			return
		}
	}

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s.pmtiles"`, key))
	w.Header().Set("Cache-Control", "public, max-age=86400")
	http.ServeFile(w, r, out) // honours Range too
}

// extractOnce runs `pmtiles extract` for `key`, collapsing concurrent
// identical requests onto a single run.
func extractOnce(key, out, bbox string, minzoom, maxzoom int) error {
	flightsMu.Lock()
	if wg, busy := flights[key]; busy {
		flightsMu.Unlock()
		wg.Wait()
		if _, err := os.Stat(out); err == nil {
			return nil
		}
		return fmt.Errorf("concurrent extract produced no file")
	}
	wg := &sync.WaitGroup{}
	wg.Add(1)
	flights[key] = wg
	flightsMu.Unlock()
	defer func() {
		flightsMu.Lock()
		delete(flights, key)
		flightsMu.Unlock()
		wg.Done()
	}()

	tmp := out + ".tmp"
	cmd := exec.Command(pmtiles, "extract", planet, tmp,
		"--bbox="+bbox,
		"--minzoom="+strconv.Itoa(minzoom),
		"--maxzoom="+strconv.Itoa(maxzoom),
	)
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, out) // atomic publish
}

func parseBbox(s string) (lons, lats [2]float64, err error) {
	p := strings.Split(s, ",")
	if len(p) != 4 {
		return lons, lats, fmt.Errorf("want minLon,minLat,maxLon,maxLat")
	}
	v := make([]float64, 4)
	for i := range p {
		if v[i], err = strconv.ParseFloat(strings.TrimSpace(p[i]), 64); err != nil {
			return lons, lats, err
		}
	}
	lons = [2]float64{v[0], v[2]}
	lats = [2]float64{v[1], v[3]}
	if lons[1] <= lons[0] || lats[1] <= lats[0] {
		return lons, lats, fmt.Errorf("max must exceed min")
	}
	return lons, lats, nil
}

func clampInt(s string, def, lo, hi int) int {
	n, err := strconv.Atoi(s)
	if err != nil {
		n = def
	}
	if n < lo {
		n = lo
	}
	if n > hi {
		n = hi
	}
	return n
}

func hashKey(bbox string, minzoom, maxzoom int) string {
	h := sha256.Sum256(fmt.Appendf(nil, "%s|%d|%d", bbox, minzoom, maxzoom))
	return fmt.Sprintf("%x", h[:8])
}
