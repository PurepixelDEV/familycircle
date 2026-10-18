package api_test

import (
	"testing"
	"time"
)

func pairingGrant(code string) map[string]any {
	return map[string]any{"grant_type": "pairing_code", "pairing_code": code, "device_name": "Scanned phone", "app_version": "0.2.0"}
}

func TestPairingCodeConnectsPhoneToOwnAccount(t *testing.T) {
	e, anna, _, _, _, familyID := family(t)
	r := anna.must(201, "POST", "/me/pairing-codes", nil)
	code := r.str("code")
	if len(code) != 14 || r.str("expires_at") == "" {
		t.Fatalf("pairing code: %s", r.raw)
	}
	phone := &client{e: e, http: anna.http}
	tok := phone.must(200, "POST", "/auth/token", pairingGrant(code))
	if tok.str("user", "id") != anna.userID {
		t.Fatal("pairing must sign in the account that created the code")
	}
	dev := &client{e: e, http: anna.http, bearer: tok.str("access_token")}
	dev.upload(point(47.37, 8.54, time.Now()))
	if !anna.seesLocationOf(familyID, anna.userID) {
		t.Fatal("paired device must upload under the owner's identity")
	}
	devices := anna.must(200, "GET", "/me/devices", nil).body["devices"].([]any)
	if len(devices) != 2 { // the family() helper's device + the scanned phone
		t.Fatalf("expected 2 devices, got %d", len(devices))
	}
}

func TestPairingCodeIsSingleUseAndExpires(t *testing.T) {
	e, anna, _, _, _, _ := family(t)
	phone := &client{e: e, http: anna.http}
	code := anna.must(201, "POST", "/me/pairing-codes", nil).str("code")
	phone.must(200, "POST", "/auth/token", pairingGrant(code))
	if r := phone.do("POST", "/auth/token", pairingGrant(code)); r.status != 401 || r.errCode() != "invalid_grant" {
		t.Fatalf("second redemption: %d %s", r.status, r.raw)
	}
	expired := anna.must(201, "POST", "/me/pairing-codes", nil).str("code")
	shiftStoreClock(e, 11*60)
	if r := phone.do("POST", "/auth/token", pairingGrant(expired)); r.status != 401 {
		t.Fatalf("expired code accepted: %d", r.status)
	}
	if r := phone.do("POST", "/auth/token", pairingGrant("AAAA-BBBB-CCCC")); r.status != 401 {
		t.Fatalf("random code accepted: %d", r.status)
	}
}

func TestPairingCodeLimitsAndAuth(t *testing.T) {
	e, anna, _, _, _, _ := family(t)
	if r := e.webClient().do("POST", "/me/pairing-codes", nil); r.status != 401 {
		t.Fatalf("anonymous code creation: %d", r.status)
	}
	for i := 0; i < 5; i++ {
		anna.must(201, "POST", "/me/pairing-codes", nil)
	}
	if r := anna.do("POST", "/me/pairing-codes", nil); r.status != 429 {
		t.Fatalf("6th open code must be refused: %d", r.status)
	}
	// An invite code is not a pairing code (separate hash domains).
	invite := e.invite(anna, anna.mustFamily(), 1)
	if r := (&client{e: e, http: anna.http}).do("POST", "/auth/token", pairingGrant(invite)); r.status != 401 {
		t.Fatalf("invite code accepted as pairing code: %d", r.status)
	}
}
