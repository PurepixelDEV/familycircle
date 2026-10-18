import type { Location } from "../api/client";

export type Freshness = Location["freshness"];

export const LIVE_MS = 2 * 60_000;
export const RECENT_MS = 15 * 60_000;

/**
 * Freshness from the fix's own timestamp, evaluated now. The server value is only a snapshot;
 * re-evaluating locally guarantees that a position that ages while the page is open is never
 * still shown as live. The stricter of server and local value wins.
 */
export function freshnessOf(recordedAt: string, now: number, serverValue?: Freshness): Freshness {
  const age = now - Date.parse(recordedAt);
  const local: Freshness = age < LIVE_MS ? "live" : age < RECENT_MS ? "recent" : "stale";
  if (!serverValue) return local;
  const rank = { live: 0, recent: 1, stale: 2 } as const;
  return rank[serverValue] > rank[local] ? serverValue : local;
}

/** Short relative time such as "vor 5 Min." / "5 min ago". */
export function relativeTime(iso: string, now: number, locale: string, justNow: string): string {
  const diff = Math.max(0, now - Date.parse(iso));
  if (diff < 45_000) return justNow;
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: "auto", style: "short" });
  const min = Math.round(diff / 60_000);
  if (min < 60) return rtf.format(-min, "minute");
  const h = Math.round(diff / 3_600_000);
  if (h < 24) return rtf.format(-h, "hour");
  return rtf.format(-Math.round(diff / 86_400_000), "day");
}
