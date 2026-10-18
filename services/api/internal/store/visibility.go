package store

import (
	"context"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// visibleSQL is THE authorization rule for reading a location. It is used by every query that
// returns location data (map, live hub, avatar-independent single lookups):
//
//	viewer may see owner  ⇔  explicit share owner→viewer
//	                        ∧ owner has not paused sharing
//	                        ∧ owner and viewer currently have at least one family in common
//
// Family roles are deliberately absent: an admin has no extra access.
// owner and viewer must be trusted SQL expressions (column names or $n placeholders).
func visibleSQL(owner, viewer string) string {
	return fmt.Sprintf(`EXISTS (
		SELECT 1 FROM location_shares vs
		JOIN user_settings vus ON vus.user_id = vs.owner_id AND NOT vus.sharing_paused
		WHERE vs.owner_id = %[1]s AND vs.viewer_id = %[2]s
		  AND EXISTS (SELECT 1 FROM family_members vfa
		              JOIN family_members vfb ON vfb.family_id = vfa.family_id
		              WHERE vfa.user_id = %[1]s AND vfb.user_id = %[2]s))`, owner, viewer)
}

// CanView reports whether viewer may currently see owner's location. Owners always see themselves.
func (s *Store) CanView(ctx context.Context, viewer, owner uuid.UUID) (bool, error) {
	if viewer == owner {
		return true, nil
	}
	var ok bool
	err := s.pool.QueryRow(ctx, "SELECT "+visibleSQL("$1", "$2"), owner, viewer).Scan(&ok)
	return ok, err
}

// ViewersOf returns every user that may currently see owner's location (excluding owner).
func (s *Store) ViewersOf(ctx context.Context, owner uuid.UUID) ([]uuid.UUID, error) {
	rows, err := s.pool.Query(ctx, `SELECT u.id FROM users u WHERE u.id <> $1 AND `+visibleSQL("$1", "u.id"), owner)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
}

// SharePair is an (owner, viewer) relation, used to notify viewers when visibility ends.
type SharePair struct {
	Owner, Viewer uuid.UUID
}

// pruneOrphanedShares deletes shares involving user whose parties no longer have a family in
// common. Without this, re-joining a family later would silently resurrect old shares.
func pruneOrphanedShares(ctx context.Context, tx pgx.Tx, user uuid.UUID) ([]SharePair, error) {
	rows, err := tx.Query(ctx, `
		DELETE FROM location_shares s
		WHERE (s.owner_id = $1 OR s.viewer_id = $1)
		  AND NOT EXISTS (SELECT 1 FROM family_members a
		                  JOIN family_members b ON b.family_id = a.family_id
		                  WHERE a.user_id = s.owner_id AND b.user_id = s.viewer_id)
		RETURNING owner_id, viewer_id`, user)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (SharePair, error) {
		var p SharePair
		err := r.Scan(&p.Owner, &p.Viewer)
		return p, err
	})
}
