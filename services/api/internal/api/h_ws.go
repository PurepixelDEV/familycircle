package api

import (
	"context"
	"net/http"
	"time"

	"github.com/coder/websocket"

	"familycircle/internal/live"
)

const (
	wsPingInterval = 25 * time.Second
	wsWriteTimeout = 10 * time.Second
)

// handleWebSocket streams live events. Every 25 s the session is re-validated against the
// database, so an expired or revoked session loses its connection even without an explicit event.
func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	p := principalFrom(r)
	opts := &websocket.AcceptOptions{} // default: Origin must match Host (blocks cross-site WebSocket hijacking)
	if s.cfg.PublicURL != nil {
		opts.OriginPatterns = []string{s.cfg.PublicURL.Host}
	}
	conn, err := websocket.Accept(w, r, opts)
	if err != nil {
		return // Accept already wrote the HTTP error
	}
	defer conn.CloseNow()
	conn.SetReadLimit(4096)

	client := live.NewClient(p.user.ID, p.session.ID)
	s.hub.Register(client)
	defer s.hub.Unregister(client)

	// We never expect client messages; CloseRead handles pings/close frames and cancels ctx on disconnect.
	ctx := conn.CloseRead(context.WithoutCancel(r.Context()))

	write := func(msg []byte) error {
		wctx, cancel := context.WithTimeout(ctx, wsWriteTimeout)
		defer cancel()
		return conn.Write(wctx, websocket.MessageText, msg)
	}
	if err := write(mustJSON(map[string]any{"type": "hello", "user_id": p.user.ID, "server_time": s.now()})); err != nil {
		return
	}

	ticker := time.NewTicker(wsPingInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-client.Done():
			conn.Close(websocket.StatusPolicyViolation, "session ended")
			return
		case msg := <-client.Messages():
			if err := write(msg); err != nil {
				return
			}
		case <-ticker.C:
			active, err := s.store.SessionActive(ctx, p.session.ID)
			if err != nil || !active {
				conn.Close(websocket.StatusPolicyViolation, "session ended")
				return
			}
			pctx, cancel := context.WithTimeout(ctx, wsWriteTimeout)
			err = conn.Ping(pctx)
			cancel()
			if err != nil {
				return
			}
		}
	}
}
