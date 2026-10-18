package store

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"familycircle/internal/auth"
)

const (
	PairingCodeTTL     = 10 * time.Minute
	MaxOpenPairingCode = 5
)

var ErrTooManyCodes = errors.New("too many open pairing codes")

// CreatePairingCode issues a short-lived, single-use code that lets a new phone sign in to the
// user's account by scanning a QR code. The plaintext code is returned once.
func (s *Store) CreatePairingCode(ctx context.Context, userID uuid.UUID) (string, time.Time, error) {
	code := auth.NewCode(3)
	now := s.now()
	expires := now.Add(PairingCodeTTL)
	err := s.tx(ctx, func(tx pgx.Tx) error {
		// The user row lock serialises concurrent requests for the open-code limit.
		if _, err := tx.Exec(ctx, "SELECT 1 FROM users WHERE id = $1 FOR UPDATE", userID); err != nil {
			return err
		}
		var open int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM pairing_codes WHERE user_id = $1 AND used_at IS NULL AND expires_at > $2`,
			userID, now).Scan(&open); err != nil {
			return err
		}
		if open >= MaxOpenPairingCode {
			return ErrTooManyCodes
		}
		_, err := tx.Exec(ctx, `INSERT INTO pairing_codes (user_id, code_hash, expires_at, created_at) VALUES ($1, $2, $3, $4)`,
			userID, pairingHash(code), expires, now)
		return err
	})
	return code, expires, err
}

// RedeemPairingCode consumes a valid code and creates a device session for its owner in the same
// transaction. Invalid, expired or already used codes return ErrNotFound.
func (s *Store) RedeemPairingCode(ctx context.Context, code, deviceName, appVersion, userAgent string) (Tokens, User, error) {
	var t Tokens
	var u User
	err := s.tx(ctx, func(tx pgx.Tx) error {
		var id, userID uuid.UUID
		err := tx.QueryRow(ctx, `SELECT id, user_id FROM pairing_codes
			WHERE code_hash = $1 AND used_at IS NULL AND expires_at > $2 FOR UPDATE`, pairingHash(code), s.now()).Scan(&id, &userID)
		if err != nil {
			return notFound(err)
		}
		if t, err = s.createDeviceSessionTx(ctx, tx, userID, deviceName, appVersion, userAgent); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, "UPDATE pairing_codes SET used_at = $2, device_id = $3 WHERE id = $1", id, s.now(), t.Session.DeviceID); err != nil {
			return err
		}
		u, err = scanUser(tx.QueryRow(ctx, "SELECT "+userColumns+" FROM users WHERE id = $1", userID))
		return err
	})
	return t, u, err
}

// pairingHash separates pairing codes from invite codes (different hash domain).
func pairingHash(code string) []byte {
	return auth.HashToken("pairing:" + auth.NormalizeCode(code))
}
