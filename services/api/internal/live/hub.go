// Package live fans out real-time events to connected WebSocket clients.
//
// The hub holds no authorization state. Callers compute the recipients for every event from the
// database at send time, so a revoked share can never be served from a stale cache.
package live

import (
	"sync"

	"github.com/google/uuid"
)

// Client is one connection. Send must never block the hub: when the buffer is full the client is
// considered too slow and is disconnected (it will reconnect and refetch).
type Client struct {
	UserID    uuid.UUID
	SessionID uuid.UUID
	send      chan []byte
	closeOnce sync.Once
	done      chan struct{}
}

func NewClient(userID, sessionID uuid.UUID) *Client {
	return &Client{UserID: userID, SessionID: sessionID, send: make(chan []byte, 64), done: make(chan struct{})}
}

// Messages delivers outgoing messages; Done is closed when the hub drops the client.
func (c *Client) Messages() <-chan []byte { return c.send }
func (c *Client) Done() <-chan struct{}   { return c.done }

func (c *Client) close() { c.closeOnce.Do(func() { close(c.done) }) }

type Hub struct {
	mu     sync.RWMutex
	byUser map[uuid.UUID]map[*Client]struct{}
}

func NewHub() *Hub {
	return &Hub{byUser: make(map[uuid.UUID]map[*Client]struct{})}
}

func (h *Hub) Register(c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	set := h.byUser[c.UserID]
	if set == nil {
		set = make(map[*Client]struct{})
		h.byUser[c.UserID] = set
	}
	set[c] = struct{}{}
}

func (h *Hub) Unregister(c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.removeLocked(c)
}

func (h *Hub) removeLocked(c *Client) {
	if set := h.byUser[c.UserID]; set != nil {
		delete(set, c)
		if len(set) == 0 {
			delete(h.byUser, c.UserID)
		}
	}
	c.close()
}

// SendTo delivers msg to all connections of the given users.
func (h *Hub) SendTo(users []uuid.UUID, msg []byte) {
	var slow []*Client
	h.mu.RLock()
	for _, u := range users {
		for c := range h.byUser[u] {
			select {
			case c.send <- msg:
			default:
				slow = append(slow, c)
			}
		}
	}
	h.mu.RUnlock()
	if len(slow) > 0 {
		h.mu.Lock()
		for _, c := range slow {
			h.removeLocked(c)
		}
		h.mu.Unlock()
	}
}

// CloseSession disconnects every connection authenticated by the session (logout, revocation).
func (h *Hub) CloseSession(sessionID uuid.UUID) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, set := range h.byUser {
		for c := range set {
			if c.SessionID == sessionID {
				h.removeLocked(c)
			}
		}
	}
}

// CloseUser disconnects all connections of a user (account deletion).
func (h *Hub) CloseUser(userID uuid.UUID) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.byUser[userID] {
		h.removeLocked(c)
	}
}

// Connected reports whether the user has at least one live connection.
func (h *Hub) Connected(userID uuid.UUID) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.byUser[userID]) > 0
}
