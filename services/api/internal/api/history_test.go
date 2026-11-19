package api_test

import (
	"net/url"
	"strings"
	"testing"
	"time"
)

func boundsQuery(ts ...time.Time) string {
	parts := make([]string, len(ts))
	for i, t := range ts {
		parts[i] = t.UTC().Format(time.RFC3339)
	}
	return "?bounds=" + url.QueryEscape(strings.Join(parts, ","))
}

func TestHistoryDaysSummary(t *testing.T) {
	_, anna, bob, _, bobDev, _ := family(t)
	bob.must(200, "PATCH", "/me/settings", map[string]any{"history_enabled": true})
	now := time.Now()
	inaccurate := point(47.5, 8.0, now.Add(-25*time.Minute))
	inaccurate["accuracy_m"] = 500.0
	bobDev.upload(
		point(47.0, 8.0, now.Add(-60*time.Minute)),
		point(47.0, 8.0001, now.Add(-50*time.Minute)),  // ~8 m: noise of someone standing still
		point(47.01, 8.0001, now.Add(-40*time.Minute)), // ~1.1 km
		inaccurate, // left out entirely
	)
	path := "/users/" + bob.userID + "/history/days"
	q := boundsQuery(now.Add(-48*time.Hour), now.Add(-24*time.Hour), now.Add(time.Hour))

	days := bob.must(200, "GET", path+q, nil).body["days"].([]any)
	if len(days) != 2 {
		t.Fatalf("one entry per interval expected: %v", days)
	}
	empty, today := days[0].(map[string]any), days[1].(map[string]any)
	if empty["points"].(float64) != 0 || empty["distance_m"].(float64) != 0 || empty["first_at"] != nil {
		t.Fatalf("empty day: %v", empty)
	}
	if today["points"].(float64) != 3 {
		t.Fatalf("3 plausible points expected: %v", today)
	}
	if d := today["distance_m"].(float64); d < 1050 || d > 1180 {
		t.Fatalf("distance must count the 1.1 km step but not the 8 m jitter: %v", d)
	}
	if today["first_at"] == nil || today["last_at"] == nil {
		t.Fatalf("first/last expected: %v", today)
	}

	// Same access rule as the history itself: share + history grant + history enabled.
	anna.must(404, "GET", path+q, nil)
	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	anna.must(404, "GET", path+q, nil)
	bob.must(204, "PATCH", "/shares/"+anna.userID, map[string]any{"history": true})
	anna.must(200, "GET", path+q, nil)

	// Validation.
	bob.must(400, "GET", path, nil)
	bob.must(400, "GET", path+boundsQuery(now), nil)
	bob.must(400, "GET", path+boundsQuery(now, now.Add(-time.Hour)), nil)
	bob.must(400, "GET", path+boundsQuery(now.Add(-70*24*time.Hour), now), nil)
	bob.must(400, "GET", path+"?bounds=yesterday,today", nil)
}

func TestHistoryEvents(t *testing.T) {
	_, anna, bob, _, _, _ := family(t)
	bob.must(200, "PATCH", "/me/settings", map[string]any{"history_enabled": true})
	bob.must(201, "POST", "/check-ins", map[string]any{"lat": 47.0, "lon": 8.0, "accuracy_m": 10, "message": "Bin da"})
	bob.must(201, "POST", "/sos", map[string]any{"lat": 47.0, "lon": 8.0, "accuracy_m": 10})
	anna.must(201, "POST", "/sos", map[string]any{"lat": 46.0, "lon": 7.0, "accuracy_m": 10})
	now := time.Now()
	path := "/users/" + bob.userID + "/history/events"
	q := "?from=" + url.QueryEscape(now.Add(-time.Hour).UTC().Format(time.RFC3339)) + "&to=" + url.QueryEscape(now.Add(time.Hour).UTC().Format(time.RFC3339))

	kinds := func(c *client) []string {
		var out []string
		for _, e := range c.must(200, "GET", path+q, nil).body["events"].([]any) {
			ev := e.(map[string]any)
			if ev["actor"].(map[string]any)["user_id"] != bob.userID {
				t.Fatalf("only the person's own events: %v", ev)
			}
			out = append(out, ev["kind"].(string))
		}
		return out
	}
	if k := kinds(bob); strings.Join(k, ",") != "check_in,sos" {
		t.Fatalf("own events oldest first: %v", k)
	}

	// Anna received both alerts, but the timeline needs the history grant like the history itself.
	anna.must(404, "GET", path+q, nil)
	bob.must(204, "PUT", "/shares/"+anna.userID, nil)
	bob.must(204, "PATCH", "/shares/"+anna.userID, map[string]any{"history": true})
	if k := kinds(anna); strings.Join(k, ",") != "check_in,sos" {
		t.Fatalf("received events: %v", k)
	}

	// Outside the range: nothing.
	old := "?from=" + url.QueryEscape(now.Add(-48*time.Hour).UTC().Format(time.RFC3339)) + "&to=" + url.QueryEscape(now.Add(-24*time.Hour).UTC().Format(time.RFC3339))
	if ev := bob.must(200, "GET", path+old, nil).body["events"].([]any); len(ev) != 0 {
		t.Fatalf("range must filter: %v", ev)
	}
	bob.must(400, "GET", path, nil)
	bob.must(400, "GET", path+"?from="+url.QueryEscape(now.UTC().Format(time.RFC3339))+"&to="+url.QueryEscape(now.Add(-time.Hour).UTC().Format(time.RFC3339)), nil)
}
