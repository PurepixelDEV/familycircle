package store

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

type Settings struct {
	SharingPaused        bool
	HistoryEnabled       bool
	HistoryRetentionDays int
	ShareBattery         bool
}

type SettingsUpdate struct {
	SharingPaused        *bool
	HistoryEnabled       *bool
	HistoryRetentionDays *int
	ShareBattery         *bool
}

func (s *Store) Settings(ctx context.Context, userID uuid.UUID) (Settings, error) {
	var st Settings
	err := s.pool.QueryRow(ctx, `SELECT sharing_paused, history_enabled, history_retention_days, share_battery
		FROM user_settings WHERE user_id = $1`, userID).
		Scan(&st.SharingPaused, &st.HistoryEnabled, &st.HistoryRetentionDays, &st.ShareBattery)
	return st, notFound(err)
}

// UpdateSettings applies a partial update. Disabling history deletes the stored history
// immediately – the user's choice is honoured retroactively. Returns old and new settings.
func (s *Store) UpdateSettings(ctx context.Context, userID uuid.UUID, u SettingsUpdate) (before, after Settings, err error) {
	err = s.tx(ctx, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `SELECT sharing_paused, history_enabled, history_retention_days, share_battery
			FROM user_settings WHERE user_id = $1 FOR UPDATE`, userID).
			Scan(&before.SharingPaused, &before.HistoryEnabled, &before.HistoryRetentionDays, &before.ShareBattery); err != nil {
			return notFound(err)
		}
		if err := tx.QueryRow(ctx, `UPDATE user_settings SET
				sharing_paused = COALESCE($2, sharing_paused),
				history_enabled = COALESCE($3, history_enabled),
				history_retention_days = COALESCE($4, history_retention_days),
				share_battery = COALESCE($5, share_battery),
				updated_at = now()
			WHERE user_id = $1
			RETURNING sharing_paused, history_enabled, history_retention_days, share_battery`,
			userID, u.SharingPaused, u.HistoryEnabled, u.HistoryRetentionDays, u.ShareBattery).
			Scan(&after.SharingPaused, &after.HistoryEnabled, &after.HistoryRetentionDays, &after.ShareBattery); err != nil {
			return err
		}
		if before.HistoryEnabled && !after.HistoryEnabled {
			if _, err := tx.Exec(ctx, "DELETE FROM location_history WHERE user_id = $1", userID); err != nil {
				return err
			}
		}
		return nil
	})
	return before, after, err
}
