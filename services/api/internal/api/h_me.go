package api

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/jpeg"
	_ "image/png" // register PNG decoder
	"io"
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"
	"golang.org/x/image/draw"

	"familycircle/internal/auth"
	"familycircle/internal/store"
)

func (s *Server) handleGetMe(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, toMe(principalFrom(r).user))
}

func (s *Server) handleUpdateMe(w http.ResponseWriter, r *http.Request) {
	var in struct {
		DisplayName *string `json:"display_name"`
		Locale      *string `json:"locale"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	f := fieldErrors{}
	if in.DisplayName != nil {
		*in.DisplayName = cleanName(*in.DisplayName)
		f.check(runeLen(*in.DisplayName) >= 1 && runeLen(*in.DisplayName) <= 64, "display_name", "1–64 characters")
	}
	if in.Locale != nil {
		f.check(validLocale(*in.Locale), "locale", "must be de or en")
	}
	if f.write(w) {
		return
	}
	u, err := s.store.UpdateProfile(r.Context(), principalFrom(r).user.ID, in.DisplayName, in.Locale)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if in.DisplayName != nil {
		s.notifyAllFamilies(r.Context(), u.ID)
	}
	writeJSON(w, http.StatusOK, toMe(u))
}

func (s *Server) notifyAllFamilies(ctx context.Context, userID uuid.UUID) {
	fams, err := s.store.ListFamilies(ctx, userID)
	if err != nil {
		return
	}
	for _, f := range fams {
		s.notifyFamilyChanged(ctx, f.ID)
	}
}

// verifyPassword re-checks the current user's password for sensitive operations (rate limited).
func (s *Server) verifyPassword(w http.ResponseWriter, r *http.Request, password string) bool {
	p := principalFrom(r)
	key := "reauth:" + p.user.ID.String()
	if blocked, wait := s.lim.failPair.Blocked(key); blocked {
		tooMany(w, wait)
		return false
	}
	ok, _, err := s.hasher.Verify(r.Context(), password, p.user.PasswordHash)
	if err != nil {
		s.fail(w, r, err)
		return false
	}
	if !ok {
		s.lim.failPair.Allow(key)
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "password is wrong")
		return false
	}
	return true
}

func (s *Server) handleDeleteMe(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Password string `json:"password"`
	}
	if !decode(w, r, &in, 0) || !s.verifyPassword(w, r, in.Password) {
		return
	}
	p := principalFrom(r)
	res, err := s.store.DeleteUser(r.Context(), p.user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyHidden(p.user.ID, res.FormerViewers)
	for _, f := range res.Families {
		s.notifyFamilyChanged(r.Context(), f)
	}
	s.hub.CloseUser(p.user.ID)
	if p.viaCookie {
		s.clearCookie(w)
	}
	s.log.Info("account deleted")
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleChangePassword(w http.ResponseWriter, r *http.Request) {
	var in struct {
		CurrentPassword string `json:"current_password"`
		NewPassword     string `json:"new_password"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	if err := auth.ValidatePassword(in.NewPassword); err != nil {
		writeFieldErrors(w, http.StatusBadRequest, "invalid_input", "invalid input", map[string]string{"new_password": err.Error()})
		return
	}
	if !s.verifyPassword(w, r, in.CurrentPassword) {
		return
	}
	p := principalFrom(r)
	hash, err := s.hasher.Hash(r.Context(), in.NewPassword)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if err := s.store.SetPasswordHash(r.Context(), p.user.ID, hash); err != nil {
		s.fail(w, r, err)
		return
	}
	revoked, err := s.store.RevokeOtherSessions(r.Context(), p.user.ID, p.session.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	for _, id := range revoked {
		s.hub.CloseSession(id)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleGetSettings(w http.ResponseWriter, r *http.Request) {
	st, err := s.store.Settings(r.Context(), principalFrom(r).user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, toSettings(st))
}

func (s *Server) handleUpdateSettings(w http.ResponseWriter, r *http.Request) {
	var in struct {
		SharingPaused        *bool `json:"sharing_paused"`
		HistoryEnabled       *bool `json:"history_enabled"`
		HistoryRetentionDays *int  `json:"history_retention_days"`
		ShareBattery         *bool `json:"share_battery"`
		AllowRing            *bool `json:"allow_ring"`
		AllowLocateRequest   *bool `json:"allow_locate_request"`
		BatteryAlerts        *bool `json:"battery_alerts"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	if in.HistoryRetentionDays != nil && (*in.HistoryRetentionDays < 1 || *in.HistoryRetentionDays > 365) {
		writeFieldErrors(w, http.StatusBadRequest, "invalid_input", "invalid input", map[string]string{"history_retention_days": "1–365"})
		return
	}
	uid := principalFrom(r).user.ID
	viewersBefore, err := s.store.ViewersOf(r.Context(), uid)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	before, after, err := s.store.UpdateSettings(r.Context(), uid, store.SettingsUpdate{
		SharingPaused: in.SharingPaused, HistoryEnabled: in.HistoryEnabled,
		HistoryRetentionDays: in.HistoryRetentionDays, ShareBattery: in.ShareBattery,
		AllowRing: in.AllowRing, AllowLocateRequest: in.AllowLocateRequest, BatteryAlerts: in.BatteryAlerts,
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if before.SharingPaused != after.SharingPaused || before.ShareBattery != after.ShareBattery {
		viewersAfter, err := s.store.ViewersOf(r.Context(), uid)
		if err == nil {
			still := map[uuid.UUID]bool{}
			for _, v := range viewersAfter {
				still[v] = true
			}
			var gone []uuid.UUID
			for _, v := range viewersBefore {
				if !still[v] {
					gone = append(gone, v)
				}
			}
			s.notifyHidden(uid, gone)
			s.republish(r.Context(), uid)
		}
	}
	writeJSON(w, http.StatusOK, toSettings(after))
}

const (
	maxAvatarUpload = 2 << 20
	avatarSize      = 256
	// 16 MP covers current phone cameras; a decoded 16 MP image needs ~64 MB, and at most two
	// decodes run concurrently (avatarSlots), bounding memory against decompression bombs.
	maxAvatarPixels = 16_000_000
)

var avatarSlots = make(chan struct{}, 2)

// handlePutAvatar decodes and re-encodes the image. Re-encoding strips all metadata (EXIF can
// contain the GPS position where a photo was taken) and neutralises malformed files.
func (s *Server) handlePutAvatar(w http.ResponseWriter, r *http.Request) {
	if ok, wait := s.lim.avatar.Allow(principalFrom(r).user.ID.String()); !ok {
		tooMany(w, wait)
		return
	}
	ct := r.Header.Get("Content-Type")
	if ct != "image/jpeg" && ct != "image/png" {
		writeError(w, http.StatusUnsupportedMediaType, "invalid_input", "upload image/jpeg or image/png")
		return
	}
	setReadDeadline(w, 30*time.Second)
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxAvatarUpload))
	if err != nil {
		writeError(w, http.StatusRequestEntityTooLarge, "invalid_input", "image larger than 2 MB")
		return
	}
	cfg, _, err := image.DecodeConfig(bytes.NewReader(body))
	if err != nil || cfg.Width*cfg.Height > maxAvatarPixels || cfg.Width == 0 || cfg.Height == 0 {
		writeError(w, http.StatusBadRequest, "invalid_input", "not a valid image")
		return
	}
	select {
	case avatarSlots <- struct{}{}:
		defer func() { <-avatarSlots }()
	case <-r.Context().Done():
		return
	}
	src, _, err := image.Decode(bytes.NewReader(body))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_input", "not a valid image")
		return
	}
	// Centre square crop, then scale.
	b := src.Bounds()
	side := min(b.Dx(), b.Dy())
	origin := image.Pt(b.Min.X+(b.Dx()-side)/2, b.Min.Y+(b.Dy()-side)/2)
	crop := image.Rectangle{Min: origin, Max: origin.Add(image.Pt(side, side))}
	dst := image.NewRGBA(image.Rect(0, 0, avatarSize, avatarSize))
	draw.CatmullRom.Scale(dst, dst.Bounds(), src, crop, draw.Src, nil)
	var out bytes.Buffer
	if err := jpeg.Encode(&out, dst, &jpeg.Options{Quality: 85}); err != nil {
		s.fail(w, r, err)
		return
	}
	uid := principalFrom(r).user.ID
	if err := s.store.SetAvatar(r.Context(), uid, out.Bytes()); err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyAllFamilies(r.Context(), uid)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleDeleteAvatar(w http.ResponseWriter, r *http.Request) {
	uid := principalFrom(r).user.ID
	if err := s.store.DeleteAvatar(r.Context(), uid); err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyAllFamilies(r.Context(), uid)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleGetAvatar(w http.ResponseWriter, r *http.Request) {
	target, ok := pathID(w, r, "userId")
	if !ok {
		return
	}
	img, updated, err := s.store.Avatar(r.Context(), principalFrom(r).user.ID, target)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "private, max-age=86400")
	w.Header().Set("Last-Modified", updated.UTC().Format(http.TimeFormat))
	// Safe: the bytes are a JPEG re-encoded by us and served as image/jpeg with nosniff.
	_, _ = w.Write(img) //nolint:gosec // G705 false positive
}

func (s *Server) handleListSessions(w http.ResponseWriter, r *http.Request) {
	p := principalFrom(r)
	list, err := s.store.ListSessions(r.Context(), p.user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]sessionDTO, 0, len(list))
	for _, x := range list {
		out = append(out, sessionDTO{x.ID, x.Kind, x.DeviceID, x.DeviceName, x.UserAgent, x.CreatedAt, x.LastUsedAt, x.ID == p.session.ID})
	}
	writeJSON(w, http.StatusOK, map[string]any{"sessions": out})
}

func (s *Server) handleRevokeSession(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "sessionId")
	if !ok {
		return
	}
	if err := s.store.RevokeSession(r.Context(), principalFrom(r).user.ID, id); err != nil {
		s.fail(w, r, err)
		return
	}
	s.hub.CloseSession(id)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleListDevices(w http.ResponseWriter, r *http.Request) {
	list, err := s.store.ListDevices(r.Context(), principalFrom(r).user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]deviceDTO, 0, len(list))
	for _, d := range list {
		out = append(out, deviceDTO{d.ID, d.Name, d.Platform, d.AppVersion, d.CreatedAt, d.LastSeenAt})
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": out})
}

func (s *Server) handleDeleteDevice(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "deviceId")
	if !ok {
		return
	}
	sessions, err := s.store.DeleteDevice(r.Context(), principalFrom(r).user.ID, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	for _, sid := range sessions {
		s.hub.CloseSession(sid)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleExport(w http.ResponseWriter, r *http.Request) {
	ctx, p, now := r.Context(), principalFrom(r), s.now()
	uid := p.user.ID
	settings, err1 := s.store.Settings(ctx, uid)
	families, err2 := s.store.ListFamilies(ctx, uid)
	out, in, err3 := s.store.ListShares(ctx, uid)
	devices, err4 := s.store.ListDevices(ctx, uid)
	sessions, err5 := s.store.ListSessions(ctx, uid)
	latest, err6 := s.store.LatestFor(ctx, uid, uid)
	history, err7 := s.store.History(ctx, uid, time.Unix(0, 0), now.Add(24*time.Hour), 10_000_000)
	sent, err8 := s.store.SentAlerts(ctx, uid)
	if err := errors.Join(err1, err2, err3, err4, err5, err6, err7, err8); err != nil {
		s.fail(w, r, err)
		return
	}
	fams := make([]familySummaryDTO, 0, len(families))
	for _, f := range families {
		fams = append(fams, toFamilySummary(f))
	}
	devs := make([]deviceDTO, 0, len(devices))
	for _, d := range devices {
		devs = append(devs, deviceDTO{d.ID, d.Name, d.Platform, d.AppVersion, d.CreatedAt, d.LastSeenAt})
	}
	sess := make([]sessionDTO, 0, len(sessions))
	for _, x := range sessions {
		sess = append(sess, sessionDTO{x.ID, x.Kind, x.DeviceID, x.DeviceName, x.UserAgent, x.CreatedAt, x.LastUsedAt, x.ID == p.session.ID})
	}
	var latestDTO *locationDTO
	if latest != nil {
		l := toLocation(*latest, now)
		latestDTO = &l
	}
	alerts := make([]alertDTO, 0, len(sent))
	for _, a := range sent {
		alerts = append(alerts, toAlert(a, now))
	}
	hist := make([]historyPointDTO, 0, len(history))
	for _, h := range history {
		hist = append(hist, historyPointDTO{h.ID, h.Suspect, toLocation(h.Location, now)})
	}
	w.Header().Set("Content-Disposition", `attachment; filename="familycircle-export-`+now.Format("2006-01-02")+`.json"`)
	writeJSON(w, http.StatusOK, map[string]any{
		"format":           "familycircle-export/1",
		"exported_at":      now,
		"profile":          toMe(p.user),
		"settings":         toSettings(settings),
		"families":         fams,
		"shares_outgoing":  toShareEntries(out),
		"shares_incoming":  toShareEntries(in),
		"devices":          devs,
		"sessions":         sess,
		"alerts_sent":      alerts,
		"latest_location":  latestDTO,
		"location_history": hist,
	})
}

func (s *Server) handleOwnHistory(w http.ResponseWriter, r *http.Request) {
	from, to, limit, ok := historyParams(w, r, s.now())
	if !ok {
		return
	}
	points, err := s.store.History(r.Context(), principalFrom(r).user.ID, from, to, limit)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeHistory(w, points, s.now())
}

// historyParams parses from/to/limit (default: the last 7 days, 1000 points).
func historyParams(w http.ResponseWriter, r *http.Request, now time.Time) (from, to time.Time, limit int, ok bool) {
	from, to, limit = now.Add(-7*24*time.Hour), now.Add(time.Minute), 1000
	q := r.URL.Query()
	f := fieldErrors{}
	if v := q.Get("from"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		f.check(err == nil, "from", "RFC 3339 timestamp expected")
		from = t
	}
	if v := q.Get("to"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		f.check(err == nil, "to", "RFC 3339 timestamp expected")
		to = t
	}
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		f.check(err == nil && n >= 1 && n <= 5000, "limit", "1–5000")
		limit = n
	}
	if f.write(w) {
		return from, to, limit, false
	}
	return from, to, limit, true
}

func writeHistory(w http.ResponseWriter, points []store.HistoryPoint, now time.Time) {
	out := make([]historyPointDTO, 0, len(points))
	for _, h := range points {
		out = append(out, historyPointDTO{h.ID, h.Suspect, toLocation(h.Location, now)})
	}
	writeJSON(w, http.StatusOK, map[string]any{"points": out})
}

func (s *Server) handleDeleteOwnLocations(w http.ResponseWriter, r *http.Request) {
	uid := principalFrom(r).user.ID
	viewers, err := s.store.ViewersOf(r.Context(), uid)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if err := s.store.DeleteAllLocations(r.Context(), uid); err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyHidden(uid, append(viewers, uid))
	w.WriteHeader(http.StatusNoContent)
}
