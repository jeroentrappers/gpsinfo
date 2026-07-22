package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Germany's Autobahn GmbH publishes traffic as a JSON REST API rather than a
// single DATEX II document (https://verkehr.autobahn.de/o/autobahn):
//
//	GET /o/autobahn                         → { "roads": ["A1","A2",…] }
//	GET /o/autobahn/{road}/services/warning → { "warning":  [ item, … ] }
//	                        …/roadworks     → { "roadworks": [ item, … ] }
//	                        …/closure       → { "closure":   [ item, … ] }
//
// Each item carries a point (`coordinate`) and often a bounding `extent`
// ("lat,lon,lat,lon"). We fan out across roads × services concurrently and map
// every item to the same normalized Incident the DATEX sources produce.

var autobahnClient = &http.Client{Timeout: 20 * time.Second}

var autobahnServices = []string{"warning", "roadworks", "closure"}

func fetchAutobahn(src Source) ([]Incident, time.Time, error) {
	roads, err := autobahnRoads(src.URL)
	if err != nil {
		return nil, time.Time{}, err
	}
	type job struct{ road, service string }
	jobs := make(chan job)
	var mu sync.Mutex
	out := []Incident{}
	seen := map[string]struct{}{}
	var wg sync.WaitGroup
	worker := func() {
		defer wg.Done()
		for j := range jobs {
			for _, inc := range autobahnService(src.URL, j.road, j.service) {
				mu.Lock()
				if _, dup := seen[inc.ID]; !dup {
					seen[inc.ID] = struct{}{}
					out = append(out, inc)
				}
				mu.Unlock()
			}
		}
	}
	const workers = 8
	wg.Add(workers)
	for i := 0; i < workers; i++ {
		go worker()
	}
	for _, r := range roads {
		for _, s := range autobahnServices {
			jobs <- job{r, s}
		}
	}
	close(jobs)
	wg.Wait()
	return out, time.Now(), nil
}

func autobahnRoads(base string) ([]string, error) {
	body, err := autobahnGet(base)
	if err != nil {
		return nil, err
	}
	var r struct {
		Roads []string `json:"roads"`
	}
	if err := json.Unmarshal(body, &r); err != nil {
		return nil, err
	}
	// Dedup + trim (the list contains stray trailing spaces, e.g. "A60 ").
	seen := map[string]struct{}{}
	out := make([]string, 0, len(r.Roads))
	for _, road := range r.Roads {
		road = strings.TrimSpace(road)
		if road == "" {
			continue
		}
		if _, ok := seen[road]; ok {
			continue
		}
		seen[road] = struct{}{}
		out = append(out, road)
	}
	return out, nil
}

// autobahnItem is the subset of an Autobahn service item we use. coordinate
// values arrive as JSON numbers or strings depending on the endpoint, so they
// are decoded leniently as json.Number.
type autobahnItem struct {
	Identifier   string `json:"identifier"`
	Extent       string `json:"extent"`
	AbnormalType string `json:"abnormalTrafficType"`
	IsBlocked    string `json:"isBlocked"`
	Title        string `json:"title"`
	Start        string `json:"startTimestamp"`
	Coordinate   struct {
		Lat  json.Number `json:"lat"`
		Long json.Number `json:"long"`
	} `json:"coordinate"`
}

func autobahnService(base, road, service string) []Incident {
	body, err := autobahnGet(base + "/" + road + "/services/" + service)
	if err != nil {
		return nil
	}
	// The array lives under a key named after the service.
	var wrap map[string][]autobahnItem
	if err := json.Unmarshal(body, &wrap); err != nil {
		return nil
	}
	items := wrap[service]
	out := make([]Incident, 0, len(items))
	for _, it := range items {
		geo := autobahnGeometry(it)
		if len(geo) == 0 {
			continue
		}
		id := it.Identifier
		if id == "" {
			id = fmt.Sprintf("%s-%s-%.5f,%.5f", road, service, geo[0][1], geo[0][0])
		}
		out = append(out, Incident{
			ID:       "de-autobahn:" + id,
			Source:   "de-autobahn",
			Category: autobahnCategory(service, it),
			Subtype:  it.AbnormalType,
			Start:    it.Start,
			Geometry: geo,
		})
	}
	return out
}

// autobahnGeometry prefers the bounding extent ("lat,lon,lat,lon" → a 2-point
// line) and falls back to the single coordinate point.
func autobahnGeometry(it autobahnItem) [][2]float64 {
	if nums := parseFloats(strings.ReplaceAll(it.Extent, ",", " ")); len(nums) >= 4 {
		geo := make([][2]float64, 0, len(nums)/2)
		for i := 0; i+1 < len(nums); i += 2 {
			geo = append(geo, [2]float64{round6(nums[i+1]), round6(nums[i])}) // lat,lon → lon,lat
		}
		return geo
	}
	lat, err1 := strconv.ParseFloat(it.Coordinate.Lat.String(), 64)
	lon, err2 := strconv.ParseFloat(it.Coordinate.Long.String(), 64)
	if err1 == nil && err2 == nil && (lat != 0 || lon != 0) {
		return [][2]float64{{round6(lon), round6(lat)}}
	}
	return nil
}

func autobahnCategory(service string, it autobahnItem) string {
	switch service {
	case "closure":
		return "laneClosure"
	case "roadworks":
		return "roadworks"
	default: // warning
		switch strings.ToUpper(it.AbnormalType) {
		case "QUEUING_TRAFFIC", "STATIONARY_TRAFFIC", "SLOW_TRAFFIC":
			return "congestion"
		}
		if strings.EqualFold(it.IsBlocked, "true") {
			return "laneClosure"
		}
		return "other"
	}
}

func autobahnGet(url string) ([]byte, error) {
	req, _ := http.NewRequest(http.MethodGet, url, nil)
	req.Header.Set("User-Agent", "gpsinfo-traffic/0.1 (+https://appmire.be)")
	req.Header.Set("Accept", "application/json")
	resp, err := autobahnClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 8<<20))
}
