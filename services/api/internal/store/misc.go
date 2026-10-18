package store

import (
	"context"
	"time"

	"github.com/google/uuid"
)

func (s *Store) SetAvatar(ctx context.Context, userID uuid.UUID, jpeg []byte) error {
	_, err := s.pool.Exec(ctx, "UPDATE users SET avatar_jpeg = $2, avatar_updated_at = $3 WHERE id = $1", userID, jpeg, s.now())
	return err
}

func (s *Store) DeleteAvatar(ctx context.Context, userID uuid.UUID) error {
	_, err := s.pool.Exec(ctx, "UPDATE users SET avatar_jpeg = NULL, avatar_updated_at = NULL WHERE id = $1", userID)
	return err
}

// Avatar returns target's picture if viewer is target or shares a family with target.
func (s *Store) Avatar(ctx context.Context, viewer, target uuid.UUID) ([]byte, time.Time, error) {
	var img []byte
	var updated time.Time
	err := s.pool.QueryRow(ctx, `SELECT avatar_jpeg, avatar_updated_at FROM users u
		WHERE u.id = $1 AND u.avatar_jpeg IS NOT NULL AND ($1 = $2 OR EXISTS (
			SELECT 1 FROM family_members a JOIN family_members b ON b.family_id = a.family_id
			WHERE a.user_id = $1 AND b.user_id = $2))`, target, viewer).Scan(&img, &updated)
	return img, updated, notFound(err)
}

// PurgeResult counts rows removed by the retention job.
type PurgeResult struct {
	History, Sessions, Invites int64
}

// Purge enforces retention: history older than each user's retention period, long-dead sessions
// and expired invites.
func (s *Store) Purge(ctx context.Context) (PurgeResult, error) {
	var r PurgeResult
	now := s.now()
	tag, err := s.pool.Exec(ctx, `DELETE FROM location_history h USING user_settings us
		WHERE h.user_id = us.user_id AND h.recorded_at < $1 - make_interval(days => us.history_retention_days)`, now)
	if err != nil {
		return r, err
	}
	r.History = tag.RowsAffected()
	tag, err = s.pool.Exec(ctx, `DELETE FROM sessions WHERE revoked_at < $1 - interval '7 days'
		OR (kind = 'web' AND access_expires_at < $1 - interval '7 days')
		OR (kind = 'device' AND refresh_expires_at < $1 - interval '7 days')`, now)
	if err != nil {
		return r, err
	}
	r.Sessions = tag.RowsAffected()
	tag, err = s.pool.Exec(ctx, `DELETE FROM invites WHERE expires_at < $1 - interval '30 days' OR revoked_at < $1 - interval '30 days'`, now)
	if err != nil {
		return r, err
	}
	r.Invites = tag.RowsAffected()
	return r, nil
}
