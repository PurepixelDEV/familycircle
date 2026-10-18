package live

import (
	"testing"

	"github.com/google/uuid"
)

func TestSendToOnlyTargetsUsers(t *testing.T) {
	h := NewHub()
	a, b := uuid.New(), uuid.New()
	ca := NewClient(a, uuid.New())
	cb := NewClient(b, uuid.New())
	h.Register(ca)
	h.Register(cb)

	h.SendTo([]uuid.UUID{a}, []byte("x"))
	if got := <-ca.Messages(); string(got) != "x" {
		t.Fatalf("a got %q", got)
	}
	select {
	case m := <-cb.Messages():
		t.Fatalf("b must not receive, got %q", m)
	default:
	}
}

func TestCloseSessionAndUser(t *testing.T) {
	h := NewHub()
	u := uuid.New()
	s1, s2 := uuid.New(), uuid.New()
	c1, c2 := NewClient(u, s1), NewClient(u, s2)
	h.Register(c1)
	h.Register(c2)

	h.CloseSession(s1)
	select {
	case <-c1.Done():
	default:
		t.Fatal("c1 should be closed")
	}
	if !h.Connected(u) {
		t.Fatal("c2 still connected")
	}
	h.CloseUser(u)
	<-c2.Done()
	if h.Connected(u) {
		t.Fatal("user should be fully disconnected")
	}
}

func TestSlowClientIsDropped(t *testing.T) {
	h := NewHub()
	u := uuid.New()
	c := NewClient(u, uuid.New())
	h.Register(c)
	for i := 0; i < cap(c.send)+1; i++ {
		h.SendTo([]uuid.UUID{u}, []byte("m"))
	}
	select {
	case <-c.Done():
	default:
		t.Fatal("slow client should be dropped")
	}
}
