package api

import (
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"familycircle/internal/store"
)

type placeDTO struct {
	ID        uuid.UUID   `json:"id"`
	FamilyID  uuid.UUID   `json:"family_id"`
	Name      string      `json:"name"`
	Lat       float64     `json:"lat"`
	Lon       float64     `json:"lon"`
	RadiusM   int         `json:"radius_m"`
	CreatedBy *uuid.UUID  `json:"created_by"`
	CreatedAt time.Time   `json:"created_at"`
	Muted     bool        `json:"muted"`
	Present   []uuid.UUID `json:"present"`
}

type placesChangedEvent struct {
	Type     string    `json:"type"`
	FamilyID uuid.UUID `json:"family_id"`
}

func toPlace(p store.Place) placeDTO {
	present := p.Present
	if present == nil {
		present = []uuid.UUID{}
	}
	return placeDTO{p.ID, p.FamilyID, p.Name, p.Lat, p.Lon, p.RadiusM, p.CreatedBy, p.CreatedAt, p.Muted, present}
}

type placeIn struct {
	Name    *string  `json:"name"`
	Lat     *float64 `json:"lat"`
	Lon     *float64 `json:"lon"`
	RadiusM *int     `json:"radius_m"`
}

// check validates the given fields; create requires all of them.
func (in *placeIn) check(create bool) fieldErrors {
	f := fieldErrors{}
	if in.Name != nil {
		n := strings.TrimSpace(*in.Name)
		in.Name = &n
		f.check(len([]rune(n)) >= 1 && len([]rune(n)) <= 64, "name", "1–64 characters")
	} else {
		f.check(!create, "name", "required")
	}
	if in.Lat != nil || in.Lon != nil || create {
		f.check(in.Lat != nil && in.Lon != nil && finite(in.Lat) && finite(in.Lon) &&
			*in.Lat >= -90 && *in.Lat <= 90 && *in.Lon >= -180 && *in.Lon <= 180, "lat", "lat and lon must form a valid position")
	}
	if in.RadiusM != nil {
		f.check(*in.RadiusM >= store.MinPlaceRadiusM && *in.RadiusM <= store.MaxPlaceRadiusM, "radius_m", "50–2000")
	} else {
		f.check(!create, "radius_m", "required")
	}
	return f
}

func (s *Server) notifyPlacesChanged(r *http.Request, familyID uuid.UUID) {
	members, err := s.store.FamilyMemberIDs(r.Context(), familyID)
	if err != nil {
		s.log.Error("notify places: load members", "err", err)
		return
	}
	s.hub.SendTo(members, mustJSON(placesChangedEvent{"places_changed", familyID}))
}

func (s *Server) handleListPlaces(w http.ResponseWriter, r *http.Request) {
	familyID, ok := pathID(w, r, "familyId")
	if !ok {
		return
	}
	places, err := s.store.ListPlaces(r.Context(), familyID, principalFrom(r).user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]placeDTO, 0, len(places))
	for _, p := range places {
		out = append(out, toPlace(p))
	}
	writeJSON(w, http.StatusOK, map[string]any{"places": out})
}

func (s *Server) handleCreatePlace(w http.ResponseWriter, r *http.Request) {
	familyID, ok := pathID(w, r, "familyId")
	if !ok {
		return
	}
	var in placeIn
	if !decode(w, r, &in, 0) {
		return
	}
	if in.check(true).write(w) {
		return
	}
	id, err := s.store.CreatePlace(r.Context(), familyID, principalFrom(r).user.ID, *in.Name, *in.Lat, *in.Lon, *in.RadiusM)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyPlacesChanged(r, familyID)
	writeJSON(w, http.StatusCreated, map[string]any{"id": id})
}

func (s *Server) handleUpdatePlace(w http.ResponseWriter, r *http.Request) {
	placeID, ok := pathID(w, r, "placeId")
	if !ok {
		return
	}
	var in placeIn
	if !decode(w, r, &in, 0) {
		return
	}
	if in.check(false).write(w) {
		return
	}
	familyID, err := s.store.UpdatePlace(r.Context(), placeID, principalFrom(r).user.ID,
		store.PlaceUpdate{Name: in.Name, Lat: in.Lat, Lon: in.Lon, RadiusM: in.RadiusM})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyPlacesChanged(r, familyID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleDeletePlace(w http.ResponseWriter, r *http.Request) {
	placeID, ok := pathID(w, r, "placeId")
	if !ok {
		return
	}
	familyID, err := s.store.DeletePlace(r.Context(), placeID, principalFrom(r).user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyPlacesChanged(r, familyID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handlePlaceMute(muted bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		placeID, ok := pathID(w, r, "placeId")
		if !ok {
			return
		}
		if err := s.store.SetPlaceMuted(r.Context(), placeID, principalFrom(r).user.ID, muted); err != nil {
			s.fail(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// handleUpdateShare: PATCH /shares/{userId} {"history": bool} – let viewer see my history.
func (s *Server) handleUpdateShare(w http.ResponseWriter, r *http.Request) {
	viewer, ok := pathID(w, r, "userId")
	if !ok {
		return
	}
	var in struct {
		History *bool `json:"history"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	if in.History == nil {
		writeFieldErrors(w, http.StatusBadRequest, "invalid_input", "invalid input", map[string]string{"history": "required"})
		return
	}
	owner := principalFrom(r).user.ID
	if err := s.store.SetShareHistory(r.Context(), owner, viewer, *in.History); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleUserHistory: GET /users/{userId}/locations – own history, or another person's if they
// granted it (store.HistoryFor). Same parameters as GET /me/locations.
func (s *Server) handleUserHistory(w http.ResponseWriter, r *http.Request) {
	owner, ok := pathID(w, r, "userId")
	if !ok {
		return
	}
	from, to, limit, ok := historyParams(w, r, s.now())
	if !ok {
		return
	}
	points, err := s.store.HistoryFor(r.Context(), principalFrom(r).user.ID, owner, from, to, limit)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeHistory(w, points, s.now())
}
