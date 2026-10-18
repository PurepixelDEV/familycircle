// Package store is the only place that talks SQL. Authorization-relevant checks that must be
// atomic with a write (e.g. "is the actor an admin of this family") happen inside the same
// transaction here, not in the HTTP layer, to avoid time-of-check/time-of-use gaps.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

var (
	ErrNotFound      = errors.New("not found")
	ErrForbidden     = errors.New("forbidden")
	ErrConflict      = errors.New("conflict")
	ErrEmailTaken    = errors.New("email already registered")
	ErrInviteInvalid = errors.New("invite code invalid, expired or used up")
	ErrAlreadyMember = errors.New("already a member of this family")
	ErrLastAdmin     = errors.New("the last admin cannot leave or be demoted while other members remain")
	ErrTokenReused   = errors.New("refresh token reuse detected; session revoked")
	ErrRegistration  = errors.New("registration is closed")
)

type Store struct {
	pool *pgxpool.Pool
	now  func() time.Time
}

func New(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool, now: func() time.Time { return time.Now().UTC() }}
}

// SetClock overrides the clock (tests).
func (s *Store) SetClock(now func() time.Time) { s.now = now }

func (s *Store) Ping(ctx context.Context) error { return s.pool.Ping(ctx) }

func (s *Store) tx(ctx context.Context, fn func(pgx.Tx) error) error {
	return pgx.BeginFunc(ctx, s.pool, fn)
}

func isUniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}

func notFound(err error) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	return err
}

// Member colours: distinguishable in light and dark mode, assigned in order per family.
var palette = []string{
	"#2f6fde", "#e0534a", "#1f9d6b", "#d68a00", "#8b5cf6",
	"#0e9aa7", "#d9468f", "#5b7c12", "#b4561f", "#4a5568",
}

func nextColor(ctx context.Context, tx pgx.Tx, familyID uuid.UUID) (string, error) {
	rows, err := tx.Query(ctx, "SELECT color FROM family_members WHERE family_id = $1", familyID)
	if err != nil {
		return "", err
	}
	used, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return "", err
	}
	count := map[string]int{}
	for _, c := range used {
		count[c]++
	}
	best, bestN := palette[0], int(^uint(0)>>1)
	for _, c := range palette {
		if count[c] < bestN {
			best, bestN = c, count[c]
		}
	}
	return best, nil
}

func addMember(ctx context.Context, tx pgx.Tx, familyID, userID uuid.UUID, role string) error {
	color, err := nextColor(ctx, tx, familyID)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `INSERT INTO family_members (family_id, user_id, role, color) VALUES ($1, $2, $3, $4)`,
		familyID, userID, role, color)
	if isUniqueViolation(err) {
		return ErrAlreadyMember
	}
	return err
}
