package store

import (
	"context"
	"errors"
	"math"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

const (
	MaxPlacesPerFamily = 50
	MinPlaceRadiusM    = 50
	MaxPlaceRadiusM    = 2000

	// presenceMaxAccuracyM: fixes less precise than this never change presence.
	presenceMaxAccuracyM = 500.0
)

type Place struct {
	ID        uuid.UUID
	FamilyID  uuid.UUID
	Name      string
	Lat, Lon  float64
	RadiusM   int
	CreatedBy *uuid.UUID
	CreatedAt time.Time
	Muted     bool        // for the reader
	Present   []uuid.UUID // members inside whose location the reader may see (and the reader)
}

type PlaceUpdate struct {
	Name     *string
	Lat, Lon *float64
	RadiusM  *int
}

// PresenceChange is an arrival at or departure from a place, detected while ingesting a fix.
type PresenceChange struct {
	PlaceID   uuid.UUID
	FamilyID  uuid.UUID
	PlaceName string
	Arrived   bool
	At        time.Time
}

// PresenceDecision is the hysteresis rule (pure, unit-tested):
//
//	enter  distance ≤ radius and the fix is precise enough for the place (≤ max(2·radius, 200 m))
//	leave  distance > radius + max(50 m, accuracy)
//
// Between the two thresholds the previous state is kept, so jitter at the edge produces no alerts.
// Fixes with accuracy > 500 m never change anything. ok=false means "no decision".
func PresenceDecision(wasInside bool, distanceM, radiusM, accuracyM float64) (inside, ok bool) {
	if accuracyM > presenceMaxAccuracyM {
		return wasInside, false
	}
	if !wasInside {
		if distanceM <= radiusM && accuracyM <= math.Max(2*radiusM, 200) {
			return true, true
		}
		return false, true
	}
	if distanceM > radiusM+math.Max(50, accuracyM) {
		return false, true
	}
	return true, true
}

// updatePresence evaluates the user's places with a new accepted fix (inside the ingest
// transaction). The first evaluation of a place only records the state – creating a place never
// triggers alerts. Older fixes than the last change are ignored.
func updatePresence(ctx context.Context, tx pgx.Tx, userID uuid.UUID, lat, lon, accuracyM float64, at time.Time) ([]PresenceChange, error) {
	rows, err := tx.Query(ctx, `SELECT p.id, p.family_id, p.name, p.radius_m::float8,
			ST_Distance(p.position, ST_SetSRID(ST_MakePoint($3, $2), 4326)::geography), pp.inside, pp.changed_at
		FROM places p
		JOIN family_members fm ON fm.family_id = p.family_id AND fm.user_id = $1
		LEFT JOIN place_presence pp ON pp.place_id = p.id AND pp.user_id = $1`, userID, lat, lon)
	if err != nil {
		return nil, err
	}
	type row struct {
		id, family   uuid.UUID
		name         string
		radius, dist float64
		inside       *bool
		changedAt    *time.Time
	}
	list, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (row, error) {
		var x row
		err := r.Scan(&x.id, &x.family, &x.name, &x.radius, &x.dist, &x.inside, &x.changedAt)
		return x, err
	})
	if err != nil {
		return nil, err
	}
	var changes []PresenceChange
	for _, x := range list {
		if x.changedAt != nil && !at.After(*x.changedAt) {
			continue
		}
		was := x.inside != nil && *x.inside
		inside, ok := PresenceDecision(was, x.dist, x.radius, accuracyM)
		if !ok {
			continue
		}
		switch {
		case x.inside == nil:
			if _, err := tx.Exec(ctx, `INSERT INTO place_presence (place_id, user_id, inside, changed_at) VALUES ($1, $2, $3, $4)
				ON CONFLICT (place_id, user_id) DO NOTHING`, x.id, userID, inside, at); err != nil {
				return nil, err
			}
		case inside != was:
			if _, err := tx.Exec(ctx, `UPDATE place_presence SET inside = $3, changed_at = $4 WHERE place_id = $1 AND user_id = $2`,
				x.id, userID, inside, at); err != nil {
				return nil, err
			}
			changes = append(changes, PresenceChange{PlaceID: x.id, FamilyID: x.family, PlaceName: x.name, Arrived: inside, At: at})
		}
	}
	return changes, nil
}

func requireMember(ctx context.Context, q pgx.Tx, familyID, user uuid.UUID) (string, error) {
	var role string
	err := q.QueryRow(ctx, "SELECT role FROM family_members WHERE family_id = $1 AND user_id = $2", familyID, user).Scan(&role)
	return role, notFound(err)
}

// ListPlaces returns the family's places for a member. Presence is filtered by visibility:
// a place only shows people whose location the reader may see.
func (s *Store) ListPlaces(ctx context.Context, familyID, viewer uuid.UUID) ([]Place, error) {
	var out []Place
	err := s.tx(ctx, func(tx pgx.Tx) error {
		if _, err := requireMember(ctx, tx, familyID, viewer); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT p.id, p.family_id, p.name, ST_Y(p.position::geometry), ST_X(p.position::geometry),
				p.radius_m, p.created_by, p.created_at,
				EXISTS (SELECT 1 FROM place_mutes m WHERE m.place_id = p.id AND m.user_id = $2),
				COALESCE(ARRAY(SELECT pp.user_id FROM place_presence pp
					JOIN family_members fm ON fm.family_id = p.family_id AND fm.user_id = pp.user_id
					WHERE pp.place_id = p.id AND pp.inside AND (pp.user_id = $2 OR `+visibleSQL("pp.user_id", "$2")+`)
					ORDER BY pp.user_id), '{}')
			FROM places p WHERE p.family_id = $1 ORDER BY lower(p.name), p.id`, familyID, viewer)
		if err != nil {
			return err
		}
		out, err = pgx.CollectRows(rows, func(r pgx.CollectableRow) (Place, error) {
			var p Place
			err := r.Scan(&p.ID, &p.FamilyID, &p.Name, &p.Lat, &p.Lon, &p.RadiusM, &p.CreatedBy, &p.CreatedAt, &p.Muted, &p.Present)
			return p, err
		})
		return err
	})
	return out, err
}

// CreatePlace: any member may add a place (at most MaxPlacesPerFamily).
func (s *Store) CreatePlace(ctx context.Context, familyID, actor uuid.UUID, name string, lat, lon float64, radiusM int) (uuid.UUID, error) {
	var id uuid.UUID
	err := s.tx(ctx, func(tx pgx.Tx) error {
		if err := lockFamilies(ctx, tx, familyID); err != nil {
			return err
		}
		if _, err := requireMember(ctx, tx, familyID, actor); err != nil {
			return err
		}
		var n int
		if err := tx.QueryRow(ctx, "SELECT count(*) FROM places WHERE family_id = $1", familyID).Scan(&n); err != nil {
			return err
		}
		if n >= MaxPlacesPerFamily {
			return ErrConflict
		}
		return tx.QueryRow(ctx, `INSERT INTO places (family_id, name, position, radius_m, created_by)
			VALUES ($1, $2, ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography, $5, $6) RETURNING id`,
			familyID, name, lat, lon, radiusM, actor).Scan(&id)
	})
	return id, err
}

// placeForEdit loads a place for a change: members only (else ErrNotFound), and only the creator or
// a family admin may change it (else ErrForbidden).
func placeForEdit(ctx context.Context, tx pgx.Tx, placeID, actor uuid.UUID) (uuid.UUID, error) {
	var family uuid.UUID
	var createdBy *uuid.UUID
	if err := tx.QueryRow(ctx, "SELECT family_id, created_by FROM places WHERE id = $1 FOR UPDATE", placeID).
		Scan(&family, &createdBy); err != nil {
		return uuid.Nil, notFound(err)
	}
	role, err := requireMember(ctx, tx, family, actor)
	if err != nil {
		return uuid.Nil, err
	}
	if role != "admin" && (createdBy == nil || *createdBy != actor) {
		return uuid.Nil, ErrForbidden
	}
	return family, nil
}

// UpdatePlace changes a place. Moving it or changing the radius resets presence, so the next fix
// re-establishes the state silently instead of producing arrival/departure alerts.
func (s *Store) UpdatePlace(ctx context.Context, placeID, actor uuid.UUID, u PlaceUpdate) (uuid.UUID, error) {
	var family uuid.UUID
	err := s.tx(ctx, func(tx pgx.Tx) error {
		var err error
		if family, err = placeForEdit(ctx, tx, placeID, actor); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE places SET
				name = COALESCE($2, name),
				position = CASE WHEN $3::float8 IS NULL THEN position ELSE ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography END,
				radius_m = COALESCE($5, radius_m),
				updated_at = now()
			WHERE id = $1`, placeID, u.Name, u.Lat, u.Lon, u.RadiusM); err != nil {
			return err
		}
		if u.Lat != nil || u.RadiusM != nil {
			_, err = tx.Exec(ctx, "DELETE FROM place_presence WHERE place_id = $1", placeID)
		}
		return err
	})
	return family, err
}

func (s *Store) DeletePlace(ctx context.Context, placeID, actor uuid.UUID) (uuid.UUID, error) {
	var family uuid.UUID
	err := s.tx(ctx, func(tx pgx.Tx) error {
		var err error
		if family, err = placeForEdit(ctx, tx, placeID, actor); err != nil {
			return err
		}
		_, err = tx.Exec(ctx, "DELETE FROM places WHERE id = $1", placeID)
		return err
	})
	return family, err
}

// SetPlaceMuted turns arrival/departure alerts of a place off or on for a member.
func (s *Store) SetPlaceMuted(ctx context.Context, placeID, user uuid.UUID, muted bool) error {
	return s.tx(ctx, func(tx pgx.Tx) error {
		var family uuid.UUID
		if err := tx.QueryRow(ctx, "SELECT family_id FROM places WHERE id = $1", placeID).Scan(&family); err != nil {
			return notFound(err)
		}
		if _, err := requireMember(ctx, tx, family, user); err != nil {
			return err
		}
		var err error
		if muted {
			_, err = tx.Exec(ctx, "INSERT INTO place_mutes (place_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING", placeID, user)
		} else {
			_, err = tx.Exec(ctx, "DELETE FROM place_mutes WHERE place_id = $1 AND user_id = $2", placeID, user)
		}
		return err
	})
}

// PlaceAt returns the closest place of the user's families that contains the point (check-ins).
func (s *Store) PlaceAt(ctx context.Context, userID uuid.UUID, lat, lon float64) (*Place, error) {
	var p Place
	err := s.pool.QueryRow(ctx, `SELECT p.id, p.family_id, p.name FROM places p
		JOIN family_members fm ON fm.family_id = p.family_id AND fm.user_id = $1
		WHERE ST_DWithin(p.position, ST_SetSRID(ST_MakePoint($3, $2), 4326)::geography, p.radius_m)
		ORDER BY ST_Distance(p.position, ST_SetSRID(ST_MakePoint($3, $2), 4326)::geography) LIMIT 1`,
		userID, lat, lon).Scan(&p.ID, &p.FamilyID, &p.Name)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &p, nil
}

// forgetFamilyPlaces removes presence and mutes of a user who left a family.
func forgetFamilyPlaces(ctx context.Context, tx pgx.Tx, familyID, user uuid.UUID) error {
	if _, err := tx.Exec(ctx, `DELETE FROM place_presence pp USING places p
		WHERE pp.place_id = p.id AND p.family_id = $1 AND pp.user_id = $2`, familyID, user); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `DELETE FROM place_mutes pm USING places p
		WHERE pm.place_id = p.id AND p.family_id = $1 AND pm.user_id = $2`, familyID, user)
	return err
}
