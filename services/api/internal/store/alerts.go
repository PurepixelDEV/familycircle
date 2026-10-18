package store

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// Alert kinds (docs/ALERTS.md).
const (
	AlertSOS          = "sos"
	AlertSOSResolved  = "sos_resolved"
	AlertCheckIn      = "check_in"
	AlertArrival      = "arrival"
	AlertDeparture    = "departure"
	AlertBatteryLow   = "battery_low"
	AlertLastLocation = "last_location"
	AlertRing         = "ring"
	AlertLocate       = "locate"
)

// alertRetention bounds how far back GET /alerts looks; the purge job deletes older rows.
const alertRetention = 30 * 24 * time.Hour

// NewAlert describes an alert to create. Recipients are derived from Kind (see audience).
type NewAlert struct {
	Kind        string
	Actor       uuid.UUID
	ActorDevice *uuid.UUID
	Target      uuid.UUID  // ring, locate: the person whose phone is addressed (may be Actor)
	PlaceID     *uuid.UUID // arrival, departure, optionally check_in
	FamilyID    uuid.UUID  // arrival, departure: family of the place
	PlaceName   *string
	Lat, Lon    *float64
	AccuracyM   *float64
	BatteryPct  *int
	Message     *string
	ExpiresAt   *time.Time
}

type Alert struct {
	ID            int64
	Kind          string
	ActorID       uuid.UUID
	ActorName     string
	ActorDeviceID *uuid.UUID
	PlaceID       *uuid.UUID
	PlaceName     *string
	Lat, Lon      *float64
	AccuracyM     *float64
	BatteryPct    *int
	Message       *string
	CreatedAt     time.Time
	ExpiresAt     *time.Time
	ResolvedAt    *time.Time
	Mine          bool // sent by the reader (own SOS / check-in), not received
}

// commonFamilySQL: a and b currently share at least one family.
func commonFamilySQL(a, b string) string {
	return fmt.Sprintf(`EXISTS (SELECT 1 FROM family_members ca JOIN family_members cb ON cb.family_id = ca.family_id
		WHERE ca.user_id = %s AND cb.user_id = %s)`, a, b)
}

// audience returns the condition on users u (and its arguments, numbered from $2) that selects
// the recipients of an alert. This is THE recipient rule:
//
//	sos, sos_resolved, check_in   explicit action → everyone who shares a family with the actor
//	arrival, departure            automatic → members of the place's family who may see the actor's
//	                              location (visibleSQL) and did not mute the place
//	battery_low, last_location    automatic → whoever may see the actor's location
//	ring, locate                  the addressed person only (authorization: RemoteRequestAllowed)
func audience(a NewAlert) (string, []any) {
	switch a.Kind {
	case AlertSOS, AlertSOSResolved, AlertCheckIn:
		return "u.id <> $2 AND " + commonFamilySQL("$2", "u.id"), []any{a.Actor}
	case AlertArrival, AlertDeparture:
		return `u.id <> $2 AND EXISTS (SELECT 1 FROM family_members f WHERE f.family_id = $3 AND f.user_id = u.id)
			AND ` + visibleSQL("$2", "u.id") + `
			AND NOT EXISTS (SELECT 1 FROM place_mutes m WHERE m.place_id = $4 AND m.user_id = u.id)`,
			[]any{a.Actor, a.FamilyID, a.PlaceID}
	case AlertBatteryLow, AlertLastLocation:
		return "u.id <> $2 AND " + visibleSQL("$2", "u.id"), []any{a.Actor}
	case AlertRing, AlertLocate:
		return "u.id = $2", []any{a.Target}
	}
	panic("unknown alert kind " + a.Kind)
}

// readableSQL re-checks at read time (reader = me) whether a stored alert may still be shown:
// a revoked share or a left family also hides older alerts.
func readableSQL(me string) string {
	return `(a.kind IN ('ring', 'locate')
		OR (a.kind IN ('sos', 'sos_resolved', 'check_in') AND ` + commonFamilySQL("a.actor_id", me) + `)
		OR (a.kind IN ('arrival', 'departure', 'battery_low', 'last_location') AND ` + visibleSQL("a.actor_id", me) + `))`
}

// CreateAlert stores the alert and its recipients in one transaction and returns both.
func (s *Store) CreateAlert(ctx context.Context, a NewAlert) (Alert, []uuid.UUID, error) {
	var out Alert
	var recipients []uuid.UUID
	err := s.tx(ctx, func(tx pgx.Tx) error {
		var pos *string
		if a.Lat != nil && a.Lon != nil {
			wkt := fmt.Sprintf("SRID=4326;POINT(%.7f %.7f)", *a.Lon, *a.Lat)
			pos = &wkt
		}
		err := tx.QueryRow(ctx, `INSERT INTO alerts (kind, actor_id, actor_device_id, place_id, place_name, position,
				accuracy_m, battery_pct, message, created_at, expires_at)
			VALUES ($1, $2, $3, $4, $5, ST_GeogFromText($6::text), $7, $8, $9, $10, $11)
			RETURNING id, created_at`,
			a.Kind, a.Actor, a.ActorDevice, a.PlaceID, a.PlaceName, pos, a.AccuracyM, a.BatteryPct, a.Message,
			s.now(), a.ExpiresAt).Scan(&out.ID, &out.CreatedAt)
		if err != nil {
			return err
		}
		cond, args := audience(a)
		rows, err := tx.Query(ctx, `INSERT INTO alert_recipients (alert_id, user_id)
			SELECT $1, u.id FROM users u WHERE `+cond+` RETURNING user_id`, append([]any{out.ID}, args...)...)
		if err != nil {
			return err
		}
		if recipients, err = pgx.CollectRows(rows, pgx.RowTo[uuid.UUID]); err != nil {
			return err
		}
		return tx.QueryRow(ctx, "SELECT display_name FROM users WHERE id = $1", a.Actor).Scan(&out.ActorName)
	})
	if err != nil {
		return Alert{}, nil, err
	}
	out.Kind, out.ActorID, out.ActorDeviceID = a.Kind, a.Actor, a.ActorDevice
	out.PlaceID, out.PlaceName, out.Lat, out.Lon, out.AccuracyM = a.PlaceID, a.PlaceName, a.Lat, a.Lon, a.AccuracyM
	out.BatteryPct, out.Message, out.ExpiresAt = a.BatteryPct, a.Message, a.ExpiresAt
	return out, recipients, nil
}

const alertColumns = `a.id, a.kind, a.actor_id, u.display_name, a.actor_device_id, a.place_id, a.place_name,
	ST_Y(a.position::geometry), ST_X(a.position::geometry), a.accuracy_m::float8, a.battery_pct::int, a.message,
	a.created_at, a.expires_at, a.resolved_at`

func scanAlert(r pgx.CollectableRow) (Alert, error) {
	var a Alert
	err := r.Scan(&a.ID, &a.Kind, &a.ActorID, &a.ActorName, &a.ActorDeviceID, &a.PlaceID, &a.PlaceName,
		&a.Lat, &a.Lon, &a.AccuracyM, &a.BatteryPct, &a.Message, &a.CreatedAt, &a.ExpiresAt, &a.ResolvedAt, &a.Mine)
	return a, err
}

// ListAlerts returns alerts for me with id > after, oldest first: received alerts that are still
// readable, plus my own SOS / check-ins (so all my devices know whether my SOS is active).
func (s *Store) ListAlerts(ctx context.Context, me uuid.UUID, after int64, limit int) ([]Alert, error) {
	since := s.now().Add(-alertRetention)
	rows, err := s.pool.Query(ctx, `SELECT * FROM (
			SELECT `+alertColumns+`, false AS mine FROM alert_recipients r
			JOIN alerts a ON a.id = r.alert_id JOIN users u ON u.id = a.actor_id
			WHERE r.user_id = $1 AND a.id > $2 AND a.created_at > $3 AND `+readableSQL("$1")+`
			UNION ALL
			SELECT `+alertColumns+`, true AS mine FROM alerts a JOIN users u ON u.id = a.actor_id
			WHERE a.actor_id = $1 AND a.kind IN ('sos', 'sos_resolved', 'check_in') AND a.id > $2 AND a.created_at > $3
		) x ORDER BY 1 LIMIT $4`, me, after, since, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, scanAlert)
}

// SentAlerts returns every alert the actor created (data export).
func (s *Store) SentAlerts(ctx context.Context, actor uuid.UUID) ([]Alert, error) {
	rows, err := s.pool.Query(ctx, `SELECT `+alertColumns+`, true FROM alerts a JOIN users u ON u.id = a.actor_id
		WHERE a.actor_id = $1 ORDER BY a.id`, actor)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, scanAlert)
}

// ResolveSOS marks the actor's open SOS alerts as resolved and returns how many there were.
func (s *Store) ResolveSOS(ctx context.Context, actor uuid.UUID) (int64, error) {
	tag, err := s.pool.Exec(ctx, `UPDATE alerts SET resolved_at = $2
		WHERE actor_id = $1 AND kind = 'sos' AND resolved_at IS NULL`, actor, s.now())
	return tag.RowsAffected(), err
}

// RecentAlert reports whether the actor created an alert of kind since the given time (dedup of
// automatic alerts such as battery_low).
func (s *Store) RecentAlert(ctx context.Context, actor uuid.UUID, kind string, since time.Time) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM alerts WHERE actor_id = $1 AND kind = $2 AND created_at > $3)`,
		actor, kind, since).Scan(&ok)
	return ok, err
}

// RemoteRequestAllowed checks ring/locate requests from actor to target:
//
//	own account                → always (find my phone)
//	no common family           → ErrNotFound (nothing about foreign users is revealed)
//	ring                       → target allows ring (opt-in, default off)
//	locate                     → target allows locate requests AND actor may see target's location
func (s *Store) RemoteRequestAllowed(ctx context.Context, actor, target uuid.UUID, kind string) error {
	if actor == target {
		return nil
	}
	var common, allowRing, allowLocate, visible bool
	err := s.pool.QueryRow(ctx, `SELECT `+commonFamilySQL("$1", "$2")+`, us.allow_ring, us.allow_locate_request, `+visibleSQL("$2", "$1")+`
		FROM user_settings us WHERE us.user_id = $2`, actor, target).Scan(&common, &allowRing, &allowLocate, &visible)
	if err != nil {
		return notFound(err)
	}
	switch {
	case !common:
		return ErrNotFound
	case kind == AlertRing && !allowRing:
		return ErrForbidden
	case kind == AlertLocate && (!allowLocate || !visible):
		return ErrForbidden
	}
	return nil
}
