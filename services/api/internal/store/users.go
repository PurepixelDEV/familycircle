package store

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

type User struct {
	ID              uuid.UUID
	Email           string
	DisplayName     string
	PasswordHash    string
	Locale          string
	IsInstanceAdmin bool
	AvatarUpdatedAt *time.Time
	CreatedAt       time.Time
}

// AvatarVersion is a cache-busting number that changes with the avatar (nil = no avatar).
func AvatarVersion(t *time.Time) *int64 {
	if t == nil {
		return nil
	}
	v := t.UnixMilli()
	return &v
}

const userColumns = `id, email, display_name, password_hash, locale, is_instance_admin, avatar_updated_at, created_at`

func scanUser(row pgx.Row) (User, error) {
	var u User
	err := row.Scan(&u.ID, &u.Email, &u.DisplayName, &u.PasswordHash, &u.Locale, &u.IsInstanceAdmin, &u.AvatarUpdatedAt, &u.CreatedAt)
	return u, notFound(err)
}

func (s *Store) CountUsers(ctx context.Context) (int, error) {
	var n int
	err := s.pool.QueryRow(ctx, "SELECT count(*) FROM users").Scan(&n)
	return n, err
}

func (s *Store) RegistrationMode(ctx context.Context) (string, error) {
	var mode string
	err := s.pool.QueryRow(ctx, "SELECT registration_mode FROM instance_settings").Scan(&mode)
	return mode, err
}

type NewUser struct {
	Email, DisplayName, PasswordHash, Locale string
}

func insertUser(ctx context.Context, tx pgx.Tx, nu NewUser, admin bool) (User, error) {
	u, err := scanUser(tx.QueryRow(ctx, `
		INSERT INTO users (email, display_name, password_hash, locale, is_instance_admin)
		VALUES ($1, $2, $3, $4, $5) RETURNING `+userColumns,
		nu.Email, nu.DisplayName, nu.PasswordHash, nu.Locale, admin))
	if isUniqueViolation(err) {
		return User{}, ErrEmailTaken
	}
	if err != nil {
		return User{}, err
	}
	_, err = tx.Exec(ctx, "INSERT INTO user_settings (user_id) VALUES ($1)", u.ID)
	return u, err
}

// CreateInitialAdmin creates the first account (instance admin) with its family. It fails with
// ErrConflict when any user already exists; the instance_settings row lock serialises racing calls.
func (s *Store) CreateInitialAdmin(ctx context.Context, nu NewUser, familyName string) (User, error) {
	var u User
	err := s.tx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "SELECT 1 FROM instance_settings FOR UPDATE"); err != nil {
			return err
		}
		var n int
		if err := tx.QueryRow(ctx, "SELECT count(*) FROM users").Scan(&n); err != nil {
			return err
		}
		if n > 0 {
			return ErrConflict
		}
		var err error
		if u, err = insertUser(ctx, tx, nu, true); err != nil {
			return err
		}
		var familyID uuid.UUID
		if err := tx.QueryRow(ctx, "INSERT INTO families (name, created_by) VALUES ($1, $2) RETURNING id",
			familyName, u.ID).Scan(&familyID); err != nil {
			return err
		}
		return addMember(ctx, tx, familyID, u.ID, "admin")
	})
	return u, err
}

// RegisterWithInvite creates an account and joins the invite's family atomically. The invite
// is consumed in the same transaction, so a single-use code cannot be redeemed twice.
func (s *Store) RegisterWithInvite(ctx context.Context, codeHash []byte, nu NewUser) (User, uuid.UUID, error) {
	var u User
	var familyID uuid.UUID
	err := s.tx(ctx, func(tx pgx.Tx) error {
		var mode string
		if err := tx.QueryRow(ctx, "SELECT registration_mode FROM instance_settings").Scan(&mode); err != nil {
			return err
		}
		if mode == "closed" {
			return ErrRegistration
		}
		var err error
		if familyID, err = consumeInvite(ctx, tx, codeHash, s.now()); err != nil {
			return err
		}
		if u, err = insertUser(ctx, tx, nu, false); err != nil {
			return err
		}
		return addMember(ctx, tx, familyID, u.ID, "member")
	})
	return u, familyID, err
}

func (s *Store) UserByEmail(ctx context.Context, email string) (User, error) {
	return scanUser(s.pool.QueryRow(ctx, "SELECT "+userColumns+" FROM users WHERE email = $1", email))
}

func (s *Store) UserByID(ctx context.Context, id uuid.UUID) (User, error) {
	return scanUser(s.pool.QueryRow(ctx, "SELECT "+userColumns+" FROM users WHERE id = $1", id))
}

func (s *Store) UpdateProfile(ctx context.Context, id uuid.UUID, displayName, locale *string) (User, error) {
	return scanUser(s.pool.QueryRow(ctx, `
		UPDATE users SET display_name = COALESCE($2, display_name), locale = COALESCE($3, locale), updated_at = now()
		WHERE id = $1 RETURNING `+userColumns, id, displayName, locale))
}

func (s *Store) SetPasswordHash(ctx context.Context, id uuid.UUID, hash string) error {
	_, err := s.pool.Exec(ctx, "UPDATE users SET password_hash = $2, updated_at = now() WHERE id = $1", id, hash)
	return err
}

// DeletedUser describes side effects of an account deletion for live notifications.
type DeletedUser struct {
	FormerViewers []uuid.UUID
	Families      []uuid.UUID
}

// DeleteUser removes the account and, via ON DELETE CASCADE, all of its data (sessions,
// devices, memberships, shares, locations). Families in which the user was the only admin get
// the longest-standing remaining member promoted; families left empty are deleted.
func (s *Store) DeleteUser(ctx context.Context, id uuid.UUID) (DeletedUser, error) {
	var out DeletedUser
	err := s.tx(ctx, func(tx pgx.Tx) error {
		viewers, err := tx.Query(ctx, `SELECT u.id FROM users u WHERE u.id <> $1 AND `+visibleSQL("$1", "u.id"), id)
		if err != nil {
			return err
		}
		if out.FormerViewers, err = pgx.CollectRows(viewers, pgx.RowTo[uuid.UUID]); err != nil {
			return err
		}
		fams, err := tx.Query(ctx, "SELECT family_id FROM family_members WHERE user_id = $1", id)
		if err != nil {
			return err
		}
		if out.Families, err = pgx.CollectRows(fams, pgx.RowTo[uuid.UUID]); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, "DELETE FROM family_members WHERE user_id = $1", id); err != nil {
			return err
		}
		for _, f := range out.Families {
			if err := afterMemberLeft(ctx, tx, f); err != nil {
				return err
			}
		}
		tag, err := tx.Exec(ctx, "DELETE FROM users WHERE id = $1", id)
		if err == nil && tag.RowsAffected() == 0 {
			return ErrNotFound
		}
		return err
	})
	return out, err
}

// afterMemberLeft keeps a family consistent: delete it when empty, promote someone when no admin remains.
func afterMemberLeft(ctx context.Context, tx pgx.Tx, familyID uuid.UUID) error {
	var members, admins int
	if err := tx.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE role = 'admin') FROM family_members WHERE family_id = $1`,
		familyID).Scan(&members, &admins); err != nil {
		return err
	}
	switch {
	case members == 0:
		_, err := tx.Exec(ctx, "DELETE FROM families WHERE id = $1", familyID)
		return err
	case admins == 0:
		_, err := tx.Exec(ctx, `UPDATE family_members SET role = 'admin'
			WHERE family_id = $1 AND user_id = (SELECT user_id FROM family_members WHERE family_id = $1 ORDER BY joined_at LIMIT 1)`,
			familyID)
		return err
	}
	return nil
}
