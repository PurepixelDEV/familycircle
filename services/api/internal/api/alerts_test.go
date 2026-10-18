package api_test

import (
	"fmt"
	"testing"
	"time"
)

// alerts returns GET /alerts for the client.
func (c *client) alerts() []map[string]any {
	c.e.t.Helper()
	r := c.must(200, "GET", "/alerts", nil)
	var out []map[string]any
	for _, a := range r.body["alerts"].([]any) {
		out = append(out, a.(map[string]any))
	}
	return out
}

func kinds(alerts []map[string]any) []string {
	out := []string{}
	for _, a := range alerts {
		k := a["kind"].(string)
		if a["mine"] == true {
			k += "(mine)"
		}
		out = append(out, k)
	}
	return out
}

func (w *wsConn) expectAlert(kind string) map[string]any {
	w.t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		m := w.next(time.Until(deadline))
		if m == nil {
			break
		}
		if m["type"] == "alert" && m["alert"].(map[string]any)["kind"] == kind {
			return m["alert"].(map[string]any)
		}
	}
	w.t.Fatalf("did not receive alert %s", kind)
	return nil
}

func (w *wsConn) expectNoAlert(d time.Duration) {
	w.t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		m := w.next(time.Until(deadline))
		if m == nil {
			return
		}
		if m["type"] == "alert" {
			w.t.Fatalf("unexpected alert: %v", m)
		}
	}
}

// outsider creates Carol in a family that shares nobody with Anna's family.
func outsider(t *testing.T, e *env, admin *client) *client {
	t.Helper()
	r := admin.must(201, "POST", "/families", map[string]any{"name": "Elsewhere"})
	carol := e.register(e.invite(admin, r.str("id"), 1), "Carol")
	admin.must(204, "PATCH", "/families/"+r.str("id")+"/members/"+carol.userID, map[string]any{"role": "admin"})
	admin.must(204, "POST", "/families/"+r.str("id")+"/leave", nil)
	return carol
}

func TestSOSReachesFamilyWithoutSharing(t *testing.T) {
	e, anna, bob, annaDev, bobDev, _ := family(t)
	carol := outsider(t, e, anna)
	carolDev := e.device("Carol")
	annaWS, carolWS := annaDev.dialWS(), carolDev.dialWS()

	// No location share at all: SOS is an explicit action for the whole family.
	r := bobDev.must(201, "POST", "/sos", map[string]any{"lat": 47.1, "lon": 8.2, "accuracy_m": 12})
	if r.body["recipients"].(float64) != 1 {
		t.Fatalf("recipients: %v", r.body)
	}
	// Regression: the response is the caller's own copy.
	if own := r.body["alert"].(map[string]any); own["mine"] != true || own["active"] != true {
		t.Fatalf("POST /sos must return the own copy (mine): %v", own)
	}
	a := annaWS.expectAlert("sos")
	if a["actor"].(map[string]any)["display_name"] != "Bob" || a["active"] != true || a["location"].(map[string]any)["lat"] != 47.1 {
		t.Fatalf("unexpected alert %v", a)
	}
	carolWS.expectNoAlert(400 * time.Millisecond)
	if got := kinds(carol.alerts()); len(got) != 0 {
		t.Fatalf("Carol (other family) must not see the SOS: %v", got)
	}
	// Bob's own devices know that his SOS is active.
	if got := kinds(bob.alerts()); fmt.Sprint(got) != "[sos(mine)]" {
		t.Fatalf("Bob's own alerts: %v", got)
	}

	bob.must(200, "POST", "/sos/resolve", nil)
	annaWS.expectAlert("sos_resolved")
	list := anna.alerts()
	if fmt.Sprint(kinds(list)) != "[sos sos_resolved]" || list[0]["active"] != false || list[0]["resolved_at"] == nil {
		t.Fatalf("after resolve: %v", list)
	}
	// Resolving again is a no-op and sends nothing.
	if r := bob.must(200, "POST", "/sos/resolve", nil); r.body["resolved"].(float64) != 0 {
		t.Fatalf("second resolve: %v", r.body)
	}
}

func TestSOSWithoutPositionUsesLatest(t *testing.T) {
	_, anna, _, _, bobDev, _ := family(t)
	bobDev.upload(point(46.5, 7.5, time.Now()))
	bobDev.must(201, "POST", "/sos", nil)
	a := anna.alerts()[0]
	if a["location"].(map[string]any)["lat"] != 46.5 {
		t.Fatalf("SOS must fall back to the latest position: %v", a)
	}
	bobDev.must(400, "POST", "/sos", map[string]any{"lat": 91, "lon": 8})
}

func TestCheckInAttachesPlaceAndValidatesMessage(t *testing.T) {
	_, anna, bob, _, _, familyID := family(t)
	anna.must(201, "POST", "/families/"+familyID+"/places", map[string]any{"name": "Schule", "lat": 47.0, "lon": 8.0, "radius_m": 150})
	bob.must(201, "POST", "/check-ins", map[string]any{"lat": 47.0005, "lon": 8.0, "accuracy_m": 10, "message": "  Bin da  "})
	a := anna.alerts()[0]
	if a["kind"] != "check_in" || a["message"] != "Bin da" || a["place"].(map[string]any)["name"] != "Schule" {
		t.Fatalf("check-in: %v", a)
	}
	long := make([]rune, 141)
	for i := range long {
		long[i] = 'x'
	}
	bob.must(400, "POST", "/check-ins", map[string]any{"lat": 47, "lon": 8, "message": string(long)})
	bob.must(400, "POST", "/check-ins", map[string]any{"message": "no position"})
}

func TestBatteryAlertOnlyForViewersAndDeduplicated(t *testing.T) {
	_, anna, bob, _, bobDev, _ := family(t)
	bobDev.upload(point(47.2, 8.3, time.Now()))

	// Nobody can see Bob: the alert has no recipients.
	r := bobDev.must(201, "POST", "/battery-alerts", map[string]any{"reason": "low", "battery_pct": 9})
	if r.body["recipients"].(float64) != 0 || len(anna.alerts()) != 0 {
		t.Fatalf("battery alert must only reach viewers: %v", r.body)
	}
	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	// Deduplicated for 6 h.
	if r := bobDev.must(200, "POST", "/battery-alerts", map[string]any{"reason": "low", "battery_pct": 8}); r.body["sent"] != false {
		t.Fatalf("must be deduplicated: %v", r.body)
	}
	bobDev.must(201, "POST", "/battery-alerts", map[string]any{"reason": "shutdown", "battery_pct": 2})
	a := anna.alerts()[0]
	if a["kind"] != "last_location" || a["battery_pct"].(float64) != 2 || a["location"].(map[string]any)["lat"] != 47.2 {
		t.Fatalf("last location: %v", a)
	}

	// Revoking the share hides the older alert as well (immediate revocation).
	bob.must(204, "DELETE", "/shares/"+anna.userID, nil)
	if got := anna.alerts(); len(got) != 0 {
		t.Fatalf("revoked share must hide older alerts: %v", got)
	}

	// Switched off by the user: nothing is sent.
	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	bob.must(200, "PATCH", "/me/settings", map[string]any{"battery_alerts": false})
	if r := bobDev.must(200, "POST", "/battery-alerts", map[string]any{"reason": "low", "battery_pct": 5}); r.body["sent"] != false {
		t.Fatalf("disabled battery alerts: %v", r.body)
	}
	// Web sessions cannot report battery state (device only).
	bob.must(403, "POST", "/battery-alerts", map[string]any{"reason": "low", "battery_pct": 5})
	bobDev.must(400, "POST", "/battery-alerts", map[string]any{"reason": "empty", "battery_pct": 5})
}

func TestRingAndLocateNeedOptIn(t *testing.T) {
	e, anna, bob, annaDev, bobDev, _ := family(t)
	carol := outsider(t, e, anna)
	bobWS := bobDev.dialWS()

	// Default off.
	if r := anna.do("POST", "/users/"+bob.userID+"/ring", nil); r.status != 403 {
		t.Fatalf("ring without opt-in: %d", r.status)
	}
	// Not in a common family: 404, nothing revealed.
	if r := carol.do("POST", "/users/"+bob.userID+"/ring", nil); r.status != 404 {
		t.Fatalf("ring from outsider: %d", r.status)
	}
	bob.must(200, "PATCH", "/me/settings", map[string]any{"allow_ring": true})
	r := anna.must(202, "POST", "/users/"+bob.userID+"/ring", nil)
	if r.body["online"] != true {
		t.Fatalf("Bob's phone is connected: %v", r.body)
	}
	a := bobWS.expectAlert("ring")
	if a["active"] != true || a["actor"].(map[string]any)["user_id"] != anna.userID {
		t.Fatalf("ring alert: %v", a)
	}

	// Locate: opt-in AND Bob's location must be visible to Anna.
	bob.must(200, "PATCH", "/me/settings", map[string]any{"allow_locate_request": true})
	if r := anna.do("POST", "/users/"+bob.userID+"/locate", nil); r.status != 403 {
		t.Fatalf("locate without share: %d", r.status)
	}
	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	anna.must(202, "POST", "/users/"+bob.userID+"/locate", nil)
	bobWS.expectAlert("locate")

	// Own devices: always allowed (find my phone), the requesting device is identified.
	r = annaDev.must(202, "POST", "/users/"+anna.userID+"/ring", nil)
	own := anna.alerts()
	if len(own) != 1 || own[0]["kind"] != "ring" || own[0]["from_device_id"] != annaDev.device {
		t.Fatalf("own ring: %v %v", r.body, own)
	}
}

func TestPlacesArrivalDepartureWithHysteresis(t *testing.T) {
	_, anna, bob, annaDev, bobDev, familyID := family(t)
	bobDev.upload(point(47.0, 8.0, time.Now().Add(-10*time.Minute))) // inside, before the place exists
	p := bob.must(201, "POST", "/families/"+familyID+"/places", map[string]any{"name": "Zuhause", "lat": 47.0, "lon": 8.0, "radius_m": 100})
	placeID := p.str("id")
	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	annaWS := annaDev.dialWS()

	at := time.Now().Add(-9 * time.Minute)
	step := func(lat float64) {
		at = at.Add(time.Minute)
		bobDev.upload(point(lat, 8.0, at))
	}
	// First evaluation only records the state: no alert for being inside a new place.
	step(47.0001)
	annaWS.expectNoAlert(300 * time.Millisecond)
	// Jitter just outside the radius (≈ 120 m < radius + 50 m) keeps the state.
	step(47.0011)
	annaWS.expectNoAlert(300 * time.Millisecond)
	// Clearly outside (≈ 330 m): departure.
	step(47.003)
	if a := annaWS.expectAlert("departure"); a["place"].(map[string]any)["name"] != "Zuhause" {
		t.Fatalf("departure: %v", a)
	}
	// Back: arrival.
	step(47.0002)
	annaWS.expectAlert("arrival")

	// Presence is shown to Anna (she can see Bob) …
	places := anna.must(200, "GET", "/families/"+familyID+"/places", nil).body["places"].([]any)
	if present := places[0].(map[string]any)["present"].([]any); len(present) != 1 || present[0] != bob.userID {
		t.Fatalf("presence: %v", places)
	}
	// … but not after the share is revoked; and no more alerts.
	bob.must(204, "DELETE", "/shares/"+anna.userID, nil)
	places = anna.must(200, "GET", "/families/"+familyID+"/places", nil).body["places"].([]any)
	if present := places[0].(map[string]any)["present"].([]any); len(present) != 0 {
		t.Fatalf("presence must follow visibility: %v", places)
	}
	step(47.003)
	annaWS.expectNoAlert(300 * time.Millisecond)

	// Muted places send nothing even with a share.
	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	anna.must(204, "PUT", "/places/"+placeID+"/mute", nil)
	step(47.0)
	annaWS.expectNoAlert(300 * time.Millisecond)
	anna.must(204, "DELETE", "/places/"+placeID+"/mute", nil)
	step(47.003)
	annaWS.expectAlert("departure")

	// Imprecise fixes (> 500 m) never change presence.
	imprecise := point(47.0, 8.0, at.Add(time.Minute))
	imprecise["accuracy_m"] = 800.0
	bobDev.upload(imprecise)
	annaWS.expectNoAlert(300 * time.Millisecond)
}

func TestPlacePermissions(t *testing.T) {
	e, anna, bob, _, _, familyID := family(t)
	carol := outsider(t, e, anna)
	p := bob.must(201, "POST", "/families/"+familyID+"/places", map[string]any{"name": "Bob's gym", "lat": 47.0, "lon": 8.0, "radius_m": 80})
	id := p.str("id")

	carol.must(404, "GET", "/families/"+familyID+"/places", nil)
	carol.must(404, "PATCH", "/places/"+id, map[string]any{"name": "x"})
	carol.must(404, "PUT", "/places/"+id+"/mute", nil)

	// Creator and admins may edit; other members may not.
	dora := e.register(e.invite(anna, familyID, 1), "Dora")
	dora.must(403, "PATCH", "/places/"+id, map[string]any{"name": "mine now"})
	dora.must(403, "DELETE", "/places/"+id, nil)
	anna.must(204, "PATCH", "/places/"+id, map[string]any{"name": "Fitness", "radius_m": 120})
	bob.must(400, "PATCH", "/places/"+id, map[string]any{"radius_m": 10})
	bob.must(400, "PATCH", "/places/"+id, map[string]any{"lat": 47.1}) // lat without lon
	bob.must(400, "POST", "/families/"+familyID+"/places", map[string]any{"name": "", "lat": 47, "lon": 8, "radius_m": 100})
	bob.must(204, "DELETE", "/places/"+id, nil)
	bob.must(404, "DELETE", "/places/"+id, nil)

	for i := 0; i < 50; i++ {
		anna.must(201, "POST", "/families/"+familyID+"/places", map[string]any{"name": fmt.Sprintf("P%d", i), "lat": 47, "lon": 8, "radius_m": 100})
	}
	anna.must(409, "POST", "/families/"+familyID+"/places", map[string]any{"name": "one too many", "lat": 47, "lon": 8, "radius_m": 100})
}

func TestHistorySharing(t *testing.T) {
	_, anna, bob, _, bobDev, _ := family(t)
	path := "/users/" + bob.userID + "/locations"
	bob.must(200, "PATCH", "/me/settings", map[string]any{"history_enabled": true})
	bobDev.upload(point(47.0, 8.0, time.Now().Add(-2*time.Minute)), point(47.001, 8.0, time.Now().Add(-time.Minute)))

	// Own history always.
	if n := len(bob.must(200, "GET", path, nil).body["points"].([]any)); n != 2 {
		t.Fatalf("own history: %d", n)
	}
	// Others: share + history grant + history enabled – each missing piece is a plain 404.
	anna.must(404, "GET", path, nil)
	bob.must(404, "PATCH", "/shares/"+anna.userID, map[string]any{"history": true}) // no share yet
	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	anna.must(404, "GET", path, nil)
	bob.must(204, "PATCH", "/shares/"+anna.userID, map[string]any{"history": true})
	pts := anna.must(200, "GET", path, nil).body["points"].([]any)
	if len(pts) != 2 || pts[0].(map[string]any)["battery_pct"] != nil {
		t.Fatalf("shared history (battery must be hidden without share_battery): %v", pts)
	}
	in := anna.must(200, "GET", "/shares", nil).body["incoming"].([]any)
	if in[0].(map[string]any)["history"] != true {
		t.Fatalf("incoming share must show history: %v", in)
	}
	bob.must(200, "PATCH", "/me/settings", map[string]any{"sharing_paused": true})
	anna.must(404, "GET", path, nil)
	bob.must(200, "PATCH", "/me/settings", map[string]any{"sharing_paused": false})
	bob.must(204, "PATCH", "/shares/"+anna.userID, map[string]any{"history": false})
	anna.must(404, "GET", path, nil)
	bob.must(400, "PATCH", "/shares/"+anna.userID, map[string]any{})
}

func TestDeletingLocationsClearsAlertPositionsAndExportListsAlerts(t *testing.T) {
	_, anna, bob, _, _, _ := family(t)
	bob.must(201, "POST", "/sos", map[string]any{"lat": 47.3, "lon": 8.5, "accuracy_m": 5})
	exp := bob.must(200, "GET", "/me/export", nil)
	if sent := exp.body["alerts_sent"].([]any); len(sent) != 1 || sent[0].(map[string]any)["kind"] != "sos" {
		t.Fatalf("export must list sent alerts: %v", exp.body["alerts_sent"])
	}
	bob.must(204, "DELETE", "/me/locations", nil)
	if a := anna.alerts()[0]; a["location"] != nil {
		t.Fatalf("deleting own location data must clear alert positions: %v", a)
	}
}
