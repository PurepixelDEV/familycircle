// Package testdb provides throw-away PostgreSQL databases for integration tests.
//
// Set FC_TEST_DATABASE_URL to a superuser DSN (e.g. the dev compose database). Tests that need
// a database are skipped when it is unset, unless FC_REQUIRE_DB=1 (used in CI) makes them fail.
package testdb

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"io"
	"io/fs"
	"log/slog"
	"net/url"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"familycircle/internal/db"
	"familycircle/migrations"
)

func adminURL(t testing.TB) string {
	u := os.Getenv("FC_TEST_DATABASE_URL")
	if u == "" {
		if os.Getenv("FC_REQUIRE_DB") == "1" {
			t.Fatal("FC_TEST_DATABASE_URL is required (FC_REQUIRE_DB=1)")
		}
		t.Skip("FC_TEST_DATABASE_URL not set; skipping database test")
	}
	return u
}

func withDatabase(raw, name string) string {
	u, _ := url.Parse(raw)
	u.Path = "/" + name
	return u.String()
}

func randomName(prefix string) string {
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	return prefix + hex.EncodeToString(b)
}

func exec(t testing.TB, admin, sql string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	conn, err := pgx.Connect(ctx, admin)
	if err != nil {
		t.Fatalf("connect admin db: %v", err)
	}
	defer conn.Close(ctx)
	if _, err := conn.Exec(ctx, sql); err != nil {
		t.Fatalf("%s: %v", sql, err)
	}
}

// Empty creates an empty database (no migrations) and returns its URL.
func Empty(t testing.TB) string {
	admin := adminURL(t)
	name := randomName("fc_test_")
	exec(t, admin, "CREATE DATABASE "+name)
	t.Cleanup(func() { exec(t, admin, "DROP DATABASE IF EXISTS "+name+" WITH (FORCE)") })
	return withDatabase(admin, name)
}

var (
	templateOnce sync.Once
	templateName string
	templateErr  error
)

// Migrated returns a pool to a fresh database with all migrations applied. A migrated template
// database is created once per test process and cloned for each test (fast).
func Migrated(t testing.TB) *pgxpool.Pool {
	admin := adminURL(t)
	templateOnce.Do(func() {
		templateName = randomName("fc_tpl_")
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		conn, err := pgx.Connect(ctx, admin)
		if err != nil {
			templateErr = err
			return
		}
		_, templateErr = conn.Exec(ctx, "CREATE DATABASE "+templateName)
		conn.Close(ctx)
		if templateErr != nil {
			return
		}
		pool, err := pgxpool.New(ctx, withDatabase(admin, templateName))
		if err != nil {
			templateErr = err
			return
		}
		templateErr = db.Migrate(ctx, pool, migrations.FS, Logger())
		pool.Close()
	})
	if templateErr != nil {
		t.Fatalf("prepare template database: %v", templateErr)
	}
	name := randomName("fc_test_")
	exec(t, admin, "CREATE DATABASE "+name+" TEMPLATE "+templateName)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, withDatabase(admin, name))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		pool.Close()
		exec(t, admin, "DROP DATABASE IF EXISTS "+name+" WITH (FORCE)")
	})
	return pool
}

// DropTemplate removes the per-process template. Call from TestMain after m.Run.
func DropTemplate() {
	if templateName == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if conn, err := pgx.Connect(ctx, os.Getenv("FC_TEST_DATABASE_URL")); err == nil {
		_, _ = conn.Exec(ctx, "DROP DATABASE IF EXISTS "+templateName+" WITH (FORCE)")
		conn.Close(ctx)
	}
}

// Logger discards output unless FC_TEST_LOG=1.
func Logger() *slog.Logger {
	if os.Getenv("FC_TEST_LOG") == "1" {
		return slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelDebug}))
	}
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// MigrationsFS is re-exported for migration tests.
var MigrationsFS fs.FS = migrations.FS
