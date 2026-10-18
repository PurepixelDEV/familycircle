// Package ratelimit implements an in-memory token bucket keyed by string (IP, e-mail, user).
// A single process serves the whole instance, so no shared store (Redis) is needed.
package ratelimit

import (
	"math"
	"sync"
	"time"
)

// DefaultMaxKeys bounds memory: an attacker rotating keys cannot grow the map without limit.
const DefaultMaxKeys = 100_000

type bucket struct {
	tokens float64
	last   time.Time
}

type Limiter struct {
	mu       sync.Mutex
	buckets  map[string]*bucket
	capacity float64
	perSec   float64
	maxKeys  int
	now      func() time.Time
}

// New creates a limiter allowing bursts of `burst` and refilling `perMinute` tokens per minute.
func New(burst int, perMinute float64) *Limiter {
	return &Limiter{
		buckets:  make(map[string]*bucket),
		capacity: float64(burst),
		perSec:   perMinute / 60,
		maxKeys:  DefaultMaxKeys,
		now:      time.Now,
	}
}

// refill returns the bucket for key with tokens updated to now; nil if the key table is full.
func (l *Limiter) refill(key string, create bool) *bucket {
	now := l.now()
	b, ok := l.buckets[key]
	if !ok {
		if !create {
			return nil
		}
		if len(l.buckets) >= l.maxKeys {
			l.sweepLocked()
			if len(l.buckets) >= l.maxKeys {
				return nil // fail closed under key flooding
			}
		}
		b = &bucket{tokens: l.capacity, last: now}
		l.buckets[key] = b
	}
	b.tokens = math.Min(l.capacity, b.tokens+now.Sub(b.last).Seconds()*l.perSec)
	b.last = now
	return b
}

func (l *Limiter) wait(b *bucket) time.Duration {
	if b == nil {
		return time.Minute
	}
	return time.Duration((1 - b.tokens) / l.perSec * float64(time.Second))
}

// Allow consumes one token for key. If none is available it returns false and the wait
// until the next token.
func (l *Limiter) Allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.refill(key, true)
	if b != nil && b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	return false, l.wait(b)
}

// Blocked reports whether key has no token left, without consuming one. Used to throttle
// only failed attempts: check Blocked before, call Allow after a failure.
func (l *Limiter) Blocked(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.refill(key, false)
	if b == nil || b.tokens >= 1 {
		return false, 0
	}
	return true, l.wait(b)
}

// Reset forgets a key (e.g. after a successful login).
func (l *Limiter) Reset(key string) {
	l.mu.Lock()
	delete(l.buckets, key)
	l.mu.Unlock()
}

// Sweep removes buckets that are full again, bounding memory. Call periodically.
func (l *Limiter) Sweep() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.sweepLocked()
}

func (l *Limiter) sweepLocked() {
	now := l.now()
	for k, b := range l.buckets {
		if b.tokens+now.Sub(b.last).Seconds()*l.perSec >= l.capacity {
			delete(l.buckets, k)
		}
	}
}

func (l *Limiter) Len() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.buckets)
}
