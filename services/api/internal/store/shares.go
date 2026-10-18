package store

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

type ShareEntry struct {
	UserID      uuid.UUID
	DisplayName string
	Since       time.Time
}

func (s *Store) ListShares(ctx context.Context, userID uuid.UUID) (outgoing, incoming []ShareEntry, err error) {
	scan := func(r pgx.CollectableRow) (ShareEntry, error) {
		var e ShareEntry
		err := r.Scan(&e.UserID, &e.DisplayName, &e.Since)
		return e, err
	}
	rows, err := s.pool.Query(ctx, `SELECT u.id, u.display_name, s.created_at FROM location_shares s
		JOIN users u ON u.id = s.viewer_id WHERE s.owner_id = $1 ORDER BY u.display_name`, userID)
	if err != nil {
		return nil, nil, err
	}
	if outgoing, err = pgx.CollectRows(rows, scan); err != nil {
		return nil, nil, err
	}
	// Incoming lists only shares that are currently effective (not paused, common family).
	rows, err = s.pool.Query(ctx, `SELECT u.id, u.display_name, s.created_at FROM location_shares s
		JOIN users u ON u.id = s.owner_id WHERE s.viewer_id = $1 AND `+visibleSQL("s.owner_id", "s.viewer_id")+`
		ORDER BY u.display_name`, userID)
	if err != nil {
		return nil, nil, err
	}
	incoming, err = pgx.CollectRows(rows, scan)
	return outgoing, incoming, err
}

// GrantShare lets owner share their own location with viewer. Viewer must currently be in a
// common family; otherwise ErrNotFound (no information about foreign users is revealed).
func (s *Store) GrantShare(ctx context.Context, owner, viewer uuid.UUID) error {
	if owner == viewer {
		return ErrNotFound
	}
	tag, err := s.pool.Exec(ctx, `
		INSERT INTO location_shares (owner_id, viewer_id)
		SELECT $1, $2 WHERE EXISTS (SELECT 1 FROM family_members a JOIN family_members b ON b.family_id = a.family_id
		                            WHERE a.user_id = $1 AND b.user_id = $2)
		ON CONFLICT DO NOTHING`, owner, viewer)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		if err := s.pool.QueryRow(ctx, "SELECT EXISTS (SELECT 1 FROM location_shares WHERE owner_id = $1 AND viewer_id = $2)",
			owner, viewer).Scan(&exists); err != nil {
			return err
		}
		if !exists {
			return ErrNotFound
		}
	}
	return nil
}

// RevokeShare is idempotent.
func (s *Store) RevokeShare(ctx context.Context, owner, viewer uuid.UUID) error {
	_, err := s.pool.Exec(ctx, "DELETE FROM location_shares WHERE owner_id = $1 AND viewer_id = $2", owner, viewer)
	return err
}
