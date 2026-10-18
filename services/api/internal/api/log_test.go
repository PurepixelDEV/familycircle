package api_test

import (
	"bytes"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/google/uuid"

	"familycircle/internal/api"
	"familycircle/internal/config"
	"familycircle/internal/live"
)

// Logs must contain the matched route pattern, never the raw path: the path is user input
// (log injection) and carries family/user IDs. Found by CodeQL go/log-injection.
func TestLogsContainRoutePatternNotRawPath(t *testing.T) {
	var buf bytes.Buffer
	cfg, err := config.Load(func(k string) string {
		if k == "FC_DATABASE_URL" {
			return "postgres://unused"
		}
		return ""
	}, "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := api.New(api.Options{Config: cfg, Hub: live.NewHub(), Logger: slog.New(slog.NewJSONHandler(&buf, nil))})
	h := srv.Handler()

	id := uuid.NewString()
	for _, path := range []string{"/api/v1/families/" + id + "/map", "/api/v1/nope%0Aforged=1/" + id} {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil)) // unauthenticated: 401/404, no store needed
	}
	logs := buf.String()
	if strings.Contains(logs, id) {
		t.Fatalf("raw path (with ID) leaked into logs:\n%s", logs)
	}
	if strings.Contains(logs, "forged") {
		t.Fatalf("user-controlled path text in logs:\n%s", logs)
	}
	if !strings.Contains(logs, `"route":"GET /api/v1/families/{familyId}/map"`) {
		t.Fatalf("route pattern missing in logs:\n%s", logs)
	}
}
