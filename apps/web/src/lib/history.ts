import type { HistoryPoint } from "../api/client";
import { distanceM } from "./geo";

/** Local calendar date as YYYY-MM-DD (value format of <input type="date">). */
export function localDate(ts: number): string {
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** The local day before `date` (YYYY-MM-DD). */
export function previousDay(date: string): string {
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  return localDate(new Date(y, m - 1, d - 1, 12).getTime());
}

/**
 * The local day as an RFC 3339 range [from, to) in UTC – from local midnight to the next local
 * midnight, so days with a daylight-saving change are 23 or 25 hours long.
 */
export function dayRange(date: string): { from: string; to: string } {
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  return { from: new Date(y, m - 1, d).toISOString(), to: new Date(y, m - 1, d + 1).toISOString() };
}

export type Stay = { from: string; to: string; lat: number; lon: number; accuracy_m: number; count: number };

/**
 * Compresses a track (oldest first, without implausible points) into consecutive stays: points
 * within `radiusM` of the first point of a group belong to it. Gives a short, readable timeline.
 */
export function groupStays(points: HistoryPoint[], radiusM = 100): Stay[] {
  const out: Stay[] = [];
  let cur: Stay | null = null;
  for (const p of points) {
    if (cur && distanceM(cur.lat, cur.lon, p.lat, p.lon) <= radiusM) {
      cur.to = p.recorded_at;
      cur.count++;
      cur.accuracy_m = Math.min(cur.accuracy_m, p.accuracy_m);
      continue;
    }
    cur = { from: p.recorded_at, to: p.recorded_at, lat: p.lat, lon: p.lon, accuracy_m: p.accuracy_m, count: 1 };
    out.push(cur);
  }
  return out;
}

/** Oldest-first track for the line; implausible GPS jumps (`suspect`) are left out. */
export function trackOf(points: HistoryPoint[]): HistoryPoint[] {
  return points.filter((p) => !p.suspect).sort((a, b) => Date.parse(a.recorded_at) - Date.parse(b.recorded_at));
}
