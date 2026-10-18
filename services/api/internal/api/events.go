package api

import (
	"context"
	"encoding/json"

	"github.com/google/uuid"

	"familycircle/internal/store"
)

// Live events. Recipients are always computed from the database at send time.
// Errors are logged, never returned: a failed notification must not fail the user's request
// (clients also refetch periodically and on reconnect).

type locationEvent struct {
	Type     string      `json:"type"`
	UserID   uuid.UUID   `json:"user_id"`
	Location locationDTO `json:"location"`
}

type userEvent struct {
	Type   string    `json:"type"`
	UserID uuid.UUID `json:"user_id"`
}

type familyEvent struct {
	Type     string    `json:"type"`
	FamilyID uuid.UUID `json:"family_id"`
}

func mustJSON(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}

// publishLocation sends owner's new location to the owner and every currently authorized viewer.
func (s *Server) publishLocation(ctx context.Context, owner uuid.UUID, loc store.Location, shareBattery bool) {
	viewers, err := s.store.ViewersOf(ctx, owner)
	if err != nil {
		s.log.Error("publish: load viewers", "err", err)
		return
	}
	now := s.now()
	s.hub.SendTo([]uuid.UUID{owner}, mustJSON(locationEvent{"location", owner, toLocation(loc, now)}))
	if len(viewers) == 0 {
		return
	}
	if !shareBattery {
		loc = loc.WithoutBattery()
	}
	s.hub.SendTo(viewers, mustJSON(locationEvent{"location", owner, toLocation(loc, now)}))
}

// republish re-sends owner's current location (e.g. after unpausing or granting a share).
func (s *Server) republish(ctx context.Context, owner uuid.UUID) {
	loc, err := s.store.LatestFor(ctx, owner, owner)
	if err != nil || loc == nil {
		return
	}
	st, err := s.store.Settings(ctx, owner)
	if err != nil {
		return
	}
	s.publishLocation(ctx, owner, *loc, st.ShareBattery)
}

// sendLocationTo sends owner's location to a single viewer if (and only if) it is visible.
func (s *Server) sendLocationTo(ctx context.Context, owner, viewer uuid.UUID) {
	loc, err := s.store.LatestFor(ctx, viewer, owner)
	if err != nil || loc == nil {
		return
	}
	s.hub.SendTo([]uuid.UUID{viewer}, mustJSON(locationEvent{"location", owner, toLocation(*loc, s.now())}))
}

func (s *Server) notifyHidden(owner uuid.UUID, viewers []uuid.UUID) {
	if len(viewers) > 0 {
		s.hub.SendTo(viewers, mustJSON(userEvent{"location_hidden", owner}))
	}
}

func (s *Server) notifyEndedShares(pairs []store.SharePair) {
	for _, p := range pairs {
		s.notifyHidden(p.Owner, []uuid.UUID{p.Viewer})
	}
}

func (s *Server) notifyFamilyChanged(ctx context.Context, familyID uuid.UUID) {
	members, err := s.store.FamilyMemberIDs(ctx, familyID)
	if err != nil {
		s.log.Error("notify family: load members", "err", err)
		return
	}
	s.hub.SendTo(members, mustJSON(familyEvent{"family_changed", familyID}))
}
