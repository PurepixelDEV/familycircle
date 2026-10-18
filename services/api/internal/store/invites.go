package store

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"familycircle/internal/auth"
)

type Invite struct {
	ID        uuid.UUID
	CodeHint  string
	MaxUses   int
	UseCount  int
	ExpiresAt time.Time
	CreatedAt time.Time
}

// CreateInvite creates a code for the family (admins only). The plaintext code is returned once.
func (s *Store) CreateInvite(ctx context.Context, familyID, actor uuid.UUID, ttl time.Duration, maxUses int) (Invite, string, error) {
	code := auth.NewCode(3)
	inv := Invite{CodeHint: code[len(code)-4:], MaxUses: maxUses}
	err := s.tx(ctx, func(tx pgx.Tx) error {
		if err := requireRole(ctx, tx, familyID, actor, true); err != nil {
			return err
		}
		now := s.now()
		return tx.QueryRow(ctx, `INSERT INTO invites (family_id, code_hash, code_hint, created_by, max_uses, expires_at, created_at)
			VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING id, expires_at, created_at`,
			familyID, auth.HashCode(code), inv.CodeHint, actor, maxUses, now.Add(ttl), now).
			Scan(&inv.ID, &inv.ExpiresAt, &inv.CreatedAt)
	})
	return inv, code, err
}

// ListInvites returns still usable invites (admins only).
func (s *Store) ListInvites(ctx context.Context, familyID, actor uuid.UUID) ([]Invite, error) {
	var out []Invite
	err := s.tx(ctx, func(tx pgx.Tx) error {
		if err := requireRole(ctx, tx, familyID, actor, true); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT id, code_hint, max_uses, use_count, expires_at, created_at FROM invites
			WHERE family_id = $1 AND revoked_at IS NULL AND expires_at > $2 AND use_count < max_uses ORDER BY created_at DESC`,
			familyID, s.now())
		if err != nil {
			return err
		}
		out, err = pgx.CollectRows(rows, func(r pgx.CollectableRow) (Invite, error) {
			var i Invite
			err := r.Scan(&i.ID, &i.CodeHint, &i.MaxUses, &i.UseCount, &i.ExpiresAt, &i.CreatedAt)
			return i, err
		})
		return err
	})
	return out, err
}

func (s *Store) RevokeInvite(ctx context.Context, familyID, actor, inviteID uuid.UUID) error {
	return s.tx(ctx, func(tx pgx.Tx) error {
		if err := requireRole(ctx, tx, familyID, actor, true); err != nil {
			return err
		}
		tag, err := tx.Exec(ctx, "UPDATE invites SET revoked_at = $3 WHERE id = $1 AND family_id = $2 AND revoked_at IS NULL",
			inviteID, familyID, s.now())
		if err == nil && tag.RowsAffected() == 0 {
			return ErrNotFound
		}
		return err
	})
}

// consumeInvite validates and uses one slot of an invite. The row lock makes concurrent
// redemptions of a single-use code safe: exactly one wins.
func consumeInvite(ctx context.Context, tx pgx.Tx, codeHash []byte, now time.Time) (uuid.UUID, error) {
	var id, familyID uuid.UUID
	err := tx.QueryRow(ctx, `SELECT id, family_id FROM invites
		WHERE code_hash = $1 AND revoked_at IS NULL AND expires_at > $2 AND use_count < max_uses FOR UPDATE`,
		codeHash, now).Scan(&id, &familyID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return uuid.Nil, ErrInviteInvalid
		}
		return uuid.Nil, err
	}
	_, err = tx.Exec(ctx, "UPDATE invites SET use_count = use_count + 1 WHERE id = $1", id)
	return familyID, err
}

// AcceptInvite lets an existing user join a family.
func (s *Store) AcceptInvite(ctx context.Context, userID uuid.UUID, codeHash []byte) (FamilySummary, error) {
	var fs FamilySummary
	err := s.tx(ctx, func(tx pgx.Tx) error {
		familyID, err := consumeInvite(ctx, tx, codeHash, s.now())
		if err != nil {
			return err
		}
		if err := lockFamilies(ctx, tx, familyID); err != nil {
			return err
		}
		if err := addMember(ctx, tx, familyID, userID, "member"); err != nil {
			return err
		}
		return tx.QueryRow(ctx, `SELECT f.id, f.name, 'member', (SELECT count(*) FROM family_members WHERE family_id = f.id)
			FROM families f WHERE f.id = $1`, familyID).Scan(&fs.ID, &fs.Name, &fs.Role, &fs.MemberCount)
	})
	return fs, err
}
