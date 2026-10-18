package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"strings"
)

// Token prefixes make leaked secrets recognisable (e.g. by secret scanners).
const (
	PrefixWebSession = "fcw_"
	PrefixAccess     = "fca_"
	PrefixRefresh    = "fcr_"
)

// NewToken returns a random 256-bit token with the given prefix and its SHA-256 hash.
// Only the hash is ever stored.
func NewToken(prefix string) (token string, hash []byte) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic("crypto/rand failed: " + err.Error())
	}
	token = prefix + base64.RawURLEncoding.EncodeToString(b)
	return token, HashToken(token)
}

func HashToken(token string) []byte {
	sum := sha256.Sum256([]byte(token))
	return sum[:]
}

// Crockford base32 alphabet: no I, L, O, U – easy to read aloud and type.
const codeAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// NewCode returns a human-friendly random code with groups of four, e.g. "K7QF-2M9X-HD4R".
// 12 characters = 60 bits of entropy; combined with expiry and rate limiting this is ample
// for short-lived invite/setup codes.
func NewCode(groups int) string {
	n := groups * 4
	raw := make([]byte, n)
	if _, err := rand.Read(raw); err != nil {
		panic("crypto/rand failed: " + err.Error())
	}
	var sb strings.Builder
	for i, b := range raw {
		if i > 0 && i%4 == 0 {
			sb.WriteByte('-')
		}
		sb.WriteByte(codeAlphabet[int(b)%len(codeAlphabet)]) // 256 % 32 == 0: no modulo bias
	}
	return sb.String()
}

// NormalizeCode maps user input to canonical form: upper case, separators removed and
// commonly confused characters folded (O→0, I/L→1).
func NormalizeCode(in string) string {
	var sb strings.Builder
	for _, r := range strings.ToUpper(in) {
		switch r {
		case '-', ' ', '\t':
			continue
		case 'O':
			r = '0'
		case 'I', 'L':
			r = '1'
		}
		sb.WriteRune(r)
	}
	return sb.String()
}

// HashCode hashes a normalised code for storage and lookup.
func HashCode(code string) []byte {
	return HashToken("code:" + NormalizeCode(code))
}
