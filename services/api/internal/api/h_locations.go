package api

import (
	"math"
	"net/http"
	"time"

	"github.com/google/uuid"

	"familycircle/internal/store"
)

const (
	maxPointsPerBatch = 500
	maxPointAge       = 7 * 24 * time.Hour
	maxClockSkew      = 5 * time.Minute
)

type pointIn struct {
	ID              string   `json:"id"`
	RecordedAt      string   `json:"recorded_at"`
	Lat             *float64 `json:"lat"`
	Lon             *float64 `json:"lon"`
	AccuracyM       *float64 `json:"accuracy_m"`
	AltitudeM       *float64 `json:"altitude_m"`
	SpeedMps        *float64 `json:"speed_mps"`
	BearingDeg      *float64 `json:"bearing_deg"`
	BatteryPct      *int     `json:"battery_pct"`
	BatteryCharging *bool    `json:"battery_charging"`
	Motion          string   `json:"motion"`
}

type rejection struct {
	ID     string `json:"id"`
	Reason string `json:"reason"`
}

func finite(f *float64) bool { return f == nil || (!math.IsNaN(*f) && !math.IsInf(*f, 0)) }

// validatePoint checks a single fix. Returns the point or a rejection reason.
func validatePoint(in pointIn, now time.Time) (store.Point, string) {
	id, err := uuid.Parse(in.ID)
	if err != nil {
		return store.Point{}, "invalid"
	}
	at, err := time.Parse(time.RFC3339Nano, in.RecordedAt)
	if err != nil {
		return store.Point{}, "invalid"
	}
	if in.Lat == nil || in.Lon == nil || in.AccuracyM == nil ||
		!finite(in.Lat) || !finite(in.Lon) || !finite(in.AccuracyM) ||
		!finite(in.AltitudeM) || !finite(in.SpeedMps) || !finite(in.BearingDeg) ||
		*in.Lat < -90 || *in.Lat > 90 || *in.Lon < -180 || *in.Lon > 180 ||
		*in.AccuracyM <= 0 || *in.AccuracyM > 100_000 ||
		(in.SpeedMps != nil && (*in.SpeedMps < 0 || *in.SpeedMps > 400)) ||
		(in.BearingDeg != nil && (*in.BearingDeg < 0 || *in.BearingDeg >= 360)) ||
		(in.AltitudeM != nil && (*in.AltitudeM < -1000 || *in.AltitudeM > 20_000)) ||
		(in.BatteryPct != nil && (*in.BatteryPct < 0 || *in.BatteryPct > 100)) {
		return store.Point{}, "invalid"
	}
	if at.After(now.Add(maxClockSkew)) {
		return store.Point{}, "in_future"
	}
	if at.Before(now.Add(-maxPointAge)) {
		return store.Point{}, "too_old"
	}
	motion := in.Motion
	switch motion {
	case "stationary", "walking", "driving", "unknown":
	default:
		motion = "unknown"
	}
	return store.Point{ID: id, RecordedAt: at.UTC(), Lat: *in.Lat, Lon: *in.Lon, AccuracyM: *in.AccuracyM,
		AltitudeM: in.AltitudeM, SpeedMps: in.SpeedMps, BearingDeg: in.BearingDeg,
		BatteryPct: in.BatteryPct, BatteryCharging: in.BatteryCharging, Motion: motion}, ""
}

// handleUploadLocations stores fixes for the authenticated device's user. The body has no
// user field; any unknown fields (e.g. an attacker-supplied user_id) are ignored.
func (s *Server) handleUploadLocations(w http.ResponseWriter, r *http.Request) {
	p := principalFrom(r)
	if ok, wait := s.lim.upload.Allow(p.user.ID.String()); !ok {
		tooMany(w, wait)
		return
	}
	var in struct {
		Points []pointIn `json:"points"`
	}
	if !decode(w, r, &in, 1<<20) {
		return
	}
	if len(in.Points) == 0 || len(in.Points) > maxPointsPerBatch {
		writeFieldErrors(w, http.StatusBadRequest, "invalid_input", "invalid input", map[string]string{"points": "1–500 points"})
		return
	}
	now := s.now()
	accepted := make([]string, 0, len(in.Points))
	rejected := make([]rejection, 0)
	points := make([]store.Point, 0, len(in.Points))
	seen := map[uuid.UUID]bool{}
	for _, raw := range in.Points {
		pt, reason := validatePoint(raw, now)
		if reason != "" {
			rejected = append(rejected, rejection{raw.ID, reason})
			continue
		}
		if seen[pt.ID] {
			continue
		}
		seen[pt.ID] = true
		points = append(points, pt)
		accepted = append(accepted, pt.ID.String())
	}
	if len(points) > 0 {
		res, err := s.store.Ingest(r.Context(), p.user.ID, *p.session.DeviceID, points)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		if res.LatestChanged {
			s.publishLocation(r.Context(), p.user.ID, res.Latest, res.ShareBattery)
		}
		s.publishPresence(r.Context(), p.user.ID, res.Presence)
	}
	writeJSON(w, http.StatusOK, map[string]any{"accepted": accepted, "rejected": rejected})
}
