package api_test

import (
	"net/http"
	"strings"
	"sync"
	"testing"
)

func TestInstanceAndSetup(t *testing.T) {
	e := newEnv(t)
	c := e.webClient()
	r := c.must(200, "GET", "/instance", nil)
	if r.body["setup_required"] != true || r.str("map_style_url") == "" {
		t.Fatalf("instance: %s", r.raw)
	}

	bad := c.do("POST", "/setup", map[string]any{"setup_token": "WRONG", "email": "a@example.org", "password": password,
		"display_name": "A", "family_name": "F"})
	if bad.status != 403 || bad.errCode() != "invalid_setup_token" {
		t.Fatalf("wrong setup token: %d %s", bad.status, bad.raw)
	}

	weak := c.do("POST", "/setup", map[string]any{"setup_token": setupCode, "email": "a@example.org", "password": "short",
		"display_name": "A", "family_name": "F"})
	if weak.status != 400 || weak.body["error"].(map[string]any)["fields"].(map[string]any)["password"] == nil {
		t.Fatalf("weak password: %d %s", weak.status, weak.raw)
	}

	admin, _ := e.setup("Anna")
	me := admin.must(200, "GET", "/me", nil)
	if me.body["is_instance_admin"] != true {
		t.Fatalf("first user must be instance admin: %s", me.raw)
	}
	again := e.webClient().do("POST", "/setup", map[string]any{"setup_token": setupCode, "email": "x@example.org",
		"password": password, "display_name": "X", "family_name": "F"})
	if again.status != 409 {
		t.Fatalf("second setup must fail, got %d", again.status)
	}
	if e.webClient().must(200, "GET", "/instance", nil).body["setup_required"] != false {
		t.Fatal("setup_required must be false after setup")
	}
}

func TestLoginLogoutAndCookieFlags(t *testing.T) {
	e := newEnv(t)
	e.setup("Anna")

	c := e.webClient()
	if r := c.do("POST", "/auth/session", map[string]any{"email": "anna@example.org", "password": "wrong password"}); r.status != 401 {
		t.Fatalf("wrong password: %d", r.status)
	}
	if r := c.do("POST", "/auth/session", map[string]any{"email": "nobody@example.org", "password": password}); r.status != 401 || r.errCode() != "invalid_credentials" {
		t.Fatalf("unknown user must look like wrong password: %d %s", r.status, r.raw)
	}

	req, _ := http.NewRequest("POST", e.ts.URL+"/api/v1/auth/session", strings.NewReader(`{"email":"ANNA@example.org","password":"`+password+`"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-FC-CSRF", "1")
	res, err := c.http.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatalf("login (case-insensitive e-mail) failed: %d", res.StatusCode)
	}
	cookie := res.Header.Get("Set-Cookie")
	for _, want := range []string{"fc_session=fcw_", "HttpOnly", "SameSite=Lax", "Path=/"} {
		if !strings.Contains(cookie, want) {
			t.Errorf("cookie %q missing %q", cookie, want)
		}
	}

	c.must(200, "GET", "/me", nil)
	c.must(204, "DELETE", "/auth/session", nil)
	if r := c.do("GET", "/me", nil); r.status != 401 {
		t.Fatalf("after logout: %d", r.status)
	}
}

func TestLoginRateLimitPerAccount(t *testing.T) {
	e := newEnv(t)
	e.setup("Anna")
	c := e.webClient()
	limited := false
	for i := 0; i < 8; i++ {
		r := c.do("POST", "/auth/session", map[string]any{"email": "anna@example.org", "password": "wrong password"})
		if r.status == 429 {
			limited = true
			break
		}
	}
	if !limited {
		t.Fatal("brute force against one account must be rate limited")
	}
}

func TestCSRFProtection(t *testing.T) {
	e := newEnv(t)
	admin, familyID := e.setup("Anna")

	admin.noCSRF = true
	if r := admin.do("PATCH", "/families/"+familyID, map[string]any{"name": "Hacked"}); r.status != 403 || r.errCode() != "csrf" {
		t.Fatalf("missing CSRF header must be rejected: %d %s", r.status, r.raw)
	}
	admin.noCSRF = false

	req, _ := http.NewRequest("PATCH", e.ts.URL+"/api/v1/families/"+familyID, strings.NewReader(`{"name":"Hacked"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-FC-CSRF", "1")
	req.Header.Set("Origin", "https://evil.example")
	res, err := admin.http.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 403 {
		t.Fatalf("foreign origin must be rejected: %d", res.StatusCode)
	}
	admin.must(200, "PATCH", "/families/"+familyID, map[string]any{"name": "Renamed"})
}

func TestDeviceTokenRefreshRotationAndReuse(t *testing.T) {
	e := newEnv(t)
	e.setup("Anna")
	dev := e.device("Anna")
	dev.must(200, "GET", "/me", nil)

	anon := &client{e: e, http: dev.http}
	r1 := anon.must(200, "POST", "/auth/token", map[string]any{"grant_type": "refresh_token", "refresh_token": dev.refresh})
	newAccess, newRefresh := r1.str("access_token"), r1.str("refresh_token")
	if newRefresh == dev.refresh || newAccess == dev.bearer {
		t.Fatal("tokens must rotate")
	}
	// Old access token is invalid after rotation.
	if r := dev.do("GET", "/me", nil); r.status != 401 {
		t.Fatalf("old access token still valid: %d", r.status)
	}
	dev.bearer = newAccess
	dev.must(200, "GET", "/me", nil)

	// Retrying the previous refresh token right away (lost response) is tolerated once in the window.
	r2 := anon.must(200, "POST", "/auth/token", map[string]any{"grant_type": "refresh_token", "refresh_token": dev.refresh})
	if r2.str("refresh_token") == newRefresh {
		t.Fatal("retry must issue new tokens")
	}
	// Using the refresh token that was superseded by the retry → not found.
	if r := anon.do("POST", "/auth/token", map[string]any{"grant_type": "refresh_token", "refresh_token": newRefresh}); r.status != 401 {
		t.Fatalf("superseded refresh token: %d %s", r.status, r.raw)
	}
}

func TestRefreshReuseOutsideWindowRevokesSession(t *testing.T) {
	e := newEnv(t)
	e.setup("Anna")
	dev := e.device("Anna")
	anon := &client{e: e, http: dev.http}
	stolen := dev.refresh
	r1 := anon.must(200, "POST", "/auth/token", map[string]any{"grant_type": "refresh_token", "refresh_token": stolen})

	// Simulate time passing beyond the retry window by moving the store clock.
	shiftStoreClock(e, 10*60)
	r := anon.do("POST", "/auth/token", map[string]any{"grant_type": "refresh_token", "refresh_token": stolen})
	if r.status != 401 || r.errCode() != "token_reused" {
		t.Fatalf("reuse must be detected: %d %s", r.status, r.raw)
	}
	// The whole session is revoked: the legitimately rotated tokens stop working too.
	if r := anon.do("POST", "/auth/token", map[string]any{"grant_type": "refresh_token", "refresh_token": r1.str("refresh_token")}); r.status != 401 {
		t.Fatalf("session must be revoked after reuse: %d", r.status)
	}
}

func TestPasswordChangeRevokesOtherSessions(t *testing.T) {
	e := newEnv(t)
	web, _ := e.setup("Anna")
	dev := e.device("Anna")
	web.must(204, "PUT", "/me/password", map[string]any{"current_password": password, "new_password": "another long password"})
	if r := dev.do("GET", "/me", nil); r.status != 401 {
		t.Fatalf("device session must be revoked after password change: %d", r.status)
	}
	web.must(200, "GET", "/me", nil)
}

func TestSessionAndDeviceManagementIsolation(t *testing.T) {
	e := newEnv(t)
	admin, familyID := e.setup("Anna")
	bob := e.register(e.invite(admin, familyID, 1), "Bob")
	bobDev := e.device("Bob")

	// Anna cannot revoke Bob's session or delete Bob's device (IDOR).
	bobSessions := bob.must(200, "GET", "/me/sessions", nil).body["sessions"].([]any)
	for _, s := range bobSessions {
		id := s.(map[string]any)["id"].(string)
		if r := admin.do("DELETE", "/me/sessions/"+id, nil); r.status != 404 {
			t.Fatalf("foreign session revoke: %d", r.status)
		}
	}
	if r := admin.do("DELETE", "/me/devices/"+bobDev.device, nil); r.status != 404 {
		t.Fatalf("foreign device delete: %d", r.status)
	}
	bobDev.must(200, "GET", "/me", nil)

	// Bob removes his (stolen) phone: its token stops working immediately.
	bob.must(204, "DELETE", "/me/devices/"+bobDev.device, nil)
	if r := bobDev.do("GET", "/me", nil); r.status != 401 {
		t.Fatalf("deleted device must lose access: %d", r.status)
	}
}

func TestInviteCannotBeReused(t *testing.T) {
	e := newEnv(t)
	admin, familyID := e.setup("Anna")
	code := e.invite(admin, familyID, 1)
	e.register(code, "Bob")
	r := e.webClient().do("POST", "/auth/register", map[string]any{"invite_code": code, "email": "eve@example.org",
		"password": password, "display_name": "Eve"})
	if r.status != 400 || r.errCode() != "invite_invalid" {
		t.Fatalf("second use of single-use invite: %d %s", r.status, r.raw)
	}
}

func TestInviteConcurrentRedemption(t *testing.T) {
	e := newEnv(t)
	admin, familyID := e.setup("Anna")
	code := e.invite(admin, familyID, 1)
	var wg sync.WaitGroup
	results := make(chan int, 5)
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			c := e.webClient()
			name := string(rune('a' + i))
			r := c.do("POST", "/auth/register", map[string]any{"invite_code": code, "email": name + "@race.example",
				"password": password, "display_name": name})
			results <- r.status
		}(i)
	}
	wg.Wait()
	close(results)
	ok := 0
	for s := range results {
		if s == 201 {
			ok++
		}
	}
	if ok != 1 {
		t.Fatalf("exactly one concurrent redemption must succeed, got %d", ok)
	}
}

func TestInviteRevokedAndExpiredAndAdminOnly(t *testing.T) {
	e := newEnv(t)
	admin, familyID := e.setup("Anna")
	bob := e.register(e.invite(admin, familyID, 1), "Bob")

	// Members are not admins: no invite management.
	if r := bob.do("POST", "/families/"+familyID+"/invites", map[string]any{}); r.status != 403 {
		t.Fatalf("member creating invite: %d", r.status)
	}
	if r := bob.do("GET", "/families/"+familyID+"/invites", nil); r.status != 403 {
		t.Fatalf("member listing invites: %d", r.status)
	}

	inv := admin.must(201, "POST", "/families/"+familyID+"/invites", map[string]any{"max_uses": 2})
	admin.must(204, "DELETE", "/families/"+familyID+"/invites/"+inv.str("id"), nil)
	r := e.webClient().do("POST", "/auth/register", map[string]any{"invite_code": inv.str("code"), "email": "c@example.org",
		"password": password, "display_name": "C"})
	if r.status != 400 {
		t.Fatalf("revoked invite must not work: %d", r.status)
	}

	exp := admin.must(201, "POST", "/families/"+familyID+"/invites", map[string]any{"expires_in_hours": 1})
	shiftStoreClock(e, 2*3600)
	r = e.webClient().do("POST", "/auth/register", map[string]any{"invite_code": exp.str("code"), "email": "d@example.org",
		"password": password, "display_name": "D"})
	if r.status != 400 {
		t.Fatalf("expired invite must not work: %d", r.status)
	}
}

func TestAccountDeletion(t *testing.T) {
	e := newEnv(t)
	admin, familyID := e.setup("Anna")
	bob := e.register(e.invite(admin, familyID, 1), "Bob")
	if r := bob.do("DELETE", "/me", map[string]any{"password": "wrong password"}); r.status != 401 {
		t.Fatalf("delete with wrong password: %d", r.status)
	}
	bob.must(204, "DELETE", "/me", map[string]any{"password": password})
	fam := admin.must(200, "GET", "/families/"+familyID, nil)
	if n := len(fam.body["members"].([]any)); n != 1 {
		t.Fatalf("deleted user still member (%d members)", n)
	}
	if r := e.webClient().do("POST", "/auth/session", map[string]any{"email": "bob@example.org", "password": password}); r.status != 401 {
		t.Fatalf("deleted account can still log in: %d", r.status)
	}
}
