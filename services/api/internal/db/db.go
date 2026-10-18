// Package db connects to PostgreSQL and applies schema migrations.
package db

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"regexp"
	"sort"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Connect opens a pool and waits (up to the context deadline) until the database accepts
// connections. In Docker Compose the database may still be starting when we boot.
func Connect(ctx context.Context, url string, log *slog.Logger) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		return nil, fmt.Errorf("invalid FC_DATABASE_URL: %w", err)
	}
	if cfg.MaxConns < 4 {
		cfg.MaxConns = 4
	}
	cfg.ConnConfig.RuntimeParams["application_name"] = "familycircle"
	cfg.ConnConfig.RuntimeParams["timezone"] = "UTC"
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, err
	}
	delay := 500 * time.Millisecond
	for {
		pingCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		err = pool.Ping(pingCtx)
		cancel()
		if err == nil {
			return pool, nil
		}
		log.Warn("database not reachable yet, retrying", "err", err, "retry_in", delay)
		select {
		case <-ctx.Done():
			pool.Close()
			return nil, fmt.Errorf("database not reachable: %w", err)
		case <-time.After(delay):
		}
		delay = min(delay*2, 5*time.Second)
	}
}

var migrationName = regexp.MustCompile(`^(\d{4})_[a-z0-9_]+\.sql$`)

type migration struct {
	version  int
	name     string
	sql      string
	checksum string
}

// Arbitrary constant identifying FamilyCircle's migration lock.
const migrationLockID = 7_314_202_610_180

var (
	ErrChecksumMismatch = errors.New("an already applied migration was modified")
	ErrDatabaseNewer    = errors.New("database schema is newer than this binary (downgrade is not supported)")
)

// Migrate applies all pending migrations from fsys. Each migration runs in its own transaction;
// a cluster-wide advisory lock prevents two instances from migrating concurrently. Applied
// migrations are verified by checksum, and a schema newer than the binary is refused.
func Migrate(ctx context.Context, pool *pgxpool.Pool, fsys fs.FS, log *slog.Logger) error {
	migs, err := load(fsys)
	if err != nil {
		return err
	}
	conn, err := pool.Acquire(ctx)
	if err != nil {
		return err
	}
	defer conn.Release()

	if _, err := conn.Exec(ctx, "SELECT pg_advisory_lock($1)", int64(migrationLockID)); err != nil {
		return fmt.Errorf("acquire migration lock: %w", err)
	}
	defer func() {
		// Use a fresh context so the lock is released even if ctx was cancelled.
		_, _ = conn.Exec(context.Background(), "SELECT pg_advisory_unlock($1)", int64(migrationLockID))
	}()

	if _, err := conn.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version    integer PRIMARY KEY,
		name       text NOT NULL,
		checksum   text NOT NULL,
		applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	applied := map[int]string{}
	rows, err := conn.Query(ctx, "SELECT version, checksum FROM schema_migrations")
	if err != nil {
		return err
	}
	for rows.Next() {
		var v int
		var sum string
		if err := rows.Scan(&v, &sum); err != nil {
			rows.Close()
			return err
		}
		applied[v] = sum
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	known := map[int]bool{}
	for _, m := range migs {
		known[m.version] = true
	}
	for v := range applied {
		if !known[v] {
			return fmt.Errorf("%w: database has migration %04d", ErrDatabaseNewer, v)
		}
	}

	for _, m := range migs {
		if sum, ok := applied[m.version]; ok {
			if sum != m.checksum {
				return fmt.Errorf("%w: %s", ErrChecksumMismatch, m.name)
			}
			continue
		}
		start := time.Now()
		err := pgx.BeginFunc(ctx, conn, func(tx pgx.Tx) error {
			if _, err := tx.Exec(ctx, m.sql); err != nil {
				return err
			}
			_, err := tx.Exec(ctx, "INSERT INTO schema_migrations (version, name, checksum) VALUES ($1, $2, $3)",
				m.version, m.name, m.checksum)
			return err
		})
		if err != nil {
			return fmt.Errorf("migration %s failed (rolled back, database unchanged): %w", m.name, err)
		}
		log.Info("applied migration", "migration", m.name, "duration_ms", time.Since(start).Milliseconds())
	}
	return nil
}

func load(fsys fs.FS) ([]migration, error) {
	entries, err := fs.ReadDir(fsys, ".")
	if err != nil {
		return nil, err
	}
	var migs []migration
	seen := map[int]string{}
	for _, e := range entries {
		if e.IsDir() || e.Name() == "migrations.go" {
			continue
		}
		match := migrationName.FindStringSubmatch(e.Name())
		if match == nil {
			if len(e.Name()) > 4 && e.Name()[len(e.Name())-4:] == ".sql" {
				return nil, fmt.Errorf("invalid migration file name %q (want NNNN_name.sql)", e.Name())
			}
			continue
		}
		v, _ := strconv.Atoi(match[1])
		if prev, dup := seen[v]; dup {
			return nil, fmt.Errorf("duplicate migration version %04d: %s and %s", v, prev, e.Name())
		}
		seen[v] = e.Name()
		body, err := fs.ReadFile(fsys, e.Name())
		if err != nil {
			return nil, err
		}
		sum := sha256.Sum256(body)
		migs = append(migs, migration{version: v, name: e.Name(), sql: string(body), checksum: hex.EncodeToString(sum[:])})
	}
	sort.Slice(migs, func(i, j int) bool { return migs[i].version < migs[j].version })
	return migs, nil
}
