package ratelimit

import (
	"testing"
	"time"
)

func TestBurstRefillAndSweep(t *testing.T) {
	now := time.Unix(0, 0)
	l := New(3, 6) // 3 burst, 1 token per 10 s
	l.now = func() time.Time { return now }

	for i := 0; i < 3; i++ {
		if ok, _ := l.Allow("a"); !ok {
			t.Fatalf("request %d should pass", i)
		}
	}
	ok, wait := l.Allow("a")
	if ok || wait <= 0 || wait > 10*time.Second {
		t.Fatalf("4th request: ok=%v wait=%v", ok, wait)
	}
	if ok, _ := l.Allow("b"); !ok {
		t.Fatal("keys must be independent")
	}

	now = now.Add(10 * time.Second)
	if ok, _ := l.Allow("a"); !ok {
		t.Fatal("token should have refilled")
	}

	now = now.Add(time.Hour)
	l.Sweep()
	if l.Len() != 0 {
		t.Fatalf("sweep left %d buckets", l.Len())
	}
}

func TestReset(t *testing.T) {
	l := New(1, 1)
	l.Allow("x")
	if ok, _ := l.Allow("x"); ok {
		t.Fatal("should be limited")
	}
	l.Reset("x")
	if ok, _ := l.Allow("x"); !ok {
		t.Fatal("reset should clear the bucket")
	}
}

func TestBlockedDoesNotConsume(t *testing.T) {
	l := New(2, 1)
	if blocked, _ := l.Blocked("k"); blocked {
		t.Fatal("unknown key is not blocked")
	}
	l.Allow("k")
	l.Allow("k")
	if blocked, wait := l.Blocked("k"); !blocked || wait <= 0 {
		t.Fatal("exhausted key must be blocked")
	}
	if l.Len() != 1 {
		t.Fatal("Blocked must not create keys")
	}
}

func TestKeyTableIsBounded(t *testing.T) {
	now := time.Unix(0, 0)
	l := New(1, 1)
	l.now = func() time.Time { return now }
	l.maxKeys = 3
	for _, k := range []string{"a", "b", "c"} {
		l.Allow(k) // drains each bucket, so sweeping cannot free them
	}
	if ok, _ := l.Allow("d"); ok {
		t.Fatal("new keys must be refused while the table is full of active buckets")
	}
	now = now.Add(time.Hour) // buckets refill → sweep frees space
	if ok, _ := l.Allow("d"); !ok {
		t.Fatal("after refill the key should be accepted")
	}
}
