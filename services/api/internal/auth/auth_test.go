package auth

import (
	"bytes"
	"context"
	"strings"
	"testing"
)

var fastParams = Params{MemoryKiB: 1024, Time: 1, Threads: 1}

func TestHashAndVerify(t *testing.T) {
	ctx := context.Background()
	h := NewHasher(fastParams, 2)
	enc, err := h.Hash(ctx, "correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(enc, "$argon2id$v=19$m=1024,t=1,p=1$") {
		t.Fatalf("unexpected encoding %q", enc)
	}
	ok, rehash, err := h.Verify(ctx, "correct horse battery", enc)
	if err != nil || !ok || rehash {
		t.Fatalf("verify: ok=%v rehash=%v err=%v", ok, rehash, err)
	}
	ok, _, err = h.Verify(ctx, "wrong password!", enc)
	if err != nil || ok {
		t.Fatalf("wrong password accepted: ok=%v err=%v", ok, err)
	}
	enc2, _ := h.Hash(ctx, "correct horse battery")
	if enc == enc2 {
		t.Fatal("salt must differ between hashes")
	}
}

func TestNeedsRehashWhenParamsChange(t *testing.T) {
	ctx := context.Background()
	old := NewHasher(fastParams, 1)
	enc, _ := old.Hash(ctx, "some password 1")
	stronger := NewHasher(Params{MemoryKiB: 2048, Time: 1, Threads: 1}, 1)
	ok, rehash, err := stronger.Verify(ctx, "some password 1", enc)
	if err != nil || !ok || !rehash {
		t.Fatalf("ok=%v rehash=%v err=%v", ok, rehash, err)
	}
}

func TestMalformedHash(t *testing.T) {
	h := NewHasher(fastParams, 1)
	for _, bad := range []string{"", "plain", "$argon2i$v=19$m=1,t=1,p=1$AA$AA", "$argon2id$v=19$m=x$AA$AA"} {
		if _, _, err := h.Verify(context.Background(), "x", bad); err == nil {
			t.Errorf("expected error for %q", bad)
		}
	}
}

func TestValidatePassword(t *testing.T) {
	if ValidatePassword("short") == nil {
		t.Error("short password accepted")
	}
	if ValidatePassword("long enough pw") != nil {
		t.Error("valid password rejected")
	}
	if ValidatePassword(strings.Repeat("a", 257)) == nil {
		t.Error("overlong password accepted")
	}
}

func TestTokens(t *testing.T) {
	tok, hash := NewToken(PrefixAccess)
	if !strings.HasPrefix(tok, "fca_") || len(tok) != 4+43 {
		t.Fatalf("token format: %q", tok)
	}
	if !bytes.Equal(hash, HashToken(tok)) {
		t.Fatal("hash mismatch")
	}
	tok2, _ := NewToken(PrefixAccess)
	if tok == tok2 {
		t.Fatal("tokens must be random")
	}
}

func TestCodes(t *testing.T) {
	c := NewCode(3)
	if len(c) != 14 || c[4] != '-' || c[9] != '-' {
		t.Fatalf("code format: %q", c)
	}
	if strings.ContainsAny(c, "ILOU") {
		t.Fatalf("ambiguous characters in %q", c)
	}
	if NormalizeCode(" k7qf-2m9x-hd4r ") != "K7QF2M9XHD4R" {
		t.Fatal("normalisation failed")
	}
	if NormalizeCode("O1L-I") != "0111" {
		t.Fatal("confusable folding failed")
	}
	if !bytes.Equal(HashCode("k7qf-2m9x-hd4r"), HashCode("K7QF2M9XHD4R")) {
		t.Fatal("hash must be on normalised code")
	}
}
