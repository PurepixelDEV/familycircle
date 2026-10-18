package store

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

type FamilySummary struct {
	ID          uuid.UUID
	Name        string
	Role        string
	MemberCount int
}

type Member struct {
	UserID          uuid.UUID
	DisplayName     string
	Role            string
	Color           string
	AvatarUpdatedAt *time.Time
	JoinedAt        time.Time
	SharesWithMe    bool
	IShareWith      bool
}

type Family struct {
	ID      uuid.UUID
	Name    string
	Role    string // role of the requesting user
	Members []Member
}

func (s *Store) ListFamilies(ctx context.Context, userID uuid.UUID) ([]FamilySummary, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT f.id, f.name, m.role, (SELECT count(*) FROM family_members x WHERE x.family_id = f.id)
		FROM family_members m JOIN families f ON f.id = m.family_id
		WHERE m.user_id = $1 ORDER BY m.joined_at`, userID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (FamilySummary, error) {
		var f FamilySummary
		err := r.Scan(&f.ID, &f.Name, &f.Role, &f.MemberCount)
		return f, err
	})
}

func (s *Store) CreateFamily(ctx context.Context, userID uuid.UUID, name string) (uuid.UUID, error) {
	var id uuid.UUID
	err := s.tx(ctx, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, "INSERT INTO families (name, created_by) VALUES ($1, $2) RETURNING id", name, userID).Scan(&id); err != nil {
			return err
		}
		return addMember(ctx, tx, id, userID, "admin")
	})
	return id, err
}

// FamilyForMember returns the family including members, or ErrNotFound if viewer is not a member
// (no distinction between "does not exist" and "not yours").
func (s *Store) FamilyForMember(ctx context.Context, familyID, viewer uuid.UUID) (Family, error) {
	var f Family
	err := s.pool.QueryRow(ctx, `SELECT f.id, f.name, m.role FROM families f
		JOIN family_members m ON m.family_id = f.id AND m.user_id = $2 WHERE f.id = $1`, familyID, viewer).
		Scan(&f.ID, &f.Name, &f.Role)
	if err != nil {
		return Family{}, notFound(err)
	}
	rows, err := s.pool.Query(ctx, `
		SELECT u.id, u.display_name, m.role, m.color, u.avatar_updated_at, m.joined_at,
		       (u.id = $2 OR `+visibleSQL("u.id", "$2")+`),
		       EXISTS (SELECT 1 FROM location_shares ls WHERE ls.owner_id = $2 AND ls.viewer_id = u.id)
		FROM family_members m JOIN users u ON u.id = m.user_id
		WHERE m.family_id = $1 ORDER BY (u.id = $2) DESC, m.joined_at`, familyID, viewer)
	if err != nil {
		return Family{}, err
	}
	f.Members, err = pgx.CollectRows(rows, func(r pgx.CollectableRow) (Member, error) {
		var m Member
		err := r.Scan(&m.UserID, &m.DisplayName, &m.Role, &m.Color, &m.AvatarUpdatedAt, &m.JoinedAt, &m.SharesWithMe, &m.IShareWith)
		return m, err
	})
	return f, err
}

func (s *Store) FamilyMemberIDs(ctx context.Context, familyID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := s.pool.Query(ctx, "SELECT user_id FROM family_members WHERE family_id = $1", familyID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
}

// requireRole loads the actor's role inside tx (locking the membership row) and checks it.
// Non-members get ErrNotFound, members without the role ErrForbidden.
func requireRole(ctx context.Context, tx pgx.Tx, familyID, actor uuid.UUID, admin bool) error {
	var role string
	err := tx.QueryRow(ctx, "SELECT role FROM family_members WHERE family_id = $1 AND user_id = $2 FOR UPDATE",
		familyID, actor).Scan(&role)
	if err != nil {
		return notFound(err)
	}
	if admin && role != "admin" {
		return ErrForbidden
	}
	return nil
}

func (s *Store) RenameFamily(ctx context.Context, familyID, actor uuid.UUID, name string) error {
	return s.tx(ctx, func(tx pgx.Tx) error {
		if err := requireRole(ctx, tx, familyID, actor, true); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, "UPDATE families SET name = $2 WHERE id = $1", familyID, name)
		return err
	})
}

// LeaveFamily removes the user from the family. Returns share relations that ended as a result.
func (s *Store) LeaveFamily(ctx context.Context, familyID, userID uuid.UUID) ([]SharePair, error) {
	var ended []SharePair
	err := s.tx(ctx, func(tx pgx.Tx) error {
		var role string
		if err := tx.QueryRow(ctx, "SELECT role FROM family_members WHERE family_id = $1 AND user_id = $2",
			familyID, userID).Scan(&role); err != nil {
			return notFound(err)
		}
		// Lock all memberships of the family so concurrent role changes cannot remove the last admin.
		var members, admins int
		if err := tx.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE role = 'admin') FROM
			(SELECT role FROM family_members WHERE family_id = $1 FOR UPDATE) m`, familyID).Scan(&members, &admins); err != nil {
			return err
		}
		if role == "admin" && admins == 1 && members > 1 {
			return ErrLastAdmin
		}
		if _, err := tx.Exec(ctx, "DELETE FROM family_members WHERE family_id = $1 AND user_id = $2", familyID, userID); err != nil {
			return err
		}
		if err := afterMemberLeft(ctx, tx, familyID); err != nil {
			return err
		}
		var err error
		ended, err = pruneOrphanedShares(ctx, tx, userID)
		return err
	})
	return ended, err
}

// RemoveMember lets an admin remove another member. Removing grants no access to anything.
func (s *Store) RemoveMember(ctx context.Context, familyID, actor, target uuid.UUID) ([]SharePair, error) {
	if actor == target {
		return nil, ErrConflict // use LeaveFamily
	}
	var ended []SharePair
	err := s.tx(ctx, func(tx pgx.Tx) error {
		if err := requireRole(ctx, tx, familyID, actor, true); err != nil {
			return err
		}
		tag, err := tx.Exec(ctx, "DELETE FROM family_members WHERE family_id = $1 AND user_id = $2", familyID, target)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return ErrNotFound
		}
		ended, err = pruneOrphanedShares(ctx, tx, target)
		return err
	})
	return ended, err
}

func (s *Store) SetMemberRole(ctx context.Context, familyID, actor, target uuid.UUID, role string) error {
	return s.tx(ctx, func(tx pgx.Tx) error {
		if err := requireRole(ctx, tx, familyID, actor, true); err != nil {
			return err
		}
		var admins int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM (SELECT 1 FROM family_members WHERE family_id = $1 AND role = 'admin' FOR UPDATE) a`,
			familyID).Scan(&admins); err != nil {
			return err
		}
		var current string
		if err := tx.QueryRow(ctx, "SELECT role FROM family_members WHERE family_id = $1 AND user_id = $2", familyID, target).
			Scan(&current); err != nil {
			return notFound(err)
		}
		if current == "admin" && role != "admin" && admins == 1 {
			return ErrLastAdmin
		}
		_, err := tx.Exec(ctx, "UPDATE family_members SET role = $3 WHERE family_id = $1 AND user_id = $2", familyID, target, role)
		return err
	})
}
