package api

import (
	"time"

	"github.com/google/uuid"

	"familycircle/internal/geo"
	"familycircle/internal/store"
)

// JSON shapes of the API (see openapi.yaml components/schemas).

type meDTO struct {
	ID              uuid.UUID `json:"id"`
	Email           string    `json:"email"`
	DisplayName     string    `json:"display_name"`
	Locale          string    `json:"locale"`
	IsInstanceAdmin bool      `json:"is_instance_admin"`
	HasAvatar       bool      `json:"has_avatar"`
	AvatarVersion   *int64    `json:"avatar_version"`
	CreatedAt       time.Time `json:"created_at"`
}

func toMe(u store.User) meDTO {
	return meDTO{ID: u.ID, Email: u.Email, DisplayName: u.DisplayName, Locale: u.Locale, IsInstanceAdmin: u.IsInstanceAdmin,
		HasAvatar: u.AvatarUpdatedAt != nil, AvatarVersion: store.AvatarVersion(u.AvatarUpdatedAt), CreatedAt: u.CreatedAt}
}

type settingsDTO struct {
	SharingPaused        bool `json:"sharing_paused"`
	HistoryEnabled       bool `json:"history_enabled"`
	HistoryRetentionDays int  `json:"history_retention_days"`
	ShareBattery         bool `json:"share_battery"`
	AllowRing            bool `json:"allow_ring"`
	AllowLocateRequest   bool `json:"allow_locate_request"`
	BatteryAlerts        bool `json:"battery_alerts"`
}

func toSettings(s store.Settings) settingsDTO {
	return settingsDTO{s.SharingPaused, s.HistoryEnabled, s.HistoryRetentionDays, s.ShareBattery,
		s.AllowRing, s.AllowLocateRequest, s.BatteryAlerts}
}

type sessionDTO struct {
	ID         uuid.UUID  `json:"id"`
	Kind       string     `json:"kind"`
	DeviceID   *uuid.UUID `json:"device_id"`
	DeviceName *string    `json:"device_name"`
	UserAgent  *string    `json:"user_agent"`
	CreatedAt  time.Time  `json:"created_at"`
	LastUsedAt time.Time  `json:"last_used_at"`
	Current    bool       `json:"current"`
}

type deviceDTO struct {
	ID         uuid.UUID  `json:"id"`
	Name       string     `json:"name"`
	Platform   string     `json:"platform"`
	AppVersion *string    `json:"app_version"`
	CreatedAt  time.Time  `json:"created_at"`
	LastSeenAt *time.Time `json:"last_seen_at"`
}

type familySummaryDTO struct {
	ID          uuid.UUID `json:"id"`
	Name        string    `json:"name"`
	Role        string    `json:"role"`
	MemberCount int       `json:"member_count"`
}

func toFamilySummary(f store.FamilySummary) familySummaryDTO {
	return familySummaryDTO{f.ID, f.Name, f.Role, f.MemberCount}
}

type memberDTO struct {
	UserID        uuid.UUID `json:"user_id"`
	DisplayName   string    `json:"display_name"`
	Role          string    `json:"role"`
	Color         string    `json:"color"`
	HasAvatar     bool      `json:"has_avatar"`
	AvatarVersion *int64    `json:"avatar_version"`
	JoinedAt      time.Time `json:"joined_at"`
	SharesWithMe  bool      `json:"shares_with_me"`
	IShareWith    bool      `json:"i_share_with"`
}

type familyDTO struct {
	ID      uuid.UUID   `json:"id"`
	Name    string      `json:"name"`
	Role    string      `json:"role"`
	Members []memberDTO `json:"members"`
}

func toFamily(f store.Family) familyDTO {
	out := familyDTO{ID: f.ID, Name: f.Name, Role: f.Role, Members: make([]memberDTO, 0, len(f.Members))}
	for _, m := range f.Members {
		out.Members = append(out.Members, memberDTO{m.UserID, m.DisplayName, m.Role, m.Color, m.AvatarUpdatedAt != nil,
			store.AvatarVersion(m.AvatarUpdatedAt), m.JoinedAt, m.SharesWithMe, m.IShareWith})
	}
	return out
}

type inviteDTO struct {
	ID        uuid.UUID `json:"id"`
	CodeHint  string    `json:"code_hint"`
	MaxUses   int       `json:"max_uses"`
	UseCount  int       `json:"use_count"`
	ExpiresAt time.Time `json:"expires_at"`
	CreatedAt time.Time `json:"created_at"`
	Code      string    `json:"code,omitempty"`
}

func toInvite(i store.Invite) inviteDTO {
	return inviteDTO{ID: i.ID, CodeHint: i.CodeHint, MaxUses: i.MaxUses, UseCount: i.UseCount, ExpiresAt: i.ExpiresAt, CreatedAt: i.CreatedAt}
}

type shareEntryDTO struct {
	UserID      uuid.UUID `json:"user_id"`
	DisplayName string    `json:"display_name"`
	Since       time.Time `json:"since"`
	History     bool      `json:"history"`
}

func toShareEntries(in []store.ShareEntry) []shareEntryDTO {
	out := make([]shareEntryDTO, 0, len(in))
	for _, e := range in {
		out = append(out, shareEntryDTO{e.UserID, e.DisplayName, e.Since, e.History})
	}
	return out
}

type locationDTO struct {
	Lat             float64       `json:"lat"`
	Lon             float64       `json:"lon"`
	AccuracyM       float64       `json:"accuracy_m"`
	AltitudeM       *float64      `json:"altitude_m"`
	SpeedMps        *float64      `json:"speed_mps"`
	BearingDeg      *float64      `json:"bearing_deg"`
	BatteryPct      *int          `json:"battery_pct"`
	BatteryCharging *bool         `json:"battery_charging"`
	Motion          string        `json:"motion"`
	RecordedAt      time.Time     `json:"recorded_at"`
	ReceivedAt      time.Time     `json:"received_at"`
	Freshness       geo.Freshness `json:"freshness"`
}

func toLocation(l store.Location, now time.Time) locationDTO {
	return locationDTO{l.Lat, l.Lon, l.AccuracyM, l.AltitudeM, l.SpeedMps, l.BearingDeg, l.BatteryPct, l.BatteryCharging,
		l.Motion, l.RecordedAt, l.ReceivedAt, geo.FreshnessOf(l.RecordedAt, now)}
}

type historyPointDTO struct {
	ID      uuid.UUID `json:"id"`
	Suspect bool      `json:"suspect"`
	locationDTO
}

type mapMemberDTO struct {
	UserID        uuid.UUID    `json:"user_id"`
	DisplayName   string       `json:"display_name"`
	Color         string       `json:"color"`
	HasAvatar     bool         `json:"has_avatar"`
	AvatarVersion *int64       `json:"avatar_version"`
	IsSelf        bool         `json:"is_self"`
	Location      *locationDTO `json:"location"`
}

type familyMapDTO struct {
	FamilyID   uuid.UUID      `json:"family_id"`
	ServerTime time.Time      `json:"server_time"`
	Members    []mapMemberDTO `json:"members"`
}
