package store

import "testing"

func TestPresenceDecision(t *testing.T) {
	cases := []struct {
		name                    string
		was                     bool
		dist, radius, acc       float64
		wantInside, wantDecided bool
	}{
		{"enter inside radius", false, 80, 100, 10, true, true},
		{"outside stays outside", false, 120, 100, 10, false, true},
		{"imprecise fix cannot enter a small place", false, 50, 100, 250, false, true},
		{"precise enough for a big place", false, 300, 500, 250, true, true},
		{"jitter beyond radius keeps inside", true, 140, 100, 10, true, true},
		{"leave beyond radius + 50 m", true, 151, 100, 10, false, true},
		{"poor accuracy widens the exit margin", true, 180, 100, 120, true, true},
		{"leave beyond radius + accuracy", true, 221, 100, 120, false, true},
		{"fix worse than 500 m decides nothing", true, 5000, 100, 600, true, false},
	}
	for _, c := range cases {
		inside, decided := PresenceDecision(c.was, c.dist, c.radius, c.acc)
		if inside != c.wantInside || decided != c.wantDecided {
			t.Errorf("%s: got (%v, %v), want (%v, %v)", c.name, inside, decided, c.wantInside, c.wantDecided)
		}
	}
}
