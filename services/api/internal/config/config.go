// Package config reads the runtime configuration from environment variables.
package config

import (
	"errors"
	"fmt"
	"log/slog"
	"net/netip"
	"net/url"
	"strings"
)

// DefaultMapStyleURL is OpenFreeMap's "liberty" style: OSM data, no API key, free to use.
const DefaultMapStyleURL = "https://tiles.openfreemap.org/styles/liberty"

type Config struct {
	DatabaseURL     string
	HTTPAddr        string
	PublicURL       *url.URL // nil when not configured
	TrustedProxies  []netip.Prefix
	MapStyleURL     string
	MapOrigins      []string // origins the browser may contact for map assets (CSP)
	SetupToken      string
	LogLevel        slog.Level
	Version         string
	DevInsecureHTTP bool // allow non-https public URL without warnings (development only)
}

// SecureContext reports whether the instance is served over HTTPS (cookies get the Secure flag).
func (c Config) SecureContext() bool {
	return c.PublicURL != nil && c.PublicURL.Scheme == "https"
}

// Load builds the configuration. getenv is os.Getenv in production and a map lookup in tests.
func Load(getenv func(string) string, version string) (Config, error) {
	var errs []error
	cfg := Config{
		DatabaseURL: strings.TrimSpace(getenv("FC_DATABASE_URL")),
		HTTPAddr:    orDefault(getenv("FC_HTTP_ADDR"), ":8080"),
		MapStyleURL: orDefault(getenv("FC_MAP_STYLE_URL"), DefaultMapStyleURL),
		SetupToken:  strings.TrimSpace(getenv("FC_SETUP_TOKEN")),
		Version:     version,
	}
	if cfg.DatabaseURL == "" {
		errs = append(errs, errors.New("FC_DATABASE_URL is required"))
	}

	if raw := strings.TrimSpace(getenv("FC_PUBLIC_URL")); raw != "" {
		u, err := url.Parse(strings.TrimRight(raw, "/"))
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			errs = append(errs, fmt.Errorf("FC_PUBLIC_URL must be an absolute http(s) URL, got %q", raw))
		} else {
			cfg.PublicURL = u
		}
	}

	for _, p := range splitList(getenv("FC_TRUSTED_PROXIES")) {
		prefix, err := parsePrefix(p)
		if err != nil {
			errs = append(errs, fmt.Errorf("FC_TRUSTED_PROXIES: %w", err))
			continue
		}
		cfg.TrustedProxies = append(cfg.TrustedProxies, prefix)
	}

	styleURL, err := url.Parse(cfg.MapStyleURL)
	if err != nil || styleURL.Scheme != "https" && styleURL.Scheme != "http" {
		errs = append(errs, fmt.Errorf("FC_MAP_STYLE_URL must be an absolute http(s) URL"))
	} else {
		cfg.MapOrigins = append(cfg.MapOrigins, styleURL.Scheme+"://"+styleURL.Host)
	}
	for _, o := range splitList(getenv("FC_MAP_EXTRA_ORIGINS")) {
		u, err := url.Parse(o)
		if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" || u.Path != "" {
			errs = append(errs, fmt.Errorf("FC_MAP_EXTRA_ORIGINS: %q is not an origin like https://tiles.example.org", o))
			continue
		}
		cfg.MapOrigins = append(cfg.MapOrigins, u.Scheme+"://"+u.Host)
	}

	switch strings.ToLower(orDefault(getenv("FC_LOG_LEVEL"), "info")) {
	case "debug":
		cfg.LogLevel = slog.LevelDebug
	case "info":
		cfg.LogLevel = slog.LevelInfo
	case "warn":
		cfg.LogLevel = slog.LevelWarn
	case "error":
		cfg.LogLevel = slog.LevelError
	default:
		errs = append(errs, errors.New("FC_LOG_LEVEL must be debug, info, warn or error"))
	}

	return cfg, errors.Join(errs...)
}

func parsePrefix(s string) (netip.Prefix, error) {
	if strings.Contains(s, "/") {
		return netip.ParsePrefix(s)
	}
	addr, err := netip.ParseAddr(s)
	if err != nil {
		return netip.Prefix{}, err
	}
	return netip.PrefixFrom(addr, addr.BitLen()), nil
}

func orDefault(v, def string) string {
	if v = strings.TrimSpace(v); v == "" {
		return def
	}
	return v
}

func splitList(s string) []string {
	var out []string
	for _, part := range strings.Split(s, ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}
