// Package jobs runs periodic maintenance inside the server process.
package jobs

import (
	"context"
	"log/slog"
	"time"

	"familycircle/internal/ratelimit"
	"familycircle/internal/store"
)

// Run enforces data retention every interval until ctx is cancelled and sweeps rate limiter state.
func Run(ctx context.Context, st *store.Store, limiters []*ratelimit.Limiter, interval time.Duration, log *slog.Logger) {
	tick := time.NewTicker(interval)
	defer tick.Stop()
	for {
		res, err := st.Purge(ctx)
		if err != nil && ctx.Err() == nil {
			log.Error("retention job failed", "err", err)
		} else if res.History+res.Sessions+res.Invites > 0 {
			log.Info("retention job", "history_deleted", res.History, "sessions_deleted", res.Sessions, "invites_deleted", res.Invites)
		}
		for _, l := range limiters {
			l.Sweep()
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}
