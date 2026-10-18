package api

import (
	"errors"
	"net/http"
	"strings"

	"familycircle/internal/auth"
	"familycircle/internal/store"
)

func (s *Server) handleInstance(w http.ResponseWriter, r *http.Request) {
	n, err := s.store.CountUsers(r.Context())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	mode, err := s.store.RegistrationMode(r.Context())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"setup_required":    n == 0,
		"version":           s.cfg.Version,
		"map_style_url":     s.cfg.MapStyleURL,
		"secure_context":    s.cfg.SecureContext(),
		"registration_mode": mode,
	})
}

func validLocale(l string) bool { return l == "de" || l == "en" }

type accountInput struct {
	Email       string `json:"email"`
	Password    string `json:"password"`
	DisplayName string `json:"display_name"`
	Locale      string `json:"locale"`
}

func (in *accountInput) validate(f fieldErrors) {
	in.Email = normEmail(in.Email)
	in.DisplayName = cleanName(in.DisplayName)
	if in.Locale == "" {
		in.Locale = "de"
	}
	f.check(validEmail(in.Email), "email", "invalid e-mail address")
	if err := auth.ValidatePassword(in.Password); err != nil {
		f["password"] = err.Error()
	}
	f.check(runeLen(in.DisplayName) >= 1 && runeLen(in.DisplayName) <= 64, "display_name", "1–64 characters")
	f.check(validLocale(in.Locale), "locale", "must be de or en")
}

func (s *Server) handleSetup(w http.ResponseWriter, r *http.Request) {
	if ok, wait := s.lim.sensitive.Allow(s.clientIP(r)); !ok {
		tooMany(w, wait)
		return
	}
	var in struct {
		accountInput
		SetupToken string `json:"setup_token"`
		FamilyName string `json:"family_name"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	if n, err := s.store.CountUsers(r.Context()); err != nil {
		s.fail(w, r, err)
		return
	} else if n > 0 {
		writeError(w, http.StatusConflict, "already_setup", "this instance is already set up")
		return
	}
	if !s.checkSetupToken(in.SetupToken) {
		writeError(w, http.StatusForbidden, "invalid_setup_token", "the setup code is wrong (see the server log)")
		return
	}
	f := fieldErrors{}
	in.validate(f)
	in.FamilyName = cleanName(in.FamilyName)
	f.check(runeLen(in.FamilyName) >= 1 && runeLen(in.FamilyName) <= 64, "family_name", "1–64 characters")
	if f.write(w) {
		return
	}
	hash, err := s.hasher.Hash(r.Context(), in.Password)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	u, err := s.store.CreateInitialAdmin(r.Context(), store.NewUser{Email: in.Email, DisplayName: in.DisplayName, PasswordHash: hash, Locale: in.Locale}, in.FamilyName)
	if errors.Is(err, store.ErrConflict) {
		writeError(w, http.StatusConflict, "already_setup", "this instance is already set up")
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.log.Info("instance setup completed")
	s.startWebSession(w, r, u, http.StatusCreated)
}

func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if ok, wait := s.lim.sensitive.Allow(s.clientIP(r)); !ok {
		tooMany(w, wait)
		return
	}
	var in struct {
		accountInput
		InviteCode string `json:"invite_code"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	f := fieldErrors{}
	in.validate(f)
	f.check(strings.TrimSpace(in.InviteCode) != "", "invite_code", "required")
	if f.write(w) {
		return
	}
	u, ok := s.registerWithInvite(w, r, in.accountInput, in.InviteCode)
	if !ok {
		return
	}
	s.startWebSession(w, r, u, http.StatusCreated)
}

func (s *Server) registerWithInvite(w http.ResponseWriter, r *http.Request, in accountInput, code string) (store.User, bool) {
	hash, err := s.hasher.Hash(r.Context(), in.Password)
	if err != nil {
		s.fail(w, r, err)
		return store.User{}, false
	}
	u, familyID, err := s.store.RegisterWithInvite(r.Context(), auth.HashCode(code),
		store.NewUser{Email: in.Email, DisplayName: in.DisplayName, PasswordHash: hash, Locale: in.Locale})
	if err != nil {
		s.fail(w, r, err)
		return store.User{}, false
	}
	s.notifyFamilyChanged(r.Context(), familyID)
	return u, true
}

func (s *Server) startWebSession(w http.ResponseWriter, r *http.Request, u store.User, status int) {
	token, sess, err := s.store.CreateWebSession(r.Context(), u.ID, r.UserAgent())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.setCookie(w, token, sess.AccessExpiresAt)
	writeJSON(w, status, toMe(u))
}

// checkCredentials verifies e-mail/password with per-IP and per-account throttling. Unknown
// accounts take the same time as known ones.
func (s *Server) checkCredentials(w http.ResponseWriter, r *http.Request, email, password string) (store.User, bool) {
	email = strings.ToLower(normEmail(email))
	if ok, wait := s.lim.login.Allow(s.clientIP(r)); !ok {
		tooMany(w, wait)
		return store.User{}, false
	}
	if ok, wait := s.lim.loginEmail.Allow(email); !ok {
		tooMany(w, wait)
		return store.User{}, false
	}
	if email == "" || password == "" || len(password) > 1024 {
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "e-mail or password is wrong")
		return store.User{}, false
	}
	u, err := s.store.UserByEmail(r.Context(), email)
	if errors.Is(err, store.ErrNotFound) {
		s.hasher.VerifyDummy(r.Context(), password)
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "e-mail or password is wrong")
		return store.User{}, false
	}
	if err != nil {
		s.fail(w, r, err)
		return store.User{}, false
	}
	ok, rehash, err := s.hasher.Verify(r.Context(), password, u.PasswordHash)
	if err != nil {
		s.fail(w, r, err)
		return store.User{}, false
	}
	if !ok {
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "e-mail or password is wrong")
		return store.User{}, false
	}
	s.lim.loginEmail.Reset(email)
	if rehash {
		if h, err := s.hasher.Hash(r.Context(), password); err == nil {
			_ = s.store.SetPasswordHash(r.Context(), u.ID, h)
		}
	}
	return u, true
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	u, ok := s.checkCredentials(w, r, in.Email, in.Password)
	if !ok {
		return
	}
	s.startWebSession(w, r, u, http.StatusOK)
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	p := principalFrom(r)
	if err := s.store.RevokeSession(r.Context(), p.user.ID, p.session.ID); err != nil && !errors.Is(err, store.ErrNotFound) {
		s.fail(w, r, err)
		return
	}
	s.hub.CloseSession(p.session.ID)
	if p.viaCookie {
		s.clearCookie(w)
	}
	w.WriteHeader(http.StatusNoContent)
}

type tokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	TokenType    string `json:"token_type"`
	ExpiresIn    int    `json:"expires_in"`
	DeviceID     string `json:"device_id"`
	User         meDTO  `json:"user"`
}

func (s *Server) handleToken(w http.ResponseWriter, r *http.Request) {
	var in struct {
		GrantType    string `json:"grant_type"`
		Email        string `json:"email"`
		Password     string `json:"password"`
		DeviceName   string `json:"device_name"`
		AppVersion   string `json:"app_version"`
		RefreshToken string `json:"refresh_token"`
		InviteCode   string `json:"invite_code"`
		DisplayName  string `json:"display_name"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	switch in.GrantType {
	case "refresh_token":
		if ok, wait := s.lim.sensitive.Allow(s.clientIP(r)); !ok {
			tooMany(w, wait)
			return
		}
		tokens, u, err := s.store.Refresh(r.Context(), in.RefreshToken)
		switch {
		case errors.Is(err, store.ErrTokenReused):
			s.log.Warn("refresh token reuse detected, session revoked")
			writeError(w, http.StatusUnauthorized, "token_reused", "this refresh token was already used; please sign in again")
		case errors.Is(err, store.ErrNotFound):
			writeError(w, http.StatusUnauthorized, "invalid_grant", "refresh token invalid or expired")
		case err != nil:
			s.fail(w, r, err)
		default:
			s.writeTokens(w, tokens, u)
		}
	case "password":
		f := fieldErrors{}
		in.DeviceName = cleanName(in.DeviceName)
		f.check(runeLen(in.DeviceName) >= 1 && runeLen(in.DeviceName) <= 64, "device_name", "1–64 characters")
		f.check(len(in.AppVersion) <= 32, "app_version", "at most 32 characters")
		var u store.User
		if in.InviteCode != "" {
			if ok, wait := s.lim.sensitive.Allow(s.clientIP(r)); !ok {
				tooMany(w, wait)
				return
			}
			acc := accountInput{Email: in.Email, Password: in.Password, DisplayName: in.DisplayName, Locale: "de"}
			acc.validate(f)
			if f.write(w) {
				return
			}
			var ok bool
			if u, ok = s.registerWithInvite(w, r, acc, in.InviteCode); !ok {
				return
			}
		} else {
			if f.write(w) {
				return
			}
			var ok bool
			if u, ok = s.checkCredentials(w, r, in.Email, in.Password); !ok {
				return
			}
		}
		tokens, err := s.store.CreateDeviceSession(r.Context(), u.ID, in.DeviceName, in.AppVersion, r.UserAgent())
		if err != nil {
			s.fail(w, r, err)
			return
		}
		s.writeTokens(w, tokens, u)
	default:
		writeFieldErrors(w, http.StatusBadRequest, "invalid_input", "invalid input", map[string]string{"grant_type": "must be password or refresh_token"})
	}
}

func (s *Server) writeTokens(w http.ResponseWriter, t store.Tokens, u store.User) {
	writeJSON(w, http.StatusOK, tokenResponse{
		AccessToken: t.Access, RefreshToken: t.Refresh, TokenType: "Bearer",
		ExpiresIn: int(t.AccessExpires.Sub(s.now()).Seconds()), DeviceID: t.Session.DeviceID.String(), User: toMe(u),
	})
}
