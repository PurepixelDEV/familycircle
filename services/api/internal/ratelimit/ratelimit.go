// Package ratelimit implements an in-memory token bucket keyed by string (IP, e-mail, user).
// A single process serves the whole instance, so no shared store (Redis) is needed.
package ratelimit

import (
	"math"
	"sync"
	"time"
)

type bucket struct {
	tokens float64
	last   time.Time
}

type Limiter struct {
	mu       sync.Mutex
	buckets  map[string]*bucket
	capacity float64
	perSec   float64
	now      func() time.Time
}

// New creates a limiter allowing bursts of `burst` and refilling `perMinute` tokens per minute.
func New(burst int, perMinute float64) *Limiter {
	return &Limiter{
		buckets:  make(map[string]*bucket),
		capacity: float64(burst),
		perSec:   perMinute / 60,
		now:      time.Now,
	}
}

// Allow consumes one token for key. If none is available it returns false and the wait
// until the next token.
func (l *Limiter) Allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.capacity, last: now}
		l.buckets[key] = b
	}
	b.tokens = math.Min(l.capacity, b.tokens+now.Sub(b.last).Seconds()*l.perSec)
	b.last = now
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	wait := time.Duration((1 - b.tokens) / l.perSec * float64(time.Second))
	return false, wait
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
