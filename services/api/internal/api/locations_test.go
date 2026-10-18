package api_test

import (
	"bytes"
	"testing"
	"time"
)

func TestUploadRequiresDeviceSession(t *testing.T) {
	_, anna, _, _, _, _ := family(t)
	r := anna.do("POST", "/locations", map[string]any{"points": []any{point(47, 8, time.Now())}})
	if r.status != 403 || r.errCode() != "device_session_required" {
		t.Fatalf("web session upload: %d %s", r.status, r.raw)
	}
	anon := &client{e: anna.e, http: anna.http, bearer: "fca_invalid"}
	if r := anon.do("POST", "/locations", map[string]any{"points": []any{point(47, 8, time.Now())}}); r.status != 401 {
		t.Fatalf("invalid token upload: %d", r.status)
	}
}

func TestUploadCannotImpersonateOtherUser(t *testing.T) {
	_, anna, bob, annaDev, _, familyID := family(t)
	p := point(47.5, 8.7, time.Now())
	p["user_id"] = bob.userID // ignored: identity comes from the token only
	body := map[string]any{"points": []any{p}, "user_id": bob.userID}
	annaDev.must(200, "POST", "/locations", body)

	if bob.seesLocationOf(familyID, bob.userID) {
		t.Fatal("location was stored under Bob's identity")
	}
	if !anna.seesLocationOf(familyID, anna.userID) {
		t.Fatal("location must be stored for the token owner (Anna)")
	}
}

func TestUploadValidationAndIdempotency(t *testing.T) {
	_, anna, _, annaDev, _, _ := family(t)
	anna.must(200, "PATCH", "/me/settings", map[string]any{"history_enabled": true})

	now := time.Now()
	good := point(47.37, 8.54, now.Add(-time.Minute))
	bad := point(91, 8.54, now)
	future := point(47, 8, now.Add(time.Hour))
	old := point(47, 8, now.Add(-8*24*time.Hour))
	r := annaDev.upload(good, bad, future, old, good) // duplicate id inside batch
	if acc := r.body["accepted"].([]any); len(acc) != 1 {
		t.Fatalf("accepted: %v", acc)
	}
	reasons := map[string]string{}
	for _, x := range r.body["rejected"].([]any) {
		m := x.(map[string]any)
		reasons[m["id"].(string)] = m["reason"].(string)
	}
	if reasons[bad["id"].(string)] != "invalid" || reasons[future["id"].(string)] != "in_future" || reasons[old["id"].(string)] != "too_old" {
		t.Fatalf("rejections: %v", reasons)
	}

	// Re-sending the same batch (e.g. after a lost response) does not duplicate history.
	annaDev.upload(good)
	h := anna.must(200, "GET", "/me/locations", nil)
	if n := len(h.body["points"].([]any)); n != 1 {
		t.Fatalf("history has %d points, want 1", n)
	}
}

func TestHistoryIsOptIn(t *testing.T) {
	_, anna, _, annaDev, _, familyID := family(t)
	annaDev.upload(point(47.37, 8.54, time.Now().Add(-2*time.Minute)), point(47.371, 8.541, time.Now()))
	if n := len(anna.must(200, "GET", "/me/locations", nil).body["points"].([]any)); n != 0 {
		t.Fatalf("history stored without opt-in: %d points", n)
	}
	if !anna.seesLocationOf(familyID, anna.userID) {
		t.Fatal("latest position is always kept")
	}
	anna.must(200, "PATCH", "/me/settings", map[string]any{"history_enabled": true})
	annaDev.upload(point(47.372, 8.542, time.Now()))
	if n := len(anna.must(200, "GET", "/me/locations", nil).body["points"].([]any)); n != 1 {
		t.Fatalf("history after opt-in: %d", n)
	}
	// Opting out deletes the stored history.
	anna.must(200, "PATCH", "/me/settings", map[string]any{"history_enabled": false})
	if n := len(anna.must(200, "GET", "/me/locations", nil).body["points"].([]any)); n != 0 {
		t.Fatalf("history not deleted on opt-out: %d", n)
	}
}

func TestOfflineBatchKeepsNewestAndMarksStale(t *testing.T) {
	_, anna, bob, _, bobDev, familyID := family(t)
	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	now := time.Now()
	// Offline queue flushed out of order: newest point must win.
	bobDev.upload(point(47.002, 8.0, now.Add(-20*time.Minute)), point(47.0, 8.0, now.Add(-40*time.Minute)), point(47.001, 8.0, now.Add(-30*time.Minute)))
	loc := anna.mapMember(familyID, bob.userID)["location"].(map[string]any)
	if loc["lat"].(float64) != 47.002 {
		t.Fatalf("newest point must be the latest: %v", loc)
	}
	if loc["freshness"] != "stale" {
		t.Fatalf("a 20 minute old fix must be stale, never live: %v", loc["freshness"])
	}
	// An older point arriving later must not overwrite the newer latest position.
	bobDev.upload(point(46.0, 8.0, now.Add(-50*time.Minute)))
	if anna.mapMember(familyID, bob.userID)["location"].(map[string]any)["lat"].(float64) != 47.002 {
		t.Fatal("older point overwrote newer latest")
	}
	bobDev.upload(point(47.003, 8.0, now))
	if f := anna.mapMember(familyID, bob.userID)["location"].(map[string]any)["freshness"]; f != "live" {
		t.Fatalf("fresh fix must be live, got %v", f)
	}
}

func TestGPSJumpIsFlaggedAndConfirmed(t *testing.T) {
	_, anna, _, annaDev, _, familyID := family(t)
	anna.must(200, "PATCH", "/me/settings", map[string]any{"history_enabled": true})
	now := time.Now().Add(-time.Minute)
	annaDev.upload(point(47.0, 8.0, now))
	// 100 km in 5 seconds: a GPS glitch. Latest must not move.
	annaDev.upload(point(47.9, 8.0, now.Add(5*time.Second)))
	if lat := anna.mapMember(familyID, anna.userID)["location"].(map[string]any)["lat"].(float64); lat != 47.0 {
		t.Fatalf("jump moved latest to %v", lat)
	}
	pts := anna.must(200, "GET", "/me/locations", nil).body["points"].([]any)
	if pts[0].(map[string]any)["suspect"] != true {
		t.Fatalf("jump must be flagged suspect: %v", pts[0])
	}
	// A second fix agreeing with the new place confirms a real move (e.g. after a flight).
	annaDev.upload(point(47.9001, 8.0, now.Add(35*time.Second)))
	if lat := anna.mapMember(familyID, anna.userID)["location"].(map[string]any)["lat"].(float64); lat != 47.9001 {
		t.Fatalf("confirmed jump must update latest, got %v", lat)
	}
}

func TestDeleteAllOwnLocationsAndExport(t *testing.T) {
	_, anna, _, annaDev, _, familyID := family(t)
	anna.must(200, "PATCH", "/me/settings", map[string]any{"history_enabled": true})
	annaDev.upload(point(47.37, 8.54, time.Now()))

	exp := anna.must(200, "GET", "/me/export", nil)
	if exp.body["latest_location"] == nil || len(exp.body["location_history"].([]any)) != 1 || exp.str("profile", "email") != "anna@example.org" {
		t.Fatalf("export incomplete: %s", exp.raw)
	}
	for _, secret := range []string{"password_hash", "argon2", "access_hash"} {
		if bytes.Contains(exp.raw, []byte(secret)) {
			t.Fatalf("export leaks %s", secret)
		}
	}

	anna.must(204, "DELETE", "/me/locations", nil)
	if anna.seesLocationOf(familyID, anna.userID) {
		t.Fatal("latest location not deleted")
	}
	if n := len(anna.must(200, "GET", "/me/locations", nil).body["points"].([]any)); n != 0 {
		t.Fatal("history not deleted")
	}
}
