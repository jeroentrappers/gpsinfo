package main

import "math"

// Belgian Lambert 72 (EPSG:31370) → WGS84 (EPSG:4326) inverse projection.
//
// The Flanders DATEX II feed encodes its gml geometry in Lambert 72
// (projected metres, e.g. "148786.52 212980.62"), not lat/lon, so every
// position has to be reprojected before it can go on a WGS84 map or be
// matched against a route. Pure-Go Snyder LCC-2SP inverse so the service
// has no native (PROJ) dependency.
//
// Parameters per EPSG:31370 (International 1924 ellipsoid):
//   lat_1 = 49.8333339°, lat_2 = 51.1666672°, lat_0 = 90°,
//   lon_0 = 4.367486666666667°, x_0 = 150000.013, y_0 = 5400088.438.

const (
	l72A  = 6378388.0 // International 1924 semi-major axis
	l72F  = 1.0 / 297.0
	l72X0 = 150000.013
	l72Y0 = 5400088.438
)

var (
	l72e    float64
	l72n    float64
	l72BigF float64
	l72rho0 float64
	l72lon0 float64
)

func init() {
	e2 := l72F * (2 - l72F)
	l72e = math.Sqrt(e2)
	l72lon0 = rad(4.367486666666667)

	m := func(phi float64) float64 {
		return math.Cos(phi) / math.Sqrt(1-e2*sq(math.Sin(phi)))
	}
	t := func(phi float64) float64 {
		s := math.Sin(phi)
		return math.Tan(math.Pi/4-phi/2) / math.Pow((1-l72e*s)/(1+l72e*s), l72e/2)
	}
	phi0, phi1, phi2 := rad(90.0), rad(49.8333339), rad(51.1666672)
	m1, m2 := m(phi1), m(phi2)
	t1, t2, t0 := t(phi1), t(phi2), t(phi0)
	l72n = (math.Log(m1) - math.Log(m2)) / (math.Log(t1) - math.Log(t2))
	l72BigF = m1 / (l72n * math.Pow(t1, l72n))
	l72rho0 = l72A * l72BigF * math.Pow(t0, l72n) // t0 == 0 at the pole → 0
}

// lambert72ToWGS84 converts EPSG:31370 easting/northing (metres) to
// (lon, lat) in degrees.
func lambert72ToWGS84(x, y float64) (lon, lat float64) {
	xp := x - l72X0
	yp := y - l72Y0
	rho := math.Copysign(math.Sqrt(xp*xp+(l72rho0-yp)*(l72rho0-yp)), l72n)
	tt := math.Pow(rho/(l72A*l72BigF), 1/l72n)
	phi := math.Pi/2 - 2*math.Atan(tt)
	for i := 0; i < 12; i++ {
		s := math.Sin(phi)
		phi = math.Pi/2 - 2*math.Atan(tt*math.Pow((1-l72e*s)/(1+l72e*s), l72e/2))
	}
	theta := math.Atan2(xp, l72rho0-yp)
	return deg(l72lon0 + theta/l72n), deg(phi)
}

func rad(d float64) float64 { return d * math.Pi / 180 }
func deg(r float64) float64 { return r * 180 / math.Pi }
func sq(x float64) float64  { return x * x }
