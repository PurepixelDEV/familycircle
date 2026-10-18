// Command familycircle is the FamilyCircle server: REST API, WebSocket hub and web app in one binary.
//
//	familycircle            start the server (default; same as "serve")
//	familycircle migrate    apply database migrations and exit
//	familycircle healthcheck  exit 0 if the local server answers /healthz (for Docker HEALTHCHECK)
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"familycircle/internal/api"
	"familycircle/internal/auth"
	"familycircle/internal/config"
	"familycircle/internal/db"
	"familycircle/internal/jobs"
	"familycircle/internal/live"
	"familycircle/internal/store"
	"familycircle/internal/webui"
	"familycircle/migrations"
)

// version is set at build time: -ldflags "-X main.version=1.2.3"
var version = "dev"

func main() {
	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	var err error
	switch cmd {
	case "serve":
		err = serve(false)
	case "migrate":
		err = serve(true)
	case "healthcheck":
		err = healthcheck()
	case "version":
		fmt.Println(version)
	default:
		err = fmt.Errorf("unknown command %q (use serve, migrate, healthcheck or version)", cmd)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "familycircle:", err)
		os.Exit(1)
	}
}

func healthcheck() error {
	addr := os.Getenv("FC_HTTP_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	_, port, err := net.SplitHostPort(addr)
	if err != nil {
		return err
	}
	client := http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get("http://127.0.0.1:" + port + "/readyz")
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unhealthy: %s", resp.Status)
	}
	return nil
}

func serve(migrateOnly bool) error {
	cfg, err := config.Load(os.Getenv, version)
	if err != nil {
		return fmt.Errorf("configuration error:\n%w", err)
	}
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel}))
	log.Info("starting familycircle", "version", version)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	connectCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	pool, err := db.Connect(connectCtx, cfg.DatabaseURL, log)
	cancel()
	if err != nil {
		return err
	}
	defer pool.Close()

	if err := db.Migrate(ctx, pool, migrations.FS, log); err != nil {
		// Refuse to start on a half-known schema. Each migration is transactional, so the
		// database is left at the last successfully applied version.
		return fmt.Errorf("database migration failed, not starting: %w", err)
	}
	if migrateOnly {
		log.Info("migrations complete")
		return nil
	}

	st := store.New(pool)
	setupToken, err := setupTokenIfNeeded(ctx, st, cfg, log)
	if err != nil {
		return err
	}
	if !cfg.SecureContext() {
		log.Warn("FC_PUBLIC_URL is not https: session cookies are sent without the Secure flag. Use only in a trusted LAN or put an HTTPS reverse proxy in front.")
	}

	srv := api.New(api.Options{
		Config: cfg, Store: st, Hub: live.NewHub(),
		Hasher: auth.NewHasher(auth.DefaultParams, 4),
		Logger: log, Web: webui.Handler(), SetupToken: setupToken,
	})
	go jobs.Run(ctx, st, srv.Limiters(), time.Hour, log)

	httpServer := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    32 << 10,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelWarn),
	}
	errCh := make(chan error, 1)
	go func() {
		log.Info("listening", "addr", cfg.HTTPAddr)
		errCh <- httpServer.ListenAndServe()
	}()
	select {
	case err := <-errCh:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case <-ctx.Done():
		log.Info("shutting down")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		_ = httpServer.Shutdown(shutdownCtx)
	}
	return nil
}

// setupTokenIfNeeded returns the token required by the setup wizard while no account exists.
// Without FC_SETUP_TOKEN a random code is generated and written to the log, so only someone with
// access to the server logs (e.g. Portainer) can claim a fresh instance.
func setupTokenIfNeeded(ctx context.Context, st *store.Store, cfg config.Config, log *slog.Logger) (string, error) {
	n, err := st.CountUsers(ctx)
	if err != nil {
		return "", err
	}
	if n > 0 {
		return "", nil
	}
	token := cfg.SetupToken
	if token == "" {
		token = auth.NewCode(2)
		log.Warn("SETUP REQUIRED: open the web interface and enter this setup code", "setup_code", token)
	} else {
		log.Warn("SETUP REQUIRED: open the web interface and enter the setup code from FC_SETUP_TOKEN")
	}
	return token, nil
}
