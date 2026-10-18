package api

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"familycircle/internal/auth"
	"familycircle/internal/store"
)

func (s *Server) handleListFamilies(w http.ResponseWriter, r *http.Request) {
	list, err := s.store.ListFamilies(r.Context(), principalFrom(r).user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]familySummaryDTO, 0, len(list))
	for _, f := range list {
		out = append(out, toFamilySummary(f))
	}
	writeJSON(w, http.StatusOK, map[string]any{"families": out})
}

func decodeFamilyName(w http.ResponseWriter, r *http.Request) (string, bool) {
	var in struct {
		Name string `json:"name"`
	}
	if !decode(w, r, &in, 0) {
		return "", false
	}
	in.Name = cleanName(in.Name)
	f := fieldErrors{}
	f.check(runeLen(in.Name) >= 1 && runeLen(in.Name) <= 64, "name", "1–64 characters")
	return in.Name, !f.write(w)
}

// handleCreateFamily is restricted to the instance admin: whoever creates a family can invite
// new accounts, so open family creation would turn "invite only" into open registration.
func (s *Server) handleCreateFamily(w http.ResponseWriter, r *http.Request) {
	if !principalFrom(r).user.IsInstanceAdmin {
		writeError(w, http.StatusForbidden, "forbidden", "only the instance administrator can create families")
		return
	}
	name, ok := decodeFamilyName(w, r)
	if !ok {
		return
	}
	uid := principalFrom(r).user.ID
	id, err := s.store.CreateFamily(r.Context(), uid, name)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.writeFamily(w, r, id, http.StatusCreated)
}

func (s *Server) writeFamily(w http.ResponseWriter, r *http.Request, id uuid.UUID, status int) {
	f, err := s.store.FamilyForMember(r.Context(), id, principalFrom(r).user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, status, toFamily(f))
}

func (s *Server) handleGetFamily(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "familyId")
	if !ok {
		return
	}
	s.writeFamily(w, r, id, http.StatusOK)
}

func (s *Server) handleRenameFamily(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "familyId")
	if !ok {
		return
	}
	name, ok := decodeFamilyName(w, r)
	if !ok {
		return
	}
	if err := s.store.RenameFamily(r.Context(), id, principalFrom(r).user.ID, name); err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyFamilyChanged(r.Context(), id)
	s.writeFamily(w, r, id, http.StatusOK)
}

func (s *Server) handleLeaveFamily(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "familyId")
	if !ok {
		return
	}
	ended, err := s.store.LeaveFamily(r.Context(), id, principalFrom(r).user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyEndedShares(ended)
	s.notifyFamilyChanged(r.Context(), id)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleUpdateMember(w http.ResponseWriter, r *http.Request) {
	familyID, ok := pathID(w, r, "familyId")
	if !ok {
		return
	}
	target, ok := pathID(w, r, "userId")
	if !ok {
		return
	}
	var in struct {
		Role string `json:"role"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	if in.Role != "admin" && in.Role != "member" {
		writeFieldErrors(w, http.StatusBadRequest, "invalid_input", "invalid input", map[string]string{"role": "admin or member"})
		return
	}
	if err := s.store.SetMemberRole(r.Context(), familyID, principalFrom(r).user.ID, target, in.Role); err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyFamilyChanged(r.Context(), familyID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleRemoveMember(w http.ResponseWriter, r *http.Request) {
	familyID, ok := pathID(w, r, "familyId")
	if !ok {
		return
	}
	target, ok := pathID(w, r, "userId")
	if !ok {
		return
	}
	ended, err := s.store.RemoveMember(r.Context(), familyID, principalFrom(r).user.ID, target)
	if errors.Is(err, store.ErrConflict) {
		writeError(w, http.StatusConflict, "conflict", "use the leave endpoint to leave a family yourself")
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyEndedShares(ended)
	s.notifyFamilyChanged(r.Context(), familyID)
	s.hub.SendTo([]uuid.UUID{target}, mustJSON(familyEvent{"family_changed", familyID}))
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleFamilyMap(w http.ResponseWriter, r *http.Request) {
	familyID, ok := pathID(w, r, "familyId")
	if !ok {
		return
	}
	members, err := s.store.FamilyMap(r.Context(), familyID, principalFrom(r).user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	now := s.now()
	out := familyMapDTO{FamilyID: familyID, ServerTime: now, Members: make([]mapMemberDTO, 0, len(members))}
	for _, m := range members {
		dto := mapMemberDTO{UserID: m.UserID, DisplayName: m.DisplayName, Color: m.Color, HasAvatar: m.AvatarUpdatedAt != nil,
			AvatarVersion: store.AvatarVersion(m.AvatarUpdatedAt), IsSelf: m.IsSelf}
		if m.Location != nil {
			l := toLocation(*m.Location, now)
			dto.Location = &l
		}
		out.Members = append(out.Members, dto)
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleListInvites(w http.ResponseWriter, r *http.Request) {
	familyID, ok := pathID(w, r, "familyId")
	if !ok {
		return
	}
	list, err := s.store.ListInvites(r.Context(), familyID, principalFrom(r).user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]inviteDTO, 0, len(list))
	for _, i := range list {
		out = append(out, toInvite(i))
	}
	writeJSON(w, http.StatusOK, map[string]any{"invites": out})
}

func (s *Server) handleCreateInvite(w http.ResponseWriter, r *http.Request) {
	familyID, ok := pathID(w, r, "familyId")
	if !ok {
		return
	}
	in := struct {
		ExpiresInHours int `json:"expires_in_hours"`
		MaxUses        int `json:"max_uses"`
	}{72, 1}
	if r.ContentLength != 0 && !decode(w, r, &in, 0) {
		return
	}
	f := fieldErrors{}
	f.check(in.ExpiresInHours >= 1 && in.ExpiresInHours <= 168, "expires_in_hours", "1–168")
	f.check(in.MaxUses >= 1 && in.MaxUses <= 20, "max_uses", "1–20")
	if f.write(w) {
		return
	}
	inv, code, err := s.store.CreateInvite(r.Context(), familyID, principalFrom(r).user.ID, time.Duration(in.ExpiresInHours)*time.Hour, in.MaxUses)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	dto := toInvite(inv)
	dto.Code = code
	writeJSON(w, http.StatusCreated, dto)
}

func (s *Server) handleRevokeInvite(w http.ResponseWriter, r *http.Request) {
	familyID, ok := pathID(w, r, "familyId")
	if !ok {
		return
	}
	inviteID, ok := pathID(w, r, "inviteId")
	if !ok {
		return
	}
	if err := s.store.RevokeInvite(r.Context(), familyID, principalFrom(r).user.ID, inviteID); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleAcceptInvite(w http.ResponseWriter, r *http.Request) {
	if ok, wait := s.lim.sensitive.Allow(s.clientIP(r)); !ok {
		tooMany(w, wait)
		return
	}
	var in struct {
		Code string `json:"code"`
	}
	if !decode(w, r, &in, 0) {
		return
	}
	if strings.TrimSpace(in.Code) == "" {
		writeFieldErrors(w, http.StatusBadRequest, "invalid_input", "invalid input", map[string]string{"code": "required"})
		return
	}
	fam, err := s.store.AcceptInvite(r.Context(), principalFrom(r).user.ID, auth.HashCode(in.Code))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyFamilyChanged(r.Context(), fam.ID)
	writeJSON(w, http.StatusOK, toFamilySummary(fam))
}

func (s *Server) handleListShares(w http.ResponseWriter, r *http.Request) {
	out, in, err := s.store.ListShares(r.Context(), principalFrom(r).user.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"outgoing": toShareEntries(out), "incoming": toShareEntries(in)})
}

func (s *Server) handleGrantShare(w http.ResponseWriter, r *http.Request) {
	viewer, ok := pathID(w, r, "userId")
	if !ok {
		return
	}
	owner := principalFrom(r).user.ID
	if err := s.store.GrantShare(r.Context(), owner, viewer); err != nil {
		s.fail(w, r, err)
		return
	}
	s.sendLocationTo(r.Context(), owner, viewer)
	s.notifyAllFamilies(r.Context(), owner)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleRevokeShare(w http.ResponseWriter, r *http.Request) {
	viewer, ok := pathID(w, r, "userId")
	if !ok {
		return
	}
	owner := principalFrom(r).user.ID
	if err := s.store.RevokeShare(r.Context(), owner, viewer); err != nil {
		s.fail(w, r, err)
		return
	}
	s.notifyHidden(owner, []uuid.UUID{viewer})
	s.notifyAllFamilies(r.Context(), owner)
	w.WriteHeader(http.StatusNoContent)
}
