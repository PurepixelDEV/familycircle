package store

import (
	"bytes"
	"context"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"familycircle/internal/auth"
)

const (
	WebSessionTTL      = 30 * 24 * time.Hour
	AccessTokenTTL     = time.Hour
	RefreshTokenTTL    = 90 * 24 * time.Hour
	RefreshRetryWindow = 2 * time.Minute // a lost refresh response may be retried with the previous token
)

type Session struct {
	ID              uuid.UUID
	UserID          uuid.UUID
	DeviceID        *uuid.UUID
	DeviceName      *string
	Kind            string
	AccessExpiresAt time.Time
	UserAgent       *string
	CreatedAt       time.Time
	LastUsedAt      time.Time
}

// Tokens are returned exactly once to the client; only hashes are stored.
type Tokens struct {
	Access, Refresh string
	AccessExpires   time.Time
	Session         Session
}

func truncateUA(ua string) *string {
	if ua == "" {
		return nil
	}
	if len(ua) > 256 {
		ua = ua[:256]
	}
	return &ua
}

func (s *Store) CreateWebSession(ctx context.Context, userID uuid.UUID, userAgent string) (string, Session, error) {
	token, hash := auth.NewToken(auth.PrefixWebSession)
	now := s.now()
	var sess Session
	err := s.pool.QueryRow(ctx, `
		INSERT INTO sessions (user_id, kind, access_hash, access_expires_at, user_agent, created_at, last_used_at)
		VALUES ($1, 'web', $2, $3, $4, $5, $5)
		RETURNING id, user_id, kind, access_expires_at, user_agent, created_at, last_used_at`,
		userID, hash, now.Add(WebSessionTTL), truncateUA(userAgent), now).
		Scan(&sess.ID, &sess.UserID, &sess.Kind, &sess.AccessExpiresAt, &sess.UserAgent, &sess.CreatedAt, &sess.LastUsedAt)
	return token, sess, err
}

// CreateDeviceSession registers a new device and issues access + refresh tokens for it.
func (s *Store) CreateDeviceSession(ctx context.Context, userID uuid.UUID, deviceName, appVersion, userAgent string) (Tokens, error) {
	var t Tokens
	err := s.tx(ctx, func(tx pgx.Tx) error {
		var err error
		t, err = s.createDeviceSessionTx(ctx, tx, userID, deviceName, appVersion, userAgent)
		return err
	})
	return t, err
}

// createDeviceSessionTx registers a device and issues its tokens inside an existing transaction.
func (s *Store) createDeviceSessionTx(ctx context.Context, tx pgx.Tx, userID uuid.UUID, deviceName, appVersion, userAgent string) (Tokens, error) {
	access, accessHash := auth.NewToken(auth.PrefixAccess)
	refresh, refreshHash := auth.NewToken(auth.PrefixRefresh)
	now := s.now()
	var appV *string
	if appVersion != "" {
		appV = &appVersion
	}
	t := Tokens{Access: access, Refresh: refresh, AccessExpires: now.Add(AccessTokenTTL)}
	var deviceID uuid.UUID
	if err := tx.QueryRow(ctx, `INSERT INTO devices (user_id, name, platform, app_version, last_seen_at)
		VALUES ($1, $2, 'android', $3, $4) RETURNING id`, userID, deviceName, appV, now).Scan(&deviceID); err != nil {
		return Tokens{}, err
	}
	t.Session = Session{UserID: userID, DeviceID: &deviceID, DeviceName: &deviceName, Kind: "device",
		AccessExpiresAt: t.AccessExpires, UserAgent: truncateUA(userAgent), CreatedAt: now, LastUsedAt: now}
	err := tx.QueryRow(ctx, `
		INSERT INTO sessions (user_id, device_id, kind, access_hash, access_expires_at, refresh_hash, refresh_expires_at, user_agent, created_at, last_used_at)
		VALUES ($1, $2, 'device', $3, $4, $5, $6, $7, $8, $8) RETURNING id`,
		userID, deviceID, accessHash, t.AccessExpires, refreshHash, now.Add(RefreshTokenTTL), t.Session.UserAgent, now).
		Scan(&t.Session.ID)
	return t, err
}

// Authenticate resolves an access token (cookie or bearer) to its user and session.
func (s *Store) Authenticate(ctx context.Context, token string) (User, Session, error) {
	var u User
	var sess Session
	err := s.pool.QueryRow(ctx, `
		SELECT `+prefixed("u.", userColumns)+`,
		       s.id, s.user_id, s.device_id, d.name, s.kind, s.access_expires_at, s.user_agent, s.created_at, s.last_used_at
		FROM sessions s
		JOIN users u ON u.id = s.user_id
		LEFT JOIN devices d ON d.id = s.device_id
		WHERE s.access_hash = $1 AND s.revoked_at IS NULL AND s.access_expires_at > $2
		  AND (d.id IS NULL OR d.revoked_at IS NULL)`, auth.HashToken(token), s.now()).
		Scan(&u.ID, &u.Email, &u.DisplayName, &u.PasswordHash, &u.Locale, &u.IsInstanceAdmin, &u.AvatarUpdatedAt, &u.CreatedAt,
			&sess.ID, &sess.UserID, &sess.DeviceID, &sess.DeviceName, &sess.Kind, &sess.AccessExpiresAt, &sess.UserAgent, &sess.CreatedAt, &sess.LastUsedAt)
	return u, sess, notFound(err)
}

// prefixed qualifies a column list with a table alias: "a, b" → "u.a, u.b".
func prefixed(p, cols string) string {
	parts := strings.Split(cols, ", ")
	for i := range parts {
		parts[i] = p + parts[i]
	}
	return strings.Join(parts, ", ")
}

// Touch records activity. Web sessions slide: their expiry is pushed out to now+TTL.
// Returns the (possibly new) expiry.
func (s *Store) Touch(ctx context.Context, sess Session) (time.Time, error) {
	now := s.now()
	expires := sess.AccessExpiresAt
	if sess.Kind == "web" {
		expires = now.Add(WebSessionTTL)
	}
	_, err := s.pool.Exec(ctx, "UPDATE sessions SET last_used_at = $2, access_expires_at = $3 WHERE id = $1 AND revoked_at IS NULL",
		sess.ID, now, expires)
	if err == nil && sess.DeviceID != nil {
		_, err = s.pool.Exec(ctx, "UPDATE devices SET last_seen_at = $2 WHERE id = $1", *sess.DeviceID, now)
	}
	return expires, err
}

// Refresh rotates a device session's tokens. Presenting an already rotated refresh token outside
// the short retry window is treated as theft: the whole session is revoked.
func (s *Store) Refresh(ctx context.Context, refreshToken string) (Tokens, User, error) {
	presented := auth.HashToken(refreshToken)
	now := s.now()
	var out Tokens
	var user User
	var reused bool
	err := s.tx(ctx, func(tx pgx.Tx) error {
		var sess Session
		var refreshHash, prevHash []byte
		var refreshExpires, rotatedAt *time.Time
		var revokedAt, deviceRevoked *time.Time
		err := tx.QueryRow(ctx, `
			SELECT s.id, s.user_id, s.device_id, d.name, s.created_at, s.user_agent,
			       s.refresh_hash, s.prev_refresh_hash, s.refresh_expires_at, s.refresh_rotated_at, s.revoked_at, d.revoked_at
			FROM sessions s JOIN devices d ON d.id = s.device_id
			WHERE s.kind = 'device' AND (s.refresh_hash = $1 OR s.prev_refresh_hash = $1)
			FOR UPDATE OF s`, presented).
			Scan(&sess.ID, &sess.UserID, &sess.DeviceID, &sess.DeviceName, &sess.CreatedAt, &sess.UserAgent,
				&refreshHash, &prevHash, &refreshExpires, &rotatedAt, &revokedAt, &deviceRevoked)
		if err != nil {
			return notFound(err)
		}
		if revokedAt != nil || deviceRevoked != nil || refreshExpires == nil || !refreshExpires.After(now) {
			return ErrNotFound
		}
		if bytes.Equal(presented, prevHash) && !bytes.Equal(presented, refreshHash) {
			if rotatedAt == nil || now.Sub(*rotatedAt) > RefreshRetryWindow {
				reused = true
				_, err := tx.Exec(ctx, "UPDATE sessions SET revoked_at = $2 WHERE id = $1", sess.ID, now)
				return err
			}
			// Retry within the window: rotate again but keep prev and rotated_at, so the window never extends.
		} else {
			prevHash, rotatedAt = presented, &now
		}
		access, accessHash := auth.NewToken(auth.PrefixAccess)
		refresh, newRefreshHash := auth.NewToken(auth.PrefixRefresh)
		out = Tokens{Access: access, Refresh: refresh, AccessExpires: now.Add(AccessTokenTTL)}
		if _, err := tx.Exec(ctx, `UPDATE sessions SET access_hash = $2, access_expires_at = $3, refresh_hash = $4,
			prev_refresh_hash = $5, refresh_rotated_at = $6, refresh_expires_at = $7, last_used_at = $8 WHERE id = $1`,
			sess.ID, accessHash, out.AccessExpires, newRefreshHash, prevHash, rotatedAt, now.Add(RefreshTokenTTL), now); err != nil {
			return err
		}
		sess.Kind, sess.AccessExpiresAt, sess.LastUsedAt = "device", out.AccessExpires, now
		out.Session = sess
		user, err = scanUser(tx.QueryRow(ctx, "SELECT "+userColumns+" FROM users WHERE id = $1", sess.UserID))
		return err
	})
	if err == nil && reused {
		return Tokens{}, User{}, ErrTokenReused
	}
	return out, user, err
}

// RevokeSession revokes one of the user's sessions. Returns ErrNotFound for foreign IDs.
func (s *Store) RevokeSession(ctx context.Context, userID, sessionID uuid.UUID) error {
	tag, err := s.pool.Exec(ctx, "UPDATE sessions SET revoked_at = $3 WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL",
		sessionID, userID, s.now())
	if err == nil && tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}

// RevokeOtherSessions revokes all sessions of the user except keep; returns the revoked IDs.
func (s *Store) RevokeOtherSessions(ctx context.Context, userID, keep uuid.UUID) ([]uuid.UUID, error) {
	rows, err := s.pool.Query(ctx, `UPDATE sessions SET revoked_at = $3 WHERE user_id = $1 AND id <> $2 AND revoked_at IS NULL RETURNING id`,
		userID, keep, s.now())
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
}

// SessionActive is used by long-lived connections (WebSocket) to re-check their session.
func (s *Store) SessionActive(ctx context.Context, id uuid.UUID) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM sessions s LEFT JOIN devices d ON d.id = s.device_id
		WHERE s.id = $1 AND s.revoked_at IS NULL AND s.access_expires_at > $2 AND (d.id IS NULL OR d.revoked_at IS NULL))`,
		id, s.now()).Scan(&ok)
	return ok, err
}

func (s *Store) ListSessions(ctx context.Context, userID uuid.UUID) ([]Session, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT s.id, s.user_id, s.device_id, d.name, s.kind, s.access_expires_at, s.user_agent, s.created_at, s.last_used_at
		FROM sessions s LEFT JOIN devices d ON d.id = s.device_id
		WHERE s.user_id = $1 AND s.revoked_at IS NULL
		  AND (s.access_expires_at > $2 OR (s.kind = 'device' AND s.refresh_expires_at > $2))
		ORDER BY s.last_used_at DESC`, userID, s.now())
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Session, error) {
		var x Session
		err := r.Scan(&x.ID, &x.UserID, &x.DeviceID, &x.DeviceName, &x.Kind, &x.AccessExpiresAt, &x.UserAgent, &x.CreatedAt, &x.LastUsedAt)
		return x, err
	})
}

type Device struct {
	ID         uuid.UUID
	Name       string
	Platform   string
	AppVersion *string
	CreatedAt  time.Time
	LastSeenAt *time.Time
}

func (s *Store) ListDevices(ctx context.Context, userID uuid.UUID) ([]Device, error) {
	rows, err := s.pool.Query(ctx, `SELECT id, name, platform, app_version, created_at, last_seen_at
		FROM devices WHERE user_id = $1 AND revoked_at IS NULL ORDER BY created_at`, userID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Device, error) {
		var d Device
		err := r.Scan(&d.ID, &d.Name, &d.Platform, &d.AppVersion, &d.CreatedAt, &d.LastSeenAt)
		return d, err
	})
}

// DeleteDevice removes a device of the user (e.g. a stolen phone). Its sessions are deleted with
// it (cascade); the IDs are returned so live connections can be closed.
func (s *Store) DeleteDevice(ctx context.Context, userID, deviceID uuid.UUID) ([]uuid.UUID, error) {
	var sessions []uuid.UUID
	err := s.tx(ctx, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, "SELECT id FROM sessions WHERE device_id = $1 AND user_id = $2", deviceID, userID)
		if err != nil {
			return err
		}
		if sessions, err = pgx.CollectRows(rows, pgx.RowTo[uuid.UUID]); err != nil {
			return err
		}
		tag, err := tx.Exec(ctx, "DELETE FROM devices WHERE id = $1 AND user_id = $2", deviceID, userID)
		if err == nil && tag.RowsAffected() == 0 {
			return ErrNotFound
		}
		return err
	})
	return sessions, err
}
