package api

import (
	"net/http"
	"strings"
	"time"
)

// Limits of the history overview requests: a day overview covers at most maxHistoryDays intervals
// within maxHistorySpan; an events request at most maxHistorySpan.
const (
	maxHistoryDays = 62
	maxHistorySpan = 63 * 24 * time.Hour
)

type historyDayDTO struct {
	From      time.Time  `json:"from"`
	To        time.Time  `json:"to"`
	Points    int        `json:"points"`
	DistanceM float64    `json:"distance_m"`
	FirstAt   *time.Time `json:"first_at"`
	LastAt    *time.Time `json:"last_at"`
}

// handleHistoryDays: GET /users/{userId}/history/days?bounds=t0,t1,…,tn – points and distance per
// interval [t(i), t(i+1)), typically the viewer's local midnights of a week. Access as for
// GET /users/{userId}/locations (anything not allowed is a plain 404).
func (s *Server) handleHistoryDays(w http.ResponseWriter, r *http.Request) {
	owner, ok := pathID(w, r, "userId")
	if !ok {
		return
	}
	raw := r.URL.Query().Get("bounds")
	parts := strings.Split(raw, ",")
	f := fieldErrors{}
	f.check(raw != "" && len(parts) >= 2 && len(parts) <= maxHistoryDays+1, "bounds", "2–63 comma-separated RFC 3339 timestamps")
	bounds := make([]time.Time, 0, len(parts))
	if len(f) == 0 {
		for i, p := range parts {
			t, err := time.Parse(time.RFC3339, strings.TrimSpace(p))
			f.check(err == nil, "bounds", "RFC 3339 timestamps expected")
			f.check(err != nil || i == 0 || t.After(bounds[i-1]), "bounds", "strictly increasing timestamps expected")
			bounds = append(bounds, t)
		}
	}
	f.check(len(f) > 0 || bounds[len(bounds)-1].Sub(bounds[0]) <= maxHistorySpan, "bounds", "at most 63 days")
	if f.write(w) {
		return
	}
	days, err := s.store.HistoryDays(r.Context(), principalFrom(r).user.ID, owner, bounds)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]historyDayDTO, 0, len(days))
	for _, d := range days {
		out = append(out, historyDayDTO{d.From, d.To, d.Points, d.DistanceM, d.FirstAt, d.LastAt})
	}
	writeJSON(w, http.StatusOK, map[string]any{"days": out})
}

// handleHistoryEvents: GET /users/{userId}/history/events?from=&to= – the person's SOS, check-ins
// and battery alerts in the range, for the history timeline (store.HistoryEvents).
func (s *Server) handleHistoryEvents(w http.ResponseWriter, r *http.Request) {
	owner, ok := pathID(w, r, "userId")
	if !ok {
		return
	}
	q := r.URL.Query()
	f := fieldErrors{}
	from, errFrom := time.Parse(time.RFC3339, q.Get("from"))
	to, errTo := time.Parse(time.RFC3339, q.Get("to"))
	f.check(errFrom == nil, "from", "RFC 3339 timestamp expected")
	f.check(errTo == nil, "to", "RFC 3339 timestamp expected")
	f.check(errFrom != nil || errTo != nil || (to.After(from) && to.Sub(from) <= maxHistorySpan), "to", "after from, at most 63 days")
	if f.write(w) {
		return
	}
	alerts, err := s.store.HistoryEvents(r.Context(), principalFrom(r).user.ID, owner, from, to)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	now := s.now()
	out := make([]alertDTO, 0, len(alerts))
	for _, a := range alerts {
		out = append(out, toAlert(a, now))
	}
	writeJSON(w, http.StatusOK, map[string]any{"events": out})
}
