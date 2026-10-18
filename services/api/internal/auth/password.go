// Package auth contains password hashing and opaque token helpers.
package auth

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"

	"golang.org/x/crypto/argon2"
)

// Params are the Argon2id cost parameters (OWASP: m=64 MiB, t=3, p=2 is a safe profile).
type Params struct {
	MemoryKiB uint32
	Time      uint32
	Threads   uint8
}

var DefaultParams = Params{MemoryKiB: 64 * 1024, Time: 3, Threads: 2}

const (
	saltLen = 16
	keyLen  = 32
)

var ErrMalformedHash = errors.New("malformed password hash")

// Hasher hashes and verifies passwords. It bounds concurrent hashing so that a burst of
// logins cannot exhaust memory (each hash allocates MemoryKiB).
type Hasher struct {
	params Params
	slots  chan struct{}
	dummy  string
}

func NewHasher(p Params, maxConcurrent int) *Hasher {
	h := &Hasher{params: p, slots: make(chan struct{}, maxConcurrent)}
	// Pre-computed hash used to spend the same time on unknown accounts (no user enumeration by timing).
	h.dummy, _ = h.hash(context.Background(), "dummy-password-for-timing-equalisation")
	return h
}

func (h *Hasher) acquire(ctx context.Context) error {
	select {
	case h.slots <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (h *Hasher) release() { <-h.slots }

// Hash returns a PHC-formatted Argon2id hash.
func (h *Hasher) Hash(ctx context.Context, password string) (string, error) {
	return h.hash(ctx, password)
}

func (h *Hasher) hash(ctx context.Context, password string) (string, error) {
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	if err := h.acquire(ctx); err != nil {
		return "", err
	}
	key := argon2.IDKey([]byte(password), salt, h.params.Time, h.params.MemoryKiB, h.params.Threads, keyLen)
	h.release()
	b64 := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, h.params.MemoryKiB, h.params.Time, h.params.Threads, b64.EncodeToString(salt), b64.EncodeToString(key)), nil
}

// Verify checks password against encoded. needsRehash is true when the stored hash uses
// different parameters than the current ones.
func (h *Hasher) Verify(ctx context.Context, password, encoded string) (ok, needsRehash bool, err error) {
	p, salt, key, err := decode(encoded)
	if err != nil {
		return false, false, err
	}
	if err := h.acquire(ctx); err != nil {
		return false, false, err
	}
	other := argon2.IDKey([]byte(password), salt, p.Time, p.MemoryKiB, p.Threads, uint32(len(key)))
	h.release()
	ok = subtle.ConstantTimeCompare(key, other) == 1
	return ok, ok && p != h.params, nil
}

// VerifyDummy burns the same CPU/memory as a real verification. Use for unknown accounts.
func (h *Hasher) VerifyDummy(ctx context.Context, password string) {
	_, _, _ = h.Verify(ctx, password, h.dummy)
}

func decode(encoded string) (Params, []byte, []byte, error) {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return Params{}, nil, nil, ErrMalformedHash
	}
	var version int
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil || version != argon2.Version {
		return Params{}, nil, nil, ErrMalformedHash
	}
	var p Params
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &p.MemoryKiB, &p.Time, &p.Threads); err != nil {
		return Params{}, nil, nil, ErrMalformedHash
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return Params{}, nil, nil, ErrMalformedHash
	}
	key, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil || len(key) == 0 {
		return Params{}, nil, nil, ErrMalformedHash
	}
	return p, salt, key, nil
}

// ValidatePassword enforces the password policy: length only (NIST SP 800-63B), 10–256 characters.
func ValidatePassword(pw string) error {
	n := len([]rune(pw))
	switch {
	case n < 10:
		return errors.New("password must have at least 10 characters")
	case n > 256:
		return errors.New("password must have at most 256 characters")
	}
	return nil
}
