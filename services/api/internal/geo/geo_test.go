package geo

import (
	"math"
	"testing"
	"time"
)

func TestDistance(t *testing.T) {
	// Zürich HB → Bern Bahnhof ≈ 95.5 km
	d := DistanceMeters(47.3779, 8.5403, 46.9490, 7.4392)
	if math.Abs(d-95_500) > 1_500 {
		t.Fatalf("distance %f", d)
	}
	if DistanceMeters(1, 1, 1, 1) != 0 {
		t.Fatal("zero distance expected")
	}
}

var t0 = time.Date(2026, 10, 18, 12, 0, 0, 0, time.UTC)

func fix(lat, lon float64, sec int) Fix {
	return Fix{Lat: lat, Lon: lon, AccuracyM: 10, At: t0.Add(time.Duration(sec) * time.Second)}
}

func TestPlausible(t *testing.T) {
	// 1 km in 60 s walking/driving → plausible
	if !Plausible(fix(47.0, 8.0, 0), fix(47.009, 8.0, 60)) {
		t.Fatal("1 km/min should be plausible")
	}
	// 50 km in 10 s → jump
	if Plausible(fix(47.0, 8.0, 0), fix(47.45, 8.0, 10)) {
		t.Fatal("50 km in 10 s must be implausible")
	}
	// Low accuracy absorbs the distance
	a, b := fix(47.0, 8.0, 0), fix(47.009, 8.0, 0)
	a.AccuracyM, b.AccuracyM = 600, 600
	if !Plausible(a, b) {
		t.Fatal("overlapping accuracy circles must be plausible")
	}
}

func TestIsSuspectAndConfirmation(t *testing.T) {
	last := fix(47.0, 8.0, 0)
	jump := fix(48.0, 8.0, 5) // ~111 km in 5 s
	if IsSuspect(nil, nil, jump) {
		t.Fatal("first fix is never suspect")
	}
	if !IsSuspect(&last, nil, jump) {
		t.Fatal("jump must be suspect")
	}
	// Next fix near the jump position confirms the move (e.g. after a flight / long offline gap).
	confirm := fix(48.0001, 8.0, 35)
	if IsSuspect(&last, &jump, confirm) {
		t.Fatal("consecutive agreeing fixes must confirm the jump")
	}
	// A fix back near the original position is plausible relative to last.
	back := fix(47.0001, 8.0, 40)
	if IsSuspect(&last, &jump, back) {
		t.Fatal("fix near last accepted position must be accepted")
	}
}

func TestFreshness(t *testing.T) {
	now := t0
	cases := map[time.Duration]Freshness{
		0:                 Live,
		-90 * time.Second: Live,
		-3 * time.Minute:  Recent,
		-20 * time.Minute: Stale,
		-48 * time.Hour:   Stale,
		30 * time.Second:  Live, // slight device clock skew into the future
	}
	for off, want := range cases {
		if got := FreshnessOf(now.Add(off), now); got != want {
			t.Errorf("offset %v: got %s want %s", off, got, want)
		}
	}
}
