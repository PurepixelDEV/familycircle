package api

import (
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"familycircle/internal/store"
)

type errorBody struct {
	Error struct {
		Code    string            `json:"code"`
		Message string            `json:"message"`
		Fields  map[string]string `json:"fields,omitempty"`
	} `json:"error"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	writeFieldErrors(w, status, code, msg, nil)
}

func writeFieldErrors(w http.ResponseWriter, status int, code, msg string, fields map[string]string) {
	var b errorBody
	b.Error.Code, b.Error.Message, b.Error.Fields = code, msg, fields
	writeJSON(w, status, b)
}

// fail maps store errors to HTTP responses. Unknown errors are logged and hidden.
func (s *Server) fail(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "not found")
	case errors.Is(err, store.ErrForbidden):
		writeError(w, http.StatusForbidden, "forbidden", "you are not allowed to do this")
	case errors.Is(err, store.ErrEmailTaken):
		writeError(w, http.StatusConflict, "email_taken", "an account with this e-mail already exists")
	case errors.Is(err, store.ErrInviteInvalid):
		writeError(w, http.StatusBadRequest, "invite_invalid", "the invite code is invalid, expired or already used")
	case errors.Is(err, store.ErrAlreadyMember):
		writeError(w, http.StatusConflict, "already_member", "you are already a member of this family")
	case errors.Is(err, store.ErrLastAdmin):
		writeError(w, http.StatusConflict, "last_admin", "a family needs at least one admin; promote someone first")
	case errors.Is(err, store.ErrRegistration):
		writeError(w, http.StatusForbidden, "registration_closed", "registration is closed on this instance")
	case errors.Is(err, store.ErrConflict):
		writeError(w, http.StatusConflict, "conflict", "conflicting state")
	default:
		s.log.Error("request failed", "route", routeOf(r), "err", err)
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
	}
}

const maxJSONBody = 64 << 10

// decode parses a JSON body of limited size into v. Returns false after writing an error.
func decode(w http.ResponseWriter, r *http.Request, v any, limit int64) bool {
	if ct := r.Header.Get("Content-Type"); ct != "" {
		if mt, _, _ := mime.ParseMediaType(ct); mt != "application/json" {
			writeError(w, http.StatusUnsupportedMediaType, "invalid_input", "expected application/json")
			return false
		}
	}
	if limit == 0 {
		limit = maxJSONBody
	}
	setReadDeadline(w, 15*time.Second)
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	if err := dec.Decode(v); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			writeError(w, http.StatusRequestEntityTooLarge, "invalid_input", "request body too large")
		} else {
			writeError(w, http.StatusBadRequest, "invalid_input", "malformed JSON body")
		}
		return false
	}
	if _, err := dec.Token(); err != io.EOF {
		writeError(w, http.StatusBadRequest, "invalid_input", "unexpected data after JSON body")
		return false
	}
	return true
}

// setReadDeadline bounds how long a client may take to send the request body (slow-body DoS).
// The server has no global ReadTimeout because WebSocket connections are long-lived.
func setReadDeadline(w http.ResponseWriter, d time.Duration) {
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(d))
}

// pathID parses a UUID path parameter. Invalid IDs are reported as not found, like foreign IDs.
func pathID(w http.ResponseWriter, r *http.Request, name string) (uuid.UUID, bool) {
	id, err := uuid.Parse(r.PathValue(name))
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", "not found")
		return uuid.Nil, false
	}
	return id, true
}

type fieldErrors map[string]string

func (f fieldErrors) check(cond bool, field, msg string) {
	if !cond {
		if _, exists := f[field]; !exists {
			f[field] = msg
		}
	}
}

func (f fieldErrors) write(w http.ResponseWriter) bool {
	if len(f) == 0 {
		return false
	}
	writeFieldErrors(w, http.StatusBadRequest, "invalid_input", "invalid input", f)
	return true
}

func runeLen(s string) int { return len([]rune(s)) }

func cleanName(s string) string { return strings.Join(strings.Fields(s), " ") }

func validEmail(e string) bool {
	at := strings.LastIndexByte(e, '@')
	return at > 0 && at < len(e)-3 && strings.Contains(e[at:], ".") && len(e) <= 254 && !strings.ContainsAny(e, " \t\r\n<>")
}

func normEmail(e string) string { return strings.TrimSpace(e) }
