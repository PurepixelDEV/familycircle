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
	AllowRing            bool
	AllowLocateRequest   bool
	BatteryAlerts        bool
}

type SettingsUpdate struct {
	SharingPaused        *bool
	HistoryEnabled       *bool
	HistoryRetentionDays *int
	ShareBattery         *bool
	AllowRing            *bool
	AllowLocateRequest   *bool
	BatteryAlerts        *bool
}

const settingsColumns = `sharing_paused, history_enabled, history_retention_days, share_battery,
	allow_ring, allow_locate_request, battery_alerts`

func (st *Settings) scanTargets() []any {
	return []any{&st.SharingPaused, &st.HistoryEnabled, &st.HistoryRetentionDays, &st.ShareBattery,
		&st.AllowRing, &st.AllowLocateRequest, &st.BatteryAlerts}
}

func (s *Store) Settings(ctx context.Context, userID uuid.UUID) (Settings, error) {
	var st Settings
	err := s.pool.QueryRow(ctx, `SELECT `+settingsColumns+` FROM user_settings WHERE user_id = $1`, userID).
		Scan(st.scanTargets()...)
	return st, notFound(err)
}

// UpdateSettings applies a partial update. Disabling history deletes the stored history
// immediately – the user's choice is honoured retroactively. Returns old and new settings.
func (s *Store) UpdateSettings(ctx context.Context, userID uuid.UUID, u SettingsUpdate) (before, after Settings, err error) {
	err = s.tx(ctx, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `SELECT `+settingsColumns+` FROM user_settings WHERE user_id = $1 FOR UPDATE`, userID).
			Scan(before.scanTargets()...); err != nil {
			return notFound(err)
		}
		if err := tx.QueryRow(ctx, `UPDATE user_settings SET
				sharing_paused = COALESCE($2, sharing_paused),
				history_enabled = COALESCE($3, history_enabled),
				history_retention_days = COALESCE($4, history_retention_days),
				share_battery = COALESCE($5, share_battery),
				allow_ring = COALESCE($6, allow_ring),
				allow_locate_request = COALESCE($7, allow_locate_request),
				battery_alerts = COALESCE($8, battery_alerts),
				updated_at = now()
			WHERE user_id = $1
			RETURNING `+settingsColumns,
			userID, u.SharingPaused, u.HistoryEnabled, u.HistoryRetentionDays, u.ShareBattery,
			u.AllowRing, u.AllowLocateRequest, u.BatteryAlerts).
			Scan(after.scanTargets()...); err != nil {
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
