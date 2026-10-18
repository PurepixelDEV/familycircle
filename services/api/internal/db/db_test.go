package db_test

import (
	"context"
	"errors"
	"testing"
	"testing/fstest"

	"github.com/jackc/pgx/v5/pgxpool"

	"familycircle/internal/db"
	"familycircle/internal/testdb"
)

func open(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := testdb.Empty(t)
	ctx := context.Background()
	pool, err := db.Connect(ctx, url, testdb.Logger())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func TestRealMigrationsApplyAndAreIdempotent(t *testing.T) {
	pool := open(t)
	ctx := context.Background()
	if err := db.Migrate(ctx, pool, testdb.MigrationsFS, testdb.Logger()); err != nil {
		t.Fatalf("first run: %v", err)
	}
	if err := db.Migrate(ctx, pool, testdb.MigrationsFS, testdb.Logger()); err != nil {
		t.Fatalf("second run must be a no-op: %v", err)
	}
	var postgis string
	if err := pool.QueryRow(ctx, "SELECT postgis_version()").Scan(&postgis); err != nil {
		t.Fatalf("postgis not available: %v", err)
	}
	var mode string
	if err := pool.QueryRow(ctx, "SELECT registration_mode FROM instance_settings").Scan(&mode); err != nil || mode != "invite_only" {
		t.Fatalf("instance settings: %q %v", mode, err)
	}
}

func TestFailingMigrationIsRolledBack(t *testing.T) {
	pool := open(t)
	ctx := context.Background()
	fsys := fstest.MapFS{
		"0001_ok.sql":     {Data: []byte("CREATE TABLE a (id int);")},
		"0002_broken.sql": {Data: []byte("CREATE TABLE b (id int); SELECT * FROM does_not_exist;")},
	}
	err := db.Migrate(ctx, pool, fsys, testdb.Logger())
	if err == nil {
		t.Fatal("expected failure")
	}
	var exists bool
	_ = pool.QueryRow(ctx, "SELECT to_regclass('b') IS NOT NULL").Scan(&exists)
	if exists {
		t.Fatal("table b must not exist after rollback")
	}
	_ = pool.QueryRow(ctx, "SELECT to_regclass('a') IS NOT NULL").Scan(&exists)
	if !exists {
		t.Fatal("migration 0001 should have been applied")
	}
}

func TestModifiedMigrationIsDetected(t *testing.T) {
	pool := open(t)
	ctx := context.Background()
	v1 := fstest.MapFS{"0001_a.sql": {Data: []byte("CREATE TABLE a (id int);")}}
	if err := db.Migrate(ctx, pool, v1, testdb.Logger()); err != nil {
		t.Fatal(err)
	}
	v1["0001_a.sql"] = &fstest.MapFile{Data: []byte("CREATE TABLE a (id bigint);")}
	if err := db.Migrate(ctx, pool, v1, testdb.Logger()); !errors.Is(err, db.ErrChecksumMismatch) {
		t.Fatalf("want ErrChecksumMismatch, got %v", err)
	}
}

func TestDowngradeIsRefused(t *testing.T) {
	pool := open(t)
	ctx := context.Background()
	v2 := fstest.MapFS{
		"0001_a.sql": {Data: []byte("CREATE TABLE a (id int);")},
		"0002_b.sql": {Data: []byte("CREATE TABLE b (id int);")},
	}
	if err := db.Migrate(ctx, pool, v2, testdb.Logger()); err != nil {
		t.Fatal(err)
	}
	delete(v2, "0002_b.sql")
	if err := db.Migrate(ctx, pool, v2, testdb.Logger()); !errors.Is(err, db.ErrDatabaseNewer) {
		t.Fatalf("want ErrDatabaseNewer, got %v", err)
	}
}

func TestInvalidFileNamesAreRejected(t *testing.T) {
	pool := open(t)
	bad := fstest.MapFS{"1_wrong.sql": {Data: []byte("SELECT 1;")}}
	if err := db.Migrate(context.Background(), pool, bad, testdb.Logger()); err == nil {
		t.Fatal("expected error for invalid name")
	}
}
