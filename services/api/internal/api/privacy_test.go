package api_test

import (
	"testing"
	"time"

	"github.com/google/uuid"
)

// family sets up Anna (admin) and Bob (member) with a device each.
func family(t *testing.T) (e *env, anna, bob, annaDev, bobDev *client, familyID string) {
	e = newEnv(t)
	anna, familyID = e.setup("Anna")
	bob = e.register(e.invite(anna, familyID, 1), "Bob")
	annaDev, bobDev = e.device("Anna"), e.device("Bob")
	return
}

func TestNoShareNoLocation(t *testing.T) {
	_, anna, bob, _, bobDev, familyID := family(t)
	bobDev.upload(point(47.37, 8.54, time.Now()))

	// Privacy by default: being in the same family is not enough.
	if anna.seesLocationOf(familyID, bob.userID) {
		t.Fatal("Anna must not see Bob without an explicit share")
	}
	// Bob sees himself.
	if !bob.seesLocationOf(familyID, bob.userID) {
		t.Fatal("Bob must see his own location")
	}
}

func TestShareGrantPauseRevoke(t *testing.T) {
	_, anna, bob, _, bobDev, familyID := family(t)
	bobDev.upload(point(47.37, 8.54, time.Now()))

	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	m := anna.mapMember(familyID, bob.userID)
	loc, _ := m["location"].(map[string]any)
	if loc == nil || loc["lat"].(float64) != 47.37 {
		t.Fatalf("Anna should see Bob after share: %v", m)
	}
	if loc["battery_pct"] != nil {
		t.Fatal("battery must be hidden unless the owner opts in")
	}
	bob.must(200, "PATCH", "/me/settings", map[string]any{"share_battery": true})
	if anna.mapMember(familyID, bob.userID)["location"].(map[string]any)["battery_pct"] == nil {
		t.Fatal("battery should be visible after opt-in")
	}

	bob.must(200, "PATCH", "/me/settings", map[string]any{"sharing_paused": true})
	if anna.seesLocationOf(familyID, bob.userID) {
		t.Fatal("pause must hide the location immediately")
	}
	if !bob.seesLocationOf(familyID, bob.userID) {
		t.Fatal("owner still sees own location while paused")
	}
	bob.must(200, "PATCH", "/me/settings", map[string]any{"sharing_paused": false})
	if !anna.seesLocationOf(familyID, bob.userID) {
		t.Fatal("unpause restores visibility")
	}

	bob.must(204, "DELETE", "/shares/"+anna.userID, nil)
	if anna.seesLocationOf(familyID, bob.userID) {
		t.Fatal("revocation must take effect immediately")
	}
	shares := anna.must(200, "GET", "/shares", nil)
	if len(shares.body["incoming"].([]any)) != 0 {
		t.Fatal("revoked share still listed as incoming")
	}
}

func TestAdminCannotBypassSharing(t *testing.T) {
	_, anna, bob, _, bobDev, familyID := family(t)
	bobDev.upload(point(47.37, 8.54, time.Now()))

	// Anna is family admin. She cannot read Bob's location ...
	if anna.seesLocationOf(familyID, bob.userID) {
		t.Fatal("admin must not see member without share")
	}
	// ... cannot create a share on Bob's behalf (PUT /shares/{id} only shares the caller's own location) ...
	anna.must(204, "PUT", "/shares/"+bob.userID, nil)
	if anna.seesLocationOf(familyID, bob.userID) {
		t.Fatal("admin sharing her own location must not reveal Bob's")
	}
	// ... cannot read Bob's history or export.
	if r := anna.do("GET", "/me/locations", nil); r.status != 200 || len(r.body["points"].([]any)) != 0 {
		t.Fatalf("history endpoint must only return own data: %s", r.raw)
	}
	// ... and cannot unpause or change Bob's settings: settings are always the caller's own.
	bob.must(200, "PATCH", "/me/settings", map[string]any{"sharing_paused": true})
	anna.must(200, "PATCH", "/me/settings", map[string]any{"sharing_paused": false})
	if st := bob.must(200, "GET", "/me/settings", nil); st.body["sharing_paused"] != true {
		t.Fatal("admin changed member settings")
	}
}

func TestCrossFamilyIDOR(t *testing.T) {
	e := newEnv(t)
	anna, famA := e.setup("Anna")
	// Second, unrelated family: the instance admin (Anna) creates it for Dave, makes him admin,
	// then leaves it; Dave also leaves Anna's family. Afterwards Anna is a complete outsider.
	dave := e.register(e.invite(anna, famA, 1), "Dave")
	famD := anna.must(201, "POST", "/families", map[string]any{"name": "Dave's"}).str("id")
	dave.must(200, "POST", "/invites/accept", map[string]any{"code": e.invite(anna, famD, 1)})
	anna.must(204, "PATCH", "/families/"+famD+"/members/"+dave.userID, map[string]any{"role": "admin"})
	anna.must(204, "POST", "/families/"+famD+"/leave", nil)
	dave.must(204, "POST", "/families/"+famA+"/leave", nil)
	daveDev := e.device("Dave")
	daveDev.upload(point(46.95, 7.44, time.Now()))

	for _, path := range []string{"/families/" + famD, "/families/" + famD + "/map", "/families/" + famD + "/invites"} {
		if r := anna.do("GET", path, nil); r.status != 404 {
			t.Errorf("GET %s by outsider: want 404, got %d", path, r.status)
		}
	}
	if r := anna.do("PATCH", "/families/"+famD, map[string]any{"name": "pwned"}); r.status != 404 {
		t.Errorf("rename foreign family: %d", r.status)
	}
	if r := anna.do("POST", "/families/"+famD+"/invites", map[string]any{}); r.status != 404 {
		t.Errorf("invite into foreign family: %d", r.status)
	}
	if r := anna.do("DELETE", "/families/"+famD+"/members/"+dave.userID, nil); r.status != 404 {
		t.Errorf("remove member of foreign family: %d", r.status)
	}
	// Sharing with someone outside all common families is impossible.
	if r := anna.do("PUT", "/shares/"+dave.userID, nil); r.status != 404 {
		t.Errorf("share with non-family user: %d", r.status)
	}
	// Avatars are only visible within common families.
	if r := anna.do("GET", "/users/"+dave.userID+"/avatar", nil); r.status != 404 {
		t.Errorf("foreign avatar: %d", r.status)
	}
	// Random and malformed IDs behave the same as foreign IDs.
	if r := anna.do("GET", "/families/"+uuid.NewString()+"/map", nil); r.status != 404 {
		t.Errorf("random family id: %d", r.status)
	}
	if r := anna.do("GET", "/families/not-a-uuid/map", nil); r.status != 404 {
		t.Errorf("malformed id: %d", r.status)
	}
}

func TestLeavingFamilyEndsVisibilityAndDoesNotResurrect(t *testing.T) {
	e, anna, bob, _, bobDev, familyID := family(t)
	bobDev.upload(point(47.37, 8.54, time.Now()))
	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	if !anna.seesLocationOf(familyID, bob.userID) {
		t.Fatal("precondition: Anna sees Bob")
	}
	bob.must(204, "POST", "/families/"+familyID+"/leave", nil)
	if r := anna.do("GET", "/shares", nil); len(r.body["incoming"].([]any)) != 0 {
		t.Fatal("share must end when the common family ends")
	}
	// Bob rejoins later: the old share must NOT come back automatically.
	bob.must(200, "POST", "/invites/accept", map[string]any{"code": e.invite(anna, familyID, 1)})
	if anna.seesLocationOf(familyID, bob.userID) {
		t.Fatal("old share resurrected after re-joining")
	}
}

func TestAdminRemovingMemberEndsVisibility(t *testing.T) {
	_, anna, bob, annaDev, _, familyID := family(t)
	annaDev.upload(point(47.37, 8.54, time.Now()))
	anna.must(204, "PUT", "/shares/"+bob.userID, nil)
	if !bob.seesLocationOf(familyID, anna.userID) {
		t.Fatal("precondition")
	}
	anna.must(204, "DELETE", "/families/"+familyID+"/members/"+bob.userID, nil)
	if r := bob.do("GET", "/families/"+familyID+"/map", nil); r.status != 404 {
		t.Fatalf("removed member still has access: %d", r.status)
	}
}

func TestLastAdminRules(t *testing.T) {
	_, anna, bob, _, _, familyID := family(t)
	if r := anna.do("POST", "/families/"+familyID+"/leave", nil); r.status != 409 || r.errCode() != "last_admin" {
		t.Fatalf("last admin leaving: %d %s", r.status, r.raw)
	}
	if r := anna.do("PATCH", "/families/"+familyID+"/members/"+anna.userID, map[string]any{"role": "member"}); r.status != 409 {
		t.Fatalf("last admin demoting self: %d", r.status)
	}
	if r := bob.do("PATCH", "/families/"+familyID+"/members/"+bob.userID, map[string]any{"role": "admin"}); r.status != 403 {
		t.Fatalf("member promoting self: %d", r.status)
	}
	anna.must(204, "PATCH", "/families/"+familyID+"/members/"+bob.userID, map[string]any{"role": "admin"})
	anna.must(204, "POST", "/families/"+familyID+"/leave", nil)
}

func TestOnlyInstanceAdminCreatesFamilies(t *testing.T) {
	_, anna, bob, _, _, _ := family(t)
	if r := bob.do("POST", "/families", map[string]any{"name": "Bob's own"}); r.status != 403 {
		t.Fatalf("regular user creating a family (and thus minting invites): %d", r.status)
	}
	anna.must(201, "POST", "/families", map[string]any{"name": "Second family"})
}
