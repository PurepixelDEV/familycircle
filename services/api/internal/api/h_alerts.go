package api

import (
	"context"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"familycircle/internal/store"
)

const (
	ringTTL           = 2 * time.Minute
	locateTTL         = 5 * time.Minute
	sosActiveFor      = 24 * time.Hour
	batteryLowEvery   = 6 * time.Hour
	lastLocationEvery = 10 * time.Minute
	maxCheckInMessage = 140
)

type actorDTO struct {
	UserID      uuid.UUID `json:"user_id"`
	DisplayName string    `json:"display_name"`
}

type placeRefDTO struct {
	ID   *uuid.UUID `json:"id"`
	Name string     `json:"name"`
}

type pointDTO struct {
	Lat       float64  `json:"lat"`
	Lon       float64  `json:"lon"`
	AccuracyM *float64 `json:"accuracy_m"`
}

type alertDTO struct {
	ID           int64        `json:"id"`
	Kind         string       `json:"kind"`
	Actor        actorDTO     `json:"actor"`
	FromDeviceID *uuid.UUID   `json:"from_device_id"`
	Place        *placeRefDTO `json:"place"`
	Location     *pointDTO    `json:"location"`
	BatteryPct   *int         `json:"battery_pct"`
	Message      *string      `json:"message"`
	CreatedAt    time.Time    `json:"created_at"`
	ExpiresAt    *time.Time   `json:"expires_at"`
	ResolvedAt   *time.Time   `json:"resolved_at"`
	Active       bool         `json:"active"`
	Mine         bool         `json:"mine"`
}

type alertEvent struct {
	Type  string   `json:"type"`
	Alert alertDTO `json:"alert"`
}

// toAlert converts an alert. Active: an SOS that is unresolved and younger than 24 h, or a
// ring/locate request that has not expired.
func toAlert(a store.Alert, now time.Time) alertDTO {
	out := alertDTO{ID: a.ID, Kind: a.Kind, Actor: actorDTO{a.ActorID, a.ActorName}, FromDeviceID: a.ActorDeviceID,
		BatteryPct: a.BatteryPct, Message: a.Message, CreatedAt: a.CreatedAt, ExpiresAt: a.ExpiresAt,
		ResolvedAt: a.ResolvedAt, Mine: a.Mine}
	if a.PlaceName != nil {
		out.Place = &placeRefDTO{a.PlaceID, *a.PlaceName}
	}
	if a.Lat != nil && a.Lon != nil {
		out.Location = &pointDTO{*a.Lat, *a.Lon, a.AccuracyM}
	}
	switch a.Kind {
	case store.AlertSOS:
		out.Active = a.ResolvedAt == nil && now.Sub(a.CreatedAt) < sosActiveFor
	case store.AlertRing, store.AlertLocate:
		out.Active = a.ExpiresAt != nil && now.Before(*a.ExpiresAt)
	}
	return out
}

// emitAlert stores an alert, pushes it to the recipients' live connections and, for explicit
// actions (SOS, check-in), echoes it to the actor's other connections.
func (s *Server) emitAlert(ctx context.Context, a store.NewAlert) (alertDTO, int, error) {
	alert, recipients, err := s.store.CreateAlert(ctx, a)
	if err != nil {
		return alertDTO{}, 0, err
	}
	now := s.now()
	dto := toAlert(alert, now)
	s.hub.SendTo(recipients, mustJSON(alertEvent{"alert", dto}))
	switch a.Kind {
	case store.AlertSOS, store.AlertSOSResolved, store.AlertCheckIn:
		alert.Mine = true
		s.hub.SendTo([]uuid.UUID{a.Actor}, mustJSON(alertEvent{"alert", toAlert(alert, now)}))
	}
	return dto, len(recipients), nil
}

// positionIn is lat/lon/accuracy_m: either all absent or a valid position.
type positionIn struct {
	Lat       *float64 `json:"lat"`
	Lon       *float64 `json:"lon"`
	AccuracyM *float64 `json:"accuracy_m"`
}

func (p positionIn) check(f fieldErrors, required bool) bool {
	if p.Lat == nil && p.Lon == nil && p.AccuracyM == nil {
		f.check(!required, "lat", "required")
		return false
	}
	valid := p.Lat != nil && p.Lon != nil && finite(p.Lat) && finite(p.Lon) && finite(p.AccuracyM) &&
		*p.Lat >= -90 && *p.Lat <= 90 && *p.Lon >= -180 && *p.Lon <= 180 &&
		(p.AccuracyM == nil || (*p.AccuracyM > 0 && *p.AccuracyM <= 100_000))
	f.check(valid, "lat", "lat, lon (and accuracy_m) must form a valid position")
	return valid
}

// latestPoint falls back to the stored latest position of the user (for SOS/battery without a fix).
func (s *Server) latestPoint(ctx context.Context, user uuid.UUID) positionIn {
	loc, err := s.store.LatestFor(ctx, user, user)
	if err != nil || loc == nil {
		return positionIn{}
	}
	acc := loc.AccuracyM
	return positionIn{&loc.Lat, &loc.Lon, &acc}
}

func (s *Server) allowAlert(w http.ResponseWriter, user uuid.UUID) bool {
	if ok, wait := s.lim.alerts.Allow(user.String()); !ok {
		tooMany(w, wait)
		return false
	}
	return true
}

func deviceOf(p principal) *uuid.UUID { return p.session.DeviceID }

// handleListAlerts: GET /alerts?after=<id>&limit=<n> – catch-up for clients that were offline.
func (s *Server) handleListAlerts(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	f := fieldErrors{}
	var after int64
	limit := 100
	if v := q.Get("after"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		f.check(err == nil && n >= 0, "after", "non-negative integer")
		after = n
	}
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		f.check(err == nil && n >= 1 && n <= 500, "limit", "1–500")
		limit = n
	}
	if f.write(w) {
		return
	}
	alerts, err := s.store.ListAlerts(r.Context(), principalFrom(r).user.ID, after, limit)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	now := s.now()
	out := make([]alertDTO, 0, len(alerts))
	for _, a := range alerts {
		out = append(out, toAlert(a, now))
	}
	writeJSON(w, http.StatusOK, map[string]any{"alerts": out, "server_time": now})
}

// handleSOS: POST /sos – alarm with position to everyone in the actor's families.
func (s *Server) handleSOS(w http.ResponseWriter, r *http.Request) {
	p := principalFrom(r)
	if !s.allowAlert(w, p.user.ID) {
		return
	}
	var in positionIn
	if !decodeOptional(w, r, &in) {
		return
	}
	f := fieldErrors{}
	if !in.check(f, false) {
		if f.write(w) {
			return
		}
		in = s.latestPoint(r.Context(), p.user.ID)
	}
	dto, n, err := s.emitAlert(r.Context(), store.NewAlert{Kind: store.AlertSOS, Actor: p.user.ID, ActorDevice: deviceOf(p),
		Lat: in.Lat, Lon: in.Lon, AccuracyM: in.AccuracyM})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.log.Info("sos", "recipients", n) // no position, no names in logs
	writeJSON(w, http.StatusCreated, map[string]any{"alert": dto, "recipients": n})
}

// handleResolveSOS: POST /sos/resolve – "I am safe". Idempotent.
func (s *Server) handleResolveSOS(w http.ResponseWriter, r *http.Request) {
	p := principalFrom(r)
	n, err := s.store.ResolveSOS(r.Context(), p.user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if n > 0 {
		if _, _, err := s.emitAlert(r.Context(), store.NewAlert{Kind: store.AlertSOSResolved, Actor: p.user.ID, ActorDevice: deviceOf(p)}); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"resolved": n})
}

// handleCheckIn: POST /check-ins – "arrived safely" with position and optional message.
func (s *Server) handleCheckIn(w http.ResponseWriter, r *http.Request) {
	p := principalFrom(r)
	if !s.allowAlert(w, p.user.ID) {
		return
	}
	var in struct {
		positionIn
		Message *string `json:"message"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	f := fieldErrors{}
	in.check(f, true)
	if in.Message != nil {
		m := strings.TrimSpace(*in.Message)
		f.check(len([]rune(m)) <= maxCheckInMessage, "message", "at most 140 characters")
		if m == "" {
			in.Message = nil
		} else {
			in.Message = &m
		}
	}
	if f.write(w) {
		return
	}
	a := store.NewAlert{Kind: store.AlertCheckIn, Actor: p.user.ID, ActorDevice: deviceOf(p),
		Lat: in.Lat, Lon: in.Lon, AccuracyM: in.AccuracyM, Message: in.Message}
	if place, err := s.store.PlaceAt(r.Context(), p.user.ID, *in.Lat, *in.Lon); err == nil && place != nil {
		a.PlaceID, a.PlaceName = &place.ID, &place.Name
	}
	dto, n, err := s.emitAlert(r.Context(), a)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"alert": dto, "recipients": n})
}

// handleBatteryAlert: POST /battery-alerts (device) – low battery or shutdown, with the last
// position. Goes only to people who can see the location; deduplicated; off if the user disabled it.
func (s *Server) handleBatteryAlert(w http.ResponseWriter, r *http.Request) {
	p := principalFrom(r)
	var in struct {
		positionIn
		Reason     string `json:"reason"`
		BatteryPct *int   `json:"battery_pct"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	f := fieldErrors{}
	f.check(in.Reason == "low" || in.Reason == "shutdown", "reason", "low or shutdown")
	f.check(in.BatteryPct != nil && *in.BatteryPct >= 0 && *in.BatteryPct <= 100, "battery_pct", "0–100")
	hasPoint := in.check(f, false)
	if f.write(w) {
		return
	}
	ctx := r.Context()
	st, err := s.store.Settings(ctx, p.user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	kind, every := store.AlertBatteryLow, batteryLowEvery
	if in.Reason == "shutdown" {
		kind, every = store.AlertLastLocation, lastLocationEvery
	}
	recent, err := s.store.RecentAlert(ctx, p.user.ID, kind, s.now().Add(-every))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !st.BatteryAlerts || recent {
		writeJSON(w, http.StatusOK, map[string]any{"sent": false})
		return
	}
	if !hasPoint {
		in.positionIn = s.latestPoint(ctx, p.user.ID)
	}
	_, n, err := s.emitAlert(ctx, store.NewAlert{Kind: kind, Actor: p.user.ID, ActorDevice: deviceOf(p),
		Lat: in.Lat, Lon: in.Lon, AccuracyM: in.AccuracyM, BatteryPct: in.BatteryPct})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"sent": true, "recipients": n})
}

// handleRemoteRequest: POST /users/{userId}/ring and /users/{userId}/locate.
func (s *Server) handleRemoteRequest(kind string, ttl time.Duration) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		target, ok := pathID(w, r, "userId")
		if !ok {
			return
		}
		p := principalFrom(r)
		if !s.allowAlert(w, p.user.ID) {
			return
		}
		if err := s.store.RemoteRequestAllowed(r.Context(), p.user.ID, target, kind); err != nil {
			s.fail(w, r, err)
			return
		}
		expires := s.now().Add(ttl)
		dto, _, err := s.emitAlert(r.Context(), store.NewAlert{Kind: kind, Actor: p.user.ID, ActorDevice: deviceOf(p),
			Target: target, ExpiresAt: &expires})
		if err != nil {
			s.fail(w, r, err)
			return
		}
		// online: at least one live connection of the target right now (the request also waits
		// until it expires for a phone that reconnects).
		writeJSON(w, http.StatusAccepted, map[string]any{"alert_id": dto.ID, "expires_at": expires, "online": s.hub.Count(target) > 0})
	}
}

// publishPresence turns arrivals/departures found during ingest into alerts.
func (s *Server) publishPresence(ctx context.Context, user uuid.UUID, changes []store.PresenceChange) {
	for _, c := range changes {
		kind := store.AlertDeparture
		if c.Arrived {
			kind = store.AlertArrival
		}
		placeID, name := c.PlaceID, c.PlaceName
		if _, _, err := s.emitAlert(ctx, store.NewAlert{Kind: kind, Actor: user, PlaceID: &placeID, FamilyID: c.FamilyID,
			PlaceName: &name}); err != nil {
			s.log.Error("presence alert", "err", err)
		}
	}
}

// decodeOptional accepts an empty body as {} (POST /sos may be sent without a position).
func decodeOptional(w http.ResponseWriter, r *http.Request, v any) bool {
	if r.ContentLength == 0 {
		return true
	}
	return decode(w, r, v, 0)
}
