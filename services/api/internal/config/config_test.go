package config

import (
	"log/slog"
	"strings"
	"testing"
)

func env(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func TestLoadDefaults(t *testing.T) {
	cfg, err := Load(env(map[string]string{"FC_DATABASE_URL": "postgres://x"}), "test")
	if err != nil {
		t.Fatal(err)
	}
	if cfg.HTTPAddr != ":8080" || cfg.MapStyleURL != DefaultMapStyleURL || cfg.LogLevel != slog.LevelInfo {
		t.Fatalf("unexpected defaults: %+v", cfg)
	}
	if cfg.SecureContext() {
		t.Fatal("no public URL must not be a secure context")
	}
	if len(cfg.MapOrigins) != 1 || cfg.MapOrigins[0] != "https://tiles.openfreemap.org" {
		t.Fatalf("map origins: %v", cfg.MapOrigins)
	}
}

func TestLoadFull(t *testing.T) {
	cfg, err := Load(env(map[string]string{
		"FC_DATABASE_URL":      "postgres://x",
		"FC_PUBLIC_URL":        "https://family.example.org/",
		"FC_TRUSTED_PROXIES":   "172.16.0.0/12, 10.0.0.5",
		"FC_MAP_EXTRA_ORIGINS": "https://tiles.example.org",
		"FC_LOG_LEVEL":         "debug",
	}), "test")
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.SecureContext() || cfg.PublicURL.String() != "https://family.example.org" {
		t.Fatalf("public url: %v", cfg.PublicURL)
	}
	if len(cfg.TrustedProxies) != 2 || cfg.TrustedProxies[1].Bits() != 32 {
		t.Fatalf("proxies: %v", cfg.TrustedProxies)
	}
	if len(cfg.MapOrigins) != 2 {
		t.Fatalf("origins: %v", cfg.MapOrigins)
	}
}

func TestLoadErrors(t *testing.T) {
	_, err := Load(env(map[string]string{
		"FC_PUBLIC_URL":        "ftp://nope",
		"FC_TRUSTED_PROXIES":   "not-an-ip",
		"FC_MAP_EXTRA_ORIGINS": "https://x.org/path",
		"FC_LOG_LEVEL":         "loud",
	}), "test")
	if err == nil {
		t.Fatal("expected errors")
	}
	for _, want := range []string{"FC_DATABASE_URL", "FC_PUBLIC_URL", "FC_TRUSTED_PROXIES", "FC_MAP_EXTRA_ORIGINS", "FC_LOG_LEVEL"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error should mention %s: %v", want, err)
		}
	}
}
