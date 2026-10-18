// Package geo contains pure location logic: distances, GPS jump detection and freshness.
package geo

import (
	"math"
	"time"
)

const earthRadiusM = 6371008.8

// MaxPlausibleSpeedMps is the highest speed treated as physically plausible (~1080 km/h,
// covers airliners). Faster implied movement between two fixes is treated as a GPS jump.
const MaxPlausibleSpeedMps = 300.0

// DistanceMeters returns the great-circle distance between two WGS84 points (haversine).
func DistanceMeters(lat1, lon1, lat2, lon2 float64) float64 {
	rad := math.Pi / 180
	dLat := (lat2 - lat1) * rad
	dLon := (lon2 - lon1) * rad
	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(lat1*rad)*math.Cos(lat2*rad)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * earthRadiusM * math.Asin(math.Min(1, math.Sqrt(a)))
}

type Fix struct {
	Lat, Lon  float64
	AccuracyM float64
	At        time.Time
}

// Plausible reports whether moving from a to b is physically possible. Accuracy radii are
// subtracted so that noisy-but-honest fixes are not flagged.
func Plausible(a, b Fix) bool {
	d := DistanceMeters(a.Lat, a.Lon, b.Lat, b.Lon) - a.AccuracyM - b.AccuracyM
	if d <= 0 {
		return true
	}
	dt := math.Abs(b.At.Sub(a.At).Seconds())
	if dt < 1 {
		dt = 1
	}
	return d/dt <= MaxPlausibleSpeedMps
}

// IsSuspect decides whether f is an implausible jump. last is the last accepted fix, candidate
// the most recent fix that was already flagged. A jump is confirmed (not suspect) when the new fix
// is plausible relative to the candidate, i.e. two consecutive fixes agree on the new place.
func IsSuspect(last, candidate *Fix, f Fix) bool {
	if last == nil || Plausible(*last, f) {
		return false
	}
	if candidate != nil && f.At.After(candidate.At) && Plausible(*candidate, f) {
		return false
	}
	return true
}

type Freshness string

const (
	Live   Freshness = "live"
	Recent Freshness = "recent"
	Stale  Freshness = "stale"
)

const (
	LiveWindow   = 2 * time.Minute
	RecentWindow = 15 * time.Minute
)

// FreshnessOf classifies a fix by the time it was recorded on the device (not when it arrived),
// so an old fix uploaded late from the offline queue is never shown as live.
func FreshnessOf(recordedAt, now time.Time) Freshness {
	age := now.Sub(recordedAt)
	switch {
	case age < LiveWindow:
		return Live
	case age < RecentWindow:
		return Recent
	default:
		return Stale
	}
}
