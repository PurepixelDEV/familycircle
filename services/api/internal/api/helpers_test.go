package api_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"familycircle/internal/api"
	"familycircle/internal/auth"
	"familycircle/internal/config"
	"familycircle/internal/live"
	"familycircle/internal/store"
	"familycircle/internal/testdb"
)

func TestMain(m *testing.M) {
	code := m.Run()
	testdb.DropTemplate()
	os.Exit(code)
}

const (
	setupCode = "TEST-CODE"
	password  = "correct horse battery"
)

type env struct {
	t     *testing.T
	ts    *httptest.Server
	store *store.Store
	srv   *api.Server
}

func newEnv(t *testing.T) *env {
	t.Helper()
	pool := testdb.Migrated(t)
	st := store.New(pool)
	cfg, err := config.Load(func(k string) string {
		if k == "FC_DATABASE_URL" {
			return "postgres://unused"
		}
		return ""
	}, "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := api.New(api.Options{
		Config: cfg, Store: st, Hub: live.NewHub(),
		Hasher: auth.NewHasher(auth.Params{MemoryKiB: 1024, Time: 1, Threads: 1}, 4),
		Logger: testdb.Logger(), SetupToken: setupCode,
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return &env{t: t, ts: ts, store: st, srv: srv}
}

// client is either a browser-like client (cookie jar + CSRF header) or a device (bearer token).
type client struct {
	e       *env
	http    *http.Client
	bearer  string
	refresh string
	userID  string
	device  string
	noCSRF  bool
}

func (e *env) webClient() *client {
	jar, _ := cookiejar.New(nil)
	return &client{e: e, http: &http.Client{Jar: jar, Timeout: 15 * time.Second}}
}

type resp struct {
	status int
	body   map[string]any
	raw    []byte
}

func (r resp) str(path ...string) string {
	var cur any = r.body
	for _, p := range path {
		m, ok := cur.(map[string]any)
		if !ok {
			return ""
		}
		cur = m[p]
	}
	s, _ := cur.(string)
	return s
}

func (r resp) errCode() string { return r.str("error", "code") }

func (c *client) do(method, path string, body any) resp {
	c.e.t.Helper()
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			c.e.t.Fatal(err)
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, c.e.ts.URL+"/api/v1"+path, rd)
	if err != nil {
		c.e.t.Fatal(err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.bearer != "" {
		req.Header.Set("Authorization", "Bearer "+c.bearer)
	} else if !c.noCSRF {
		req.Header.Set("X-FC-CSRF", "1")
	}
	res, err := c.http.Do(req)
	if err != nil {
		c.e.t.Fatal(err)
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	out := resp{status: res.StatusCode, raw: raw}
	_ = json.Unmarshal(raw, &out.body)
	return out
}

func (c *client) must(status int, method, path string, body any) resp {
	c.e.t.Helper()
	r := c.do(method, path, body)
	if r.status != status {
		c.e.t.Fatalf("%s %s: want %d, got %d: %s", method, path, status, r.status, r.raw)
	}
	return r
}

// setup creates the instance admin and returns their web client and family ID.
func (e *env) setup(name string) (*client, string) {
	c := e.webClient()
	r := c.must(201, "POST", "/setup", map[string]any{
		"setup_token": setupCode, "email": strings.ToLower(name) + "@example.org", "password": password,
		"display_name": name, "family_name": "Family " + name,
	})
	c.userID = r.str("id")
	fams := c.must(200, "GET", "/families", nil)
	return c, fams.body["families"].([]any)[0].(map[string]any)["id"].(string)
}

func (e *env) invite(admin *client, familyID string, maxUses int) string {
	r := admin.must(201, "POST", "/families/"+familyID+"/invites", map[string]any{"max_uses": maxUses})
	return r.str("code")
}

func (e *env) register(code, name string) *client {
	c := e.webClient()
	r := c.must(201, "POST", "/auth/register", map[string]any{
		"invite_code": code, "email": strings.ToLower(name) + "@example.org", "password": password, "display_name": name,
	})
	c.userID = r.str("id")
	return c
}

// device logs in via the token endpoint like the Android app does.
func (e *env) device(name string) *client {
	c := &client{e: e, http: &http.Client{Timeout: 15 * time.Second}}
	r := c.must(200, "POST", "/auth/token", map[string]any{
		"grant_type": "password", "email": strings.ToLower(name) + "@example.org", "password": password,
		"device_name": name + "'s phone",
	})
	c.bearer, c.refresh, c.device = r.str("access_token"), r.str("refresh_token"), r.str("device_id")
	c.userID = r.str("user", "id")
	return c
}

func point(lat, lon float64, at time.Time) map[string]any {
	return map[string]any{"id": uuid.NewString(), "recorded_at": at.UTC().Format(time.RFC3339Nano),
		"lat": lat, "lon": lon, "accuracy_m": 8.0, "battery_pct": 77, "motion": "walking"}
}

func (c *client) upload(points ...map[string]any) resp {
	c.e.t.Helper()
	return c.must(200, "POST", "/locations", map[string]any{"points": points})
}

// mapMember returns the map entry of userID as seen by c in family familyID.
func (c *client) mapMember(familyID, userID string) map[string]any {
	c.e.t.Helper()
	r := c.must(200, "GET", "/families/"+familyID+"/map", nil)
	for _, m := range r.body["members"].([]any) {
		mm := m.(map[string]any)
		if mm["user_id"] == userID {
			return mm
		}
	}
	c.e.t.Fatalf("member %s not in map", userID)
	return nil
}

func (c *client) seesLocationOf(familyID, userID string) bool {
	return c.mapMember(familyID, userID)["location"] != nil
}

type wsConn struct {
	t      *testing.T
	conn   *websocket.Conn
	msgs   chan map[string]any
	closed chan struct{}
	err    error // read error that ended the stream (valid after closed)
}

func (c *client) dialWS() *wsConn {
	c.e.t.Helper()
	url := "ws" + strings.TrimPrefix(c.e.ts.URL, "http") + "/api/v1/ws"
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + c.bearer}},
	})
	if err != nil {
		c.e.t.Fatalf("dial ws: %v", err)
	}
	c.e.t.Cleanup(func() { conn.CloseNow() })
	w := &wsConn{t: c.e.t, conn: conn, msgs: make(chan map[string]any, 100), closed: make(chan struct{})}
	// A single reader goroutine: coder/websocket closes the connection when a Read context
	// expires, so timeouts are implemented on the channel instead.
	go func() {
		defer close(w.closed)
		for {
			_, data, err := conn.Read(context.Background())
			if err != nil {
				w.err = err
				return
			}
			var m map[string]any
			if json.Unmarshal(data, &m) == nil {
				w.msgs <- m
			}
		}
	}()
	if m := w.next(2 * time.Second); m == nil || m["type"] != "hello" {
		c.e.t.Fatalf("expected hello, got %v", m)
	}
	return w
}

// next returns the next message, or nil on timeout or when the connection ended.
func (w *wsConn) next(timeout time.Duration) map[string]any {
	select {
	case m := <-w.msgs:
		return m
	case <-w.closed:
		select {
		case m := <-w.msgs:
			return m
		default:
			return nil
		}
	case <-time.After(timeout):
		return nil
	}
}

// waitClosed returns the close status once the server ended the connection.
func (w *wsConn) waitClosed(timeout time.Duration) (websocket.StatusCode, bool) {
	select {
	case <-w.closed:
		return websocket.CloseStatus(w.err), true
	case <-time.After(timeout):
		return -1, false
	}
}

// expect waits for a message of the given type about userID, skipping unrelated messages.
func (w *wsConn) expect(typ, userID string) map[string]any {
	w.t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		m := w.next(time.Until(deadline))
		if m == nil {
			break
		}
		if m["type"] == typ && (userID == "" || m["user_id"] == userID) {
			return m
		}
	}
	w.t.Fatalf("did not receive %s for %s", typ, userID)
	return nil
}

// expectNoLocation asserts that no location message about userID arrives within d.
func (w *wsConn) expectNoLocation(userID string, d time.Duration) {
	w.t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		m := w.next(time.Until(deadline))
		if m == nil {
			return
		}
		if m["type"] == "location" && m["user_id"] == userID {
			w.t.Fatalf("received forbidden location for %s: %v", userID, m)
		}
	}
}

// shiftStoreClock moves the database-side clock forward (expiry tests).
func shiftStoreClock(e *env, seconds int) {
	d := time.Duration(seconds) * time.Second
	e.store.SetClock(func() time.Time { return time.Now().UTC().Add(d) })
}
