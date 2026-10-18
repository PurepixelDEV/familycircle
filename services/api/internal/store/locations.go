package store

import (
	"context"
	"errors"
	"sort"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"familycircle/internal/geo"
)

// Point is a validated fix from a device.
type Point struct {
	ID              uuid.UUID
	RecordedAt      time.Time
	Lat, Lon        float64
	AccuracyM       float64
	AltitudeM       *float64
	SpeedMps        *float64
	BearingDeg      *float64
	BatteryPct      *int
	BatteryCharging *bool
	Motion          string
}

// Location is a stored position as returned to authorized readers.
type Location struct {
	Lat, Lon        float64
	AccuracyM       float64
	AltitudeM       *float64
	SpeedMps        *float64
	BearingDeg      *float64
	BatteryPct      *int
	BatteryCharging *bool
	Motion          string
	RecordedAt      time.Time
	ReceivedAt      time.Time
}

// WithoutBattery strips battery data (owner did not opt in to sharing it).
func (l Location) WithoutBattery() Location {
	l.BatteryPct, l.BatteryCharging = nil, nil
	return l
}

type IngestResult struct {
	Stored        int
	LatestChanged bool
	Latest        Location
	ShareBattery  bool
}

// Ingest stores a batch of fixes for userID/deviceID. Identity comes from the authenticated
// session only. Idempotent: history rows are unique per (user, client id) and the latest position
// only moves forward in time, so re-sending a batch has no effect.
func (s *Store) Ingest(ctx context.Context, userID, deviceID uuid.UUID, points []Point) (IngestResult, error) {
	sort.SliceStable(points, func(i, j int) bool { return points[i].RecordedAt.Before(points[j].RecordedAt) })
	now := s.now()
	var res IngestResult
	err := s.tx(ctx, func(tx pgx.Tx) error {
		// The settings row exists for every user and serves as the per-user ingest lock.
		var history bool
		if err := tx.QueryRow(ctx, "SELECT history_enabled, share_battery FROM user_settings WHERE user_id = $1 FOR UPDATE", userID).
			Scan(&history, &res.ShareBattery); err != nil {
			return notFound(err)
		}

		var last, cand *geo.Fix
		var lastLat, lastLon, lastAcc, candLat, candLon, candAcc *float64
		var lastAt, candAt *time.Time
		err := tx.QueryRow(ctx, `SELECT ST_Y(position::geometry), ST_X(position::geometry), accuracy_m::float8, recorded_at,
			ST_Y(candidate_position::geometry), ST_X(candidate_position::geometry), candidate_accuracy_m::float8, candidate_recorded_at
			FROM locations_latest WHERE user_id = $1`, userID).
			Scan(&lastLat, &lastLon, &lastAcc, &lastAt, &candLat, &candLon, &candAcc, &candAt)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		if lastAt != nil {
			last = &geo.Fix{Lat: *lastLat, Lon: *lastLon, AccuracyM: *lastAcc, At: *lastAt}
		}
		if candAt != nil {
			cand = &geo.Fix{Lat: *candLat, Lon: *candLon, AccuracyM: *candAcc, At: *candAt}
		}

		var newest *Point
		candChanged := false
		for i := range points {
			p := &points[i]
			f := geo.Fix{Lat: p.Lat, Lon: p.Lon, AccuracyM: p.AccuracyM, At: p.RecordedAt}
			suspect := geo.IsSuspect(last, cand, f)
			if history {
				tag, err := tx.Exec(ctx, `INSERT INTO location_history (user_id, device_id, client_event_id, position, accuracy_m,
					altitude_m, speed_mps, bearing_deg, battery_pct, battery_charging, motion, suspect, recorded_at, received_at)
					VALUES ($1, $2, $3, ST_SetSRID(ST_MakePoint($5, $4), 4326)::geography, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)
					ON CONFLICT (user_id, client_event_id) DO NOTHING`,
					userID, deviceID, p.ID, p.Lat, p.Lon, p.AccuracyM, p.AltitudeM, p.SpeedMps, p.BearingDeg,
					p.BatteryPct, p.BatteryCharging, p.Motion, suspect, p.RecordedAt, now)
				if err != nil {
					return err
				}
				res.Stored += int(tag.RowsAffected())
			}
			switch {
			case suspect:
				if cand == nil || f.At.After(cand.At) {
					cand, candChanged = &f, true
				}
			case last == nil || f.At.After(last.At):
				last, newest = &f, p
				if cand != nil {
					cand, candChanged = nil, true
				}
			}
		}

		if newest != nil {
			res.LatestChanged = true
			res.Latest = Location{Lat: newest.Lat, Lon: newest.Lon, AccuracyM: newest.AccuracyM, AltitudeM: newest.AltitudeM,
				SpeedMps: newest.SpeedMps, BearingDeg: newest.BearingDeg, BatteryPct: newest.BatteryPct,
				BatteryCharging: newest.BatteryCharging, Motion: newest.Motion, RecordedAt: newest.RecordedAt, ReceivedAt: now}
			_, err := tx.Exec(ctx, `
				INSERT INTO locations_latest (user_id, device_id, position, accuracy_m, altitude_m, speed_mps, bearing_deg,
					battery_pct, battery_charging, motion, recorded_at, received_at, candidate_position, candidate_accuracy_m, candidate_recorded_at)
				VALUES ($1, $2, ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography, $5, $6, $7, $8, $9, $10, $11, $12, $13, NULL, NULL, NULL)
				ON CONFLICT (user_id) DO UPDATE SET device_id = EXCLUDED.device_id, position = EXCLUDED.position,
					accuracy_m = EXCLUDED.accuracy_m, altitude_m = EXCLUDED.altitude_m, speed_mps = EXCLUDED.speed_mps,
					bearing_deg = EXCLUDED.bearing_deg, battery_pct = EXCLUDED.battery_pct, battery_charging = EXCLUDED.battery_charging,
					motion = EXCLUDED.motion, recorded_at = EXCLUDED.recorded_at, received_at = EXCLUDED.received_at,
					candidate_position = NULL, candidate_accuracy_m = NULL, candidate_recorded_at = NULL`,
				userID, deviceID, newest.Lat, newest.Lon, newest.AccuracyM, newest.AltitudeM, newest.SpeedMps, newest.BearingDeg,
				newest.BatteryPct, newest.BatteryCharging, newest.Motion, newest.RecordedAt, now)
			if err != nil {
				return err
			}
			candChanged = cand != nil
		}
		if candChanged {
			if cand == nil {
				_, err = tx.Exec(ctx, `UPDATE locations_latest SET candidate_position = NULL, candidate_accuracy_m = NULL,
					candidate_recorded_at = NULL WHERE user_id = $1`, userID)
			} else {
				_, err = tx.Exec(ctx, `UPDATE locations_latest SET candidate_position = ST_SetSRID(ST_MakePoint($3, $2), 4326)::geography,
					candidate_accuracy_m = $4, candidate_recorded_at = $5 WHERE user_id = $1`,
					userID, cand.Lat, cand.Lon, cand.AccuracyM, cand.At)
			}
			if err != nil {
				return err
			}
		}
		_, err = tx.Exec(ctx, "UPDATE devices SET last_seen_at = $2 WHERE id = $1", deviceID, now)
		return err
	})
	return res, err
}

const locationColumns = `ST_Y(l.position::geometry), ST_X(l.position::geometry), l.accuracy_m::float8, l.altitude_m::float8,
	l.speed_mps::float8, l.bearing_deg::float8, l.battery_pct::int, l.battery_charging, l.motion, l.recorded_at, l.received_at`

func scanLocationPtr(dest []any, loc *Location) []any {
	return append(dest, &loc.Lat, &loc.Lon, &loc.AccuracyM, &loc.AltitudeM, &loc.SpeedMps, &loc.BearingDeg,
		&loc.BatteryPct, &loc.BatteryCharging, &loc.Motion, &loc.RecordedAt, &loc.ReceivedAt)
}

// LatestFor returns owner's last location as viewer may see it, or (nil, nil) if not visible / none.
func (s *Store) LatestFor(ctx context.Context, viewer, owner uuid.UUID) (*Location, error) {
	var loc Location
	var shareBattery bool
	dest := scanLocationPtr(nil, &loc)
	dest = append(dest, &shareBattery)
	err := s.pool.QueryRow(ctx, `SELECT `+locationColumns+`, us.share_battery
		FROM locations_latest l JOIN user_settings us ON us.user_id = l.user_id
		WHERE l.user_id = $1 AND ($1 = $2 OR `+visibleSQL("$1", "$2")+`)`, owner, viewer).Scan(dest...)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if viewer != owner && !shareBattery {
		loc = loc.WithoutBattery()
	}
	return &loc, nil
}

type MapMember struct {
	UserID          uuid.UUID
	DisplayName     string
	Color           string
	AvatarUpdatedAt *time.Time
	IsSelf          bool
	Location        *Location
}

// FamilyMap returns all members of the family with locations only where viewer is authorized.
// The authorization is part of the JOIN, so unauthorized coordinates never leave the database.
func (s *Store) FamilyMap(ctx context.Context, familyID, viewer uuid.UUID) ([]MapMember, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT u.id, u.display_name, m.color, u.avatar_updated_at, u.id = $2,
		       ST_Y(l.position::geometry), ST_X(l.position::geometry), l.accuracy_m::float8, l.altitude_m::float8,
		       l.speed_mps::float8, l.bearing_deg::float8,
		       CASE WHEN u.id = $2 OR us.share_battery THEN l.battery_pct::int END,
		       CASE WHEN u.id = $2 OR us.share_battery THEN l.battery_charging END,
		       l.motion, l.recorded_at, l.received_at
		FROM family_members m
		JOIN users u ON u.id = m.user_id
		JOIN user_settings us ON us.user_id = u.id
		LEFT JOIN locations_latest l ON l.user_id = u.id AND (u.id = $2 OR `+visibleSQL("u.id", "$2")+`)
		WHERE m.family_id = $1
		  AND EXISTS (SELECT 1 FROM family_members me WHERE me.family_id = $1 AND me.user_id = $2)
		ORDER BY (u.id = $2) DESC, m.joined_at`, familyID, viewer)
	if err != nil {
		return nil, err
	}
	members, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (MapMember, error) {
		var m MapMember
		var lat, lon, acc *float64
		var motion *string
		var recorded, received *time.Time
		var loc Location
		err := r.Scan(&m.UserID, &m.DisplayName, &m.Color, &m.AvatarUpdatedAt, &m.IsSelf,
			&lat, &lon, &acc, &loc.AltitudeM, &loc.SpeedMps, &loc.BearingDeg, &loc.BatteryPct, &loc.BatteryCharging,
			&motion, &recorded, &received)
		if err == nil && lat != nil {
			loc.Lat, loc.Lon, loc.AccuracyM, loc.Motion, loc.RecordedAt, loc.ReceivedAt = *lat, *lon, *acc, *motion, *recorded, *received
			m.Location = &loc
		}
		return m, err
	})
	if err != nil {
		return nil, err
	}
	if len(members) == 0 {
		return nil, ErrNotFound
	}
	return members, nil
}

type HistoryPoint struct {
	ID      uuid.UUID
	Suspect bool
	Location
}

func (s *Store) History(ctx context.Context, userID uuid.UUID, from, to time.Time, limit int) ([]HistoryPoint, error) {
	rows, err := s.pool.Query(ctx, `SELECT l.client_event_id, l.suspect, `+locationColumns+`
		FROM location_history l WHERE l.user_id = $1 AND l.recorded_at >= $2 AND l.recorded_at <= $3
		ORDER BY l.recorded_at DESC LIMIT $4`, userID, from, to, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (HistoryPoint, error) {
		var h HistoryPoint
		err := r.Scan(scanLocationPtr([]any{&h.ID, &h.Suspect}, &h.Location)...)
		return h, err
	})
}

// DeleteAllLocations removes every stored position of the user (latest and history).
func (s *Store) DeleteAllLocations(ctx context.Context, userID uuid.UUID) error {
	return s.tx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "DELETE FROM location_history WHERE user_id = $1", userID); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, "DELETE FROM locations_latest WHERE user_id = $1", userID)
		return err
	})
}
