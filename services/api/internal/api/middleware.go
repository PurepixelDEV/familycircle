package api

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"

	"familycircle/internal/store"
)

const (
	cookieName = "fc_session"
	csrfHeader = "X-FC-CSRF"
)

type ctxKey int

const principalKey ctxKey = 1

type principal struct {
	user      store.User
	session   store.Session
	viaCookie bool
}

func principalFrom(r *http.Request) principal {
	p, _ := r.Context().Value(principalKey).(principal)
	return p
}

// statusRecorder captures the status for logging while staying hijackable for WebSockets.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	if r.status == 0 {
		r.status = code
	}
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	return r.ResponseWriter.Write(b)
}

func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

func (r *statusRecorder) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	if hj, ok := r.ResponseWriter.(http.Hijacker); ok {
		if r.status == 0 {
			r.status = http.StatusSwitchingProtocols
		}
		return hj.Hijack()
	}
	return nil, nil, errors.New("hijacking not supported")
}

func (r *statusRecorder) Flush() {
	if f, ok := r.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

// logRequests logs method, path, status and duration. Never bodies or query strings: those may
// contain coordinates or credentials.
func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)
		if r.URL.Path == "/healthz" || r.URL.Path == "/readyz" {
			return
		}
		level := s.log.Debug
		if rec.status >= 500 {
			level = s.log.Error
		} else if strings.HasPrefix(r.URL.Path, apiPrefix) {
			level = s.log.Info
		}
		level("http", "method", r.Method, "path", r.URL.Path, "status", rec.status, "duration_ms", time.Since(start).Milliseconds())
	})
}

func (s *Server) recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				if err, ok := v.(error); ok && errors.Is(err, http.ErrAbortHandler) {
					panic(v)
				}
				s.log.Error("panic in handler", "path", r.URL.Path, "panic", fmt.Sprint(v))
				writeError(w, http.StatusInternalServerError, "internal", "internal error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func (s *Server) contentSecurityPolicy() string {
	maps := strings.Join(s.cfg.MapOrigins, " ")
	return strings.Join([]string{
		"default-src 'self'",
		"script-src 'self'",
		"style-src 'self' 'unsafe-inline'", // MapLibre sets inline styles on markers/controls
		"img-src 'self' data: blob: " + maps,
		"font-src 'self' " + maps,
		"connect-src 'self' " + maps,
		"worker-src 'self' blob:",
		"child-src blob:",
		"manifest-src 'self'",
		"object-src 'none'",
		"base-uri 'none'",
		"form-action 'self'",
		"frame-ancestors 'none'",
	}, "; ")
}

func (s *Server) securityHeaders(next http.Handler) http.Handler {
	csp := s.contentSecurityPolicy()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", csp)
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=()")
		h.Set("Cross-Origin-Opener-Policy", "same-origin")
		if s.cfg.SecureContext() {
			h.Set("Strict-Transport-Security", "max-age=31536000")
		}
		if strings.HasPrefix(r.URL.Path, apiPrefix) {
			h.Set("Cache-Control", "no-store")
		}
		next.ServeHTTP(w, r)
	})
}

// clientIP returns the rate-limiting identity of the client. X-Forwarded-For is honoured only
// when the direct peer is a configured trusted proxy; then the right-most untrusted address is used.
// IPv6 clients are grouped by /64, since one host typically controls a whole /64.
func (s *Server) clientIP(r *http.Request) string {
	return rateKey(s.clientAddr(r))
}

func rateKey(addr string) string {
	a, err := netip.ParseAddr(addr)
	if err != nil || a.Is4() {
		return addr
	}
	p, err := a.Prefix(64)
	if err != nil {
		return addr
	}
	return p.String()
}

func (s *Server) clientAddr(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	peer, err := netip.ParseAddr(host)
	if err != nil {
		return host
	}
	peer = peer.Unmap()
	if !s.trusted(peer) {
		if r.Header.Get("X-Forwarded-For") != "" {
			// Most likely a reverse proxy that is not configured as trusted. Tell the operator once
			// which address to use (the proxy's address is not personal data of a family member).
			s.proxyHintOnce.Do(func() {
				s.log.Warn("received X-Forwarded-For from an untrusted peer; if this is your reverse proxy, set FC_TRUSTED_PROXIES to this address",
					"peer", peer.String())
			})
		}
		return peer.String()
	}
	hops := strings.Split(r.Header.Get("X-Forwarded-For"), ",")
	for i := len(hops) - 1; i >= 0; i-- {
		a, err := netip.ParseAddr(strings.TrimSpace(hops[i]))
		if err != nil {
			break
		}
		a = a.Unmap()
		if !s.trusted(a) {
			return a.String()
		}
	}
	return peer.String()
}

func (s *Server) trusted(a netip.Addr) bool {
	for _, p := range s.cfg.TrustedProxies {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

func tooMany(w http.ResponseWriter, wait time.Duration) {
	secs := int(wait.Seconds()) + 1
	w.Header().Set("Retry-After", strconv.Itoa(secs))
	writeError(w, http.StatusTooManyRequests, "rate_limited", "too many requests, retry later")
}

func (s *Server) rateLimitGeneral(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if ok, wait := s.lim.general.Allow(s.clientIP(r)); !ok {
			tooMany(w, wait)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if len(h) > 7 && strings.EqualFold(h[:7], "bearer ") {
		return strings.TrimSpace(h[7:])
	}
	return ""
}

// csrf protects cookie-authenticated (and cookie-issuing) state changes. Requests carrying a bearer
// token are not exposed to CSRF because browsers never attach it automatically.
func (s *Server) csrf(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet, http.MethodHead, http.MethodOptions:
			next.ServeHTTP(w, r)
			return
		}
		if bearerToken(r) == "" {
			if r.Header.Get(csrfHeader) != "1" {
				writeError(w, http.StatusForbidden, "csrf", "missing "+csrfHeader+" header")
				return
			}
			if site := r.Header.Get("Sec-Fetch-Site"); site != "" && site != "same-origin" && site != "none" {
				writeError(w, http.StatusForbidden, "csrf", "cross-site request")
				return
			}
			if origin := r.Header.Get("Origin"); origin != "" && !s.sameOrigin(origin, r) {
				writeError(w, http.StatusForbidden, "csrf", "origin not allowed")
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) sameOrigin(origin string, r *http.Request) bool {
	u, err := url.Parse(origin)
	if err != nil {
		return false
	}
	if s.cfg.PublicURL != nil && strings.EqualFold(u.Host, s.cfg.PublicURL.Host) {
		return true
	}
	return strings.EqualFold(u.Host, r.Host)
}

// requireAuth resolves the session from the bearer token or cookie.
func (s *Server) requireAuth(next http.Handler, deviceOnly bool) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, viaCookie := bearerToken(r), false
		if token == "" {
			if c, err := r.Cookie(cookieName); err == nil {
				token, viaCookie = c.Value, true
			}
		}
		if token == "" {
			writeError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
			return
		}
		user, sess, err := s.store.Authenticate(r.Context(), token)
		if err != nil {
			if !errors.Is(err, store.ErrNotFound) {
				s.log.Error("authenticate", "err", err)
				writeError(w, http.StatusInternalServerError, "internal", "internal error")
				return
			}
			if viaCookie {
				s.clearCookie(w)
			}
			writeError(w, http.StatusUnauthorized, "unauthorized", "session invalid or expired")
			return
		}
		if deviceOnly && sess.Kind != "device" {
			writeError(w, http.StatusForbidden, "device_session_required", "this endpoint requires a device session")
			return
		}
		if s.now().Sub(sess.LastUsedAt) > 5*time.Minute {
			if expires, err := s.store.Touch(r.Context(), sess); err == nil && viaCookie && sess.Kind == "web" {
				s.setCookie(w, token, expires)
			}
		}
		ctx := context.WithValue(r.Context(), principalKey, principal{user: user, session: sess, viaCookie: viaCookie})
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// Secure is tied to an https FC_PUBLIC_URL on purpose: a LAN test over plain http must still
// work (the server logs a warning in that case). HttpOnly and SameSite are always set.
func (s *Server) setCookie(w http.ResponseWriter, token string, expires time.Time) {
	http.SetCookie(w, &http.Cookie{ //nolint:gosec // G124: Secure depends on deployment, see above
		Name: cookieName, Value: token, Path: "/", Expires: expires,
		HttpOnly: true, Secure: s.cfg.SecureContext(), SameSite: http.SameSiteLaxMode,
	})
}

func (s *Server) clearCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{ //nolint:gosec // G124: see setCookie
		Name: cookieName, Value: "", Path: "/", MaxAge: -1,
		HttpOnly: true, Secure: s.cfg.SecureContext(), SameSite: http.SameSiteLaxMode,
	})
}
