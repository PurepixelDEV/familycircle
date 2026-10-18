package api_test

import (
	"testing"
	"time"

	"github.com/coder/websocket"
)

// TestLiveUpdatesRespectRevocation covers the full live path: device upload → hub → viewer's
// WebSocket, and that revocation stops delivery immediately.
func TestLiveUpdatesRespectRevocation(t *testing.T) {
	_, anna, bob, annaDev, bobDev, familyID := family(t)
	_ = familyID
	annaWS := annaDev.dialWS()

	// Without a share Anna receives nothing about Bob.
	bobDev.upload(point(47.0, 8.0, time.Now()))
	annaWS.expectNoLocation(bob.userID, 500*time.Millisecond)

	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	m := annaWS.expect("location", bob.userID) // sent immediately on grant
	if m["location"].(map[string]any)["freshness"] != "live" {
		t.Fatalf("fresh location must be live: %v", m)
	}

	bobDev.upload(point(47.0005, 8.0, time.Now()))
	m = annaWS.expect("location", bob.userID)
	if m["location"].(map[string]any)["lat"].(float64) != 47.0005 {
		t.Fatalf("unexpected location: %v", m)
	}

	bob.must(204, "DELETE", "/shares/"+anna.userID, nil)
	annaWS.expect("location_hidden", bob.userID)
	bobDev.upload(point(47.001, 8.0, time.Now()))
	annaWS.expectNoLocation(bob.userID, 700*time.Millisecond)
}

func TestLivePauseSendsHidden(t *testing.T) {
	_, anna, bob, annaDev, bobDev, _ := family(t)
	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	bobDev.upload(point(47.0, 8.0, time.Now()))
	annaWS := annaDev.dialWS()

	bob.must(200, "PATCH", "/me/settings", map[string]any{"sharing_paused": true})
	annaWS.expect("location_hidden", bob.userID)
	bobDev.upload(point(47.0003, 8.0, time.Now()))
	annaWS.expectNoLocation(bob.userID, 500*time.Millisecond)

	bob.must(200, "PATCH", "/me/settings", map[string]any{"sharing_paused": false})
	annaWS.expect("location", bob.userID)
}

func TestRevokedSessionLosesLiveConnection(t *testing.T) {
	_, anna, _, annaDev, _, _ := family(t)
	ws := annaDev.dialWS()
	sessions := anna.must(200, "GET", "/me/sessions", nil).body["sessions"].([]any)
	for _, s := range sessions {
		sm := s.(map[string]any)
		if sm["kind"] == "device" {
			anna.must(204, "DELETE", "/me/sessions/"+sm["id"].(string), nil)
		}
	}
	status, closed := ws.waitClosed(3 * time.Second)
	if !closed {
		t.Fatal("WebSocket of a revoked session must be closed")
	}
	if status != websocket.StatusPolicyViolation {
		t.Fatalf("close status %v", status)
	}
}

func TestOwnUploadIsEchoedToOwnSessions(t *testing.T) {
	_, anna, _, annaDev, _, _ := family(t)
	ws := annaDev.dialWS()
	annaDev.upload(point(47.0, 8.0, time.Now()))
	m := ws.expect("location", anna.userID)
	if m["location"].(map[string]any)["battery_pct"] == nil {
		t.Fatal("owner sees own battery")
	}
}
