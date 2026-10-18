// Package api implements the HTTP/WebSocket interface described in packages/api-contract/openapi.yaml.
package api

import (
	"crypto/subtle"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"familycircle/internal/auth"
	"familycircle/internal/config"
	"familycircle/internal/live"
	"familycircle/internal/ratelimit"
	"familycircle/internal/store"
)

type authMode int

const (
	public     authMode = iota // no session required
	userAuth                   // any valid session (web cookie or device bearer)
	deviceAuth                 // device session only (bearer)
)

// Route is exported for the OpenAPI contract test.
type Route struct {
	Method, Path string
	auth         authMode
	handler      http.HandlerFunc
}

type limiters struct {
	general    *ratelimit.Limiter // per client IP, all API requests
	login      *ratelimit.Limiter // per client IP, credential endpoints
	loginEmail *ratelimit.Limiter // per e-mail address
	sensitive  *ratelimit.Limiter // per client IP: setup, register, invite accept, refresh
	upload     *ratelimit.Limiter // per user: location uploads
}

type Server struct {
	cfg    config.Config
	store  *store.Store
	hub    *live.Hub
	hasher *auth.Hasher
	log    *slog.Logger
	lim    limiters
	web    http.Handler

	setupMu    sync.Mutex
	setupToken string
	now        func() time.Time
}

type Options struct {
	Config     config.Config
	Store      *store.Store
	Hub        *live.Hub
	Hasher     *auth.Hasher
	Logger     *slog.Logger
	Web        http.Handler // serves the embedded web app; may be nil
	SetupToken string       // required by POST /setup while no user exists
}

func New(o Options) *Server {
	return &Server{
		cfg: o.Config, store: o.Store, hub: o.Hub, hasher: o.Hasher, log: o.Logger, web: o.Web,
		setupToken: o.SetupToken,
		now:        func() time.Time { return time.Now().UTC() },
		lim: limiters{
			general:    ratelimit.New(240, 600),
			login:      ratelimit.New(20, 4),
			loginEmail: ratelimit.New(5, 1),
			sensitive:  ratelimit.New(10, 2),
			upload:     ratelimit.New(60, 60),
		},
	}
}

// SetClock overrides the clock (tests).
func (s *Server) SetClock(now func() time.Time) { s.now = now }

// Limiters exposes the rate limiters for periodic sweeping.
func (s *Server) Limiters() []*ratelimit.Limiter {
	return []*ratelimit.Limiter{s.lim.general, s.lim.login, s.lim.loginEmail, s.lim.sensitive, s.lim.upload}
}

func (s *Server) checkSetupToken(given string) bool {
	s.setupMu.Lock()
	defer s.setupMu.Unlock()
	return s.setupToken != "" && subtle.ConstantTimeCompare([]byte(auth.NormalizeCode(given)), []byte(auth.NormalizeCode(s.setupToken))) == 1
}

// Routes lists every API endpoint. The contract test compares this table with openapi.yaml.
func (s *Server) Routes() []Route {
	return []Route{
		{"GET", "/instance", public, s.handleInstance},
		{"POST", "/setup", public, s.handleSetup},
		{"POST", "/auth/register", public, s.handleRegister},
		{"POST", "/auth/session", public, s.handleLogin},
		{"DELETE", "/auth/session", userAuth, s.handleLogout},
		{"POST", "/auth/token", public, s.handleToken},

		{"GET", "/me", userAuth, s.handleGetMe},
		{"PATCH", "/me", userAuth, s.handleUpdateMe},
		{"DELETE", "/me", userAuth, s.handleDeleteMe},
		{"PUT", "/me/password", userAuth, s.handleChangePassword},
		{"GET", "/me/settings", userAuth, s.handleGetSettings},
		{"PATCH", "/me/settings", userAuth, s.handleUpdateSettings},
		{"PUT", "/me/avatar", userAuth, s.handlePutAvatar},
		{"DELETE", "/me/avatar", userAuth, s.handleDeleteAvatar},
		{"GET", "/me/sessions", userAuth, s.handleListSessions},
		{"DELETE", "/me/sessions/{sessionId}", userAuth, s.handleRevokeSession},
		{"GET", "/me/devices", userAuth, s.handleListDevices},
		{"DELETE", "/me/devices/{deviceId}", userAuth, s.handleDeleteDevice},
		{"GET", "/me/export", userAuth, s.handleExport},
		{"GET", "/me/locations", userAuth, s.handleOwnHistory},
		{"DELETE", "/me/locations", userAuth, s.handleDeleteOwnLocations},

		{"POST", "/locations", deviceAuth, s.handleUploadLocations},

		{"GET", "/families", userAuth, s.handleListFamilies},
		{"POST", "/families", userAuth, s.handleCreateFamily},
		{"GET", "/families/{familyId}", userAuth, s.handleGetFamily},
		{"PATCH", "/families/{familyId}", userAuth, s.handleRenameFamily},
		{"POST", "/families/{familyId}/leave", userAuth, s.handleLeaveFamily},
		{"PATCH", "/families/{familyId}/members/{userId}", userAuth, s.handleUpdateMember},
		{"DELETE", "/families/{familyId}/members/{userId}", userAuth, s.handleRemoveMember},
		{"GET", "/families/{familyId}/map", userAuth, s.handleFamilyMap},
		{"GET", "/families/{familyId}/invites", userAuth, s.handleListInvites},
		{"POST", "/families/{familyId}/invites", userAuth, s.handleCreateInvite},
		{"DELETE", "/families/{familyId}/invites/{inviteId}", userAuth, s.handleRevokeInvite},
		{"POST", "/invites/accept", userAuth, s.handleAcceptInvite},

		{"GET", "/shares", userAuth, s.handleListShares},
		{"PUT", "/shares/{userId}", userAuth, s.handleGrantShare},
		{"DELETE", "/shares/{userId}", userAuth, s.handleRevokeShare},

		{"GET", "/users/{userId}/avatar", userAuth, s.handleGetAvatar},
		{"GET", "/ws", userAuth, s.handleWebSocket},
	}
}

const apiPrefix = "/api/v1"

// Handler returns the complete HTTP handler: API, health endpoints and the web app.
func (s *Server) Handler() http.Handler {
	api := http.NewServeMux()
	for _, rt := range s.Routes() {
		h := http.Handler(rt.handler)
		switch rt.auth {
		case userAuth:
			h = s.requireAuth(h, false)
		case deviceAuth:
			h = s.requireAuth(h, true)
		}
		api.Handle(rt.Method+" "+apiPrefix+rt.Path, h)
	}
	api.HandleFunc(apiPrefix+"/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "not_found", "unknown endpoint")
	})

	root := http.NewServeMux()
	root.Handle(apiPrefix+"/", s.rateLimitGeneral(s.csrf(api)))
	root.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		_, _ = w.Write([]byte("ok"))
	})
	root.HandleFunc("GET /readyz", s.handleReady)
	if s.web != nil {
		root.Handle("/", s.web)
	} else {
		root.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { http.NotFound(w, r) })
	}
	return s.recoverer(s.securityHeaders(s.logRequests(root)))
}

func (s *Server) handleReady(w http.ResponseWriter, r *http.Request) {
	if err := s.store.Ping(r.Context()); err != nil {
		s.log.Warn("readiness check failed", "err", err)
		http.Error(w, "database unavailable", http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "text/plain")
	_, _ = w.Write([]byte("ready"))
}
