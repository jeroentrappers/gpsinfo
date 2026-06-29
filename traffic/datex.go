package main

import (
	"encoding/xml"
	"strconv"
	"strings"
	"time"
)

// Incident is the normalized, WGS84, source-agnostic traffic event the
// service serves to clients (and later feeds into routing). One DATEX II
// situationRecord → one Incident.
type Incident struct {
	ID       string       `json:"id"`
	Source   string       `json:"source"`
	Category string       `json:"category"` // congestion|accident|roadworks|laneClosure|rerouting|other
	Subtype  string       `json:"subtype,omitempty"`
	Severity string       `json:"severity,omitempty"`
	Start    string       `json:"start,omitempty"`
	End      string       `json:"end,omitempty"`
	Updated  string       `json:"updated,omitempty"`
	Geometry [][2]float64 `json:"geometry"` // [[lon,lat],...] WGS84; 1 point or a polyline
}

// ── DATEX II v3 Situation Publication (namespace-agnostic by local name) ──

type dxPayload struct {
	PublicationTime string        `xml:"publicationTime"`
	Situations      []dxSituation `xml:"situation"`
}

type dxSituation struct {
	ID      string     `xml:"id,attr"`
	Records []dxRecord `xml:"situationRecord"`
}

type dxRecord struct {
	ID           string   `xml:"id,attr"`
	Type         string   `xml:"http://www.w3.org/2001/XMLSchema-instance type,attr"`
	CreationTime string   `xml:"situationRecordCreationTime"`
	VersionTime  string   `xml:"situationRecordVersionTime"`
	OverallStart string   `xml:"validity>validityTimeSpecification>overallStartTime"`
	OverallEnd   string   `xml:"validity>validityTimeSpecification>overallEndTime"`
	AbnormalType string   `xml:"abnormalTrafficType"`
	Severity     string   `xml:"severity"`
	PosLists     []string `xml:"locationReference>gmlLineString>posList"`
	// Some feeds use explicit point coordinates instead of gml geometry.
	PointLat []string `xml:"locationReference>pointByCoordinates>pointCoordinates>latitude"`
	PointLon []string `xml:"locationReference>pointByCoordinates>pointCoordinates>longitude"`
}

// parseDATEX parses a DATEX II situation publication into normalized
// Incidents, reprojecting geometry per the source's [srs]
// ("EPSG:31370" = Belgian Lambert 72; anything else = treated as WGS84,
// gml axis order lat,lon). Returns the feed's publicationTime too.
func parseDATEX(data []byte, sourceID, srs string) ([]Incident, time.Time, error) {
	var p dxPayload
	if err := xml.Unmarshal(data, &p); err != nil {
		return nil, time.Time{}, err
	}
	pub := parseTime(p.PublicationTime)
	out := make([]Incident, 0, len(p.Situations))
	for _, sit := range p.Situations {
		for i, r := range sit.Records {
			id := r.ID
			if id == "" {
				id = sit.ID + "#" + strconv.Itoa(i)
			}
			inc := Incident{
				ID:       sourceID + ":" + id,
				Source:   sourceID,
				Category: categoryOf(localType(r.Type)),
				Subtype:  r.AbnormalType,
				Severity: r.Severity,
				Start:    r.OverallStart,
				End:      r.OverallEnd,
				Updated:  firstNonEmpty(r.VersionTime, r.CreationTime),
				Geometry: geometryOf(r, srs),
			}
			out = append(out, inc)
		}
	}
	return out, pub, nil
}

func geometryOf(r dxRecord, srs string) [][2]float64 {
	geo := [][2]float64{}
	lambert := strings.EqualFold(srs, "EPSG:31370")
	for _, pl := range r.PosLists {
		nums := parseFloats(pl)
		for i := 0; i+1 < len(nums); i += 2 {
			a, b := nums[i], nums[i+1]
			if lambert {
				lon, lat := lambert72ToWGS84(a, b) // gml posList = "x y"
				geo = append(geo, [2]float64{round6(lon), round6(lat)})
			} else {
				// WGS84 gml axis order is lat,lon → emit lon,lat.
				geo = append(geo, [2]float64{round6(b), round6(a)})
			}
		}
	}
	// Fall back to an explicit point if there was no line geometry.
	if len(geo) == 0 && len(r.PointLat) > 0 && len(r.PointLon) > 0 {
		if lat, err := strconv.ParseFloat(strings.TrimSpace(r.PointLat[0]), 64); err == nil {
			if lon, err := strconv.ParseFloat(strings.TrimSpace(r.PointLon[0]), 64); err == nil {
				geo = append(geo, [2]float64{round6(lon), round6(lat)})
			}
		}
	}
	return geo
}

func localType(t string) string {
	if i := strings.LastIndex(t, ":"); i >= 0 {
		return t[i+1:]
	}
	return t
}

func categoryOf(t string) string {
	switch t {
	case "AbnormalTraffic":
		return "congestion"
	case "Accident":
		return "accident"
	case "MaintenanceWorks", "ConstructionWorks":
		return "roadworks"
	case "RoadOrCarriagewayOrLaneManagement", "GeneralNetworkManagement",
		"WinterDrivingManagement":
		return "laneClosure"
	case "ReroutingManagement":
		return "rerouting"
	default:
		return "other"
	}
}

func parseFloats(s string) []float64 {
	fields := strings.Fields(s)
	out := make([]float64, 0, len(fields))
	for _, f := range fields {
		if v, err := strconv.ParseFloat(f, 64); err == nil {
			out = append(out, v)
		}
	}
	return out
}

func parseTime(s string) time.Time {
	s = strings.TrimSpace(s)
	for _, layout := range []string{time.RFC3339Nano, time.RFC3339} {
		if t, err := time.Parse(layout, s); err == nil {
			return t
		}
	}
	return time.Time{}
}

func firstNonEmpty(vs ...string) string {
	for _, v := range vs {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func round6(v float64) float64 {
	return float64(int64(v*1e6+0.5*sign(v))) / 1e6
}

func sign(v float64) float64 {
	if v < 0 {
		return -1
	}
	return 1
}
