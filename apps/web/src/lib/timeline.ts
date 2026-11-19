import type { HistoryPoint, Place } from "../api/client";
import { distanceM } from "./geo";

/*
 * The history timeline of one day: positions → stays, moves and gaps. The same rules run in the
 * Android app (ui/history/TimelineModel.kt); the distance rule is also the server's
 * (GET /users/{id}/history/days), so the week overview and the day agree.
 */

/** Positions less accurate than this are left out (too vague for a route). */
export const MAX_ACCURACY_M = 200;
/** A step shorter than max(accuracy of both points, this) is noise of someone standing still. */
export const MIN_STEP_M = 25;
/** Positions within this distance of a group's centre belong to the same stay. */
export const STAY_RADIUS_M = 100;
/** A group of positions is a stay when it lasts at least this long. */
export const MIN_STAY_MS = 5 * 60_000;
/** Without a position for longer than this while moving, the timeline shows a gap. */
export const GAP_MS = 30 * 60_000;
/** A stay counts as being at a place up to this far outside its radius. */
export const PLACE_MARGIN_M = 25;
/** Average speed above which a move counts as a ride (vehicle, bike) rather than a walk. */
export const WALK_MAX_KMH = 7;
/** Top speed above which a move is a ride even if its average is low (traffic lights, jams). */
export const RIDE_TOP_KMH = 25;

export type TPoint = {
  t: number;
  lat: number;
  lon: number;
  acc: number;
  speedKmh: number | null;
  battery: number | null;
  charging: boolean | null;
  motion: HistoryPoint["motion"];
};

export type Stay = { kind: "stay"; from: number; to: number; lat: number; lon: number; place: Place | null; start: number; end: number };
export type Move = { kind: "move"; from: number; to: number; distanceM: number; mode: "walk" | "ride"; avgKmh: number; topKmh: number | null; start: number; end: number };
export type Gap = { kind: "gap"; from: number; to: number; distanceM: number; start: number; end: number };
/** `start`/`end` are inclusive indices into the points the timeline was built from. */
export type Segment = Stay | Move | Gap;

export type DaySummary = {
  distanceM: number;
  movingMs: number;
  stays: number;
  places: number;
  first: number | null;
  last: number | null;
  battery: { t: number; pct: number; charging: boolean | null }[];
};

/** Plausible positions, oldest first: no `suspect` GPS jumps, nothing vaguer than MAX_ACCURACY_M. */
export function usablePoints(points: HistoryPoint[]): TPoint[] {
  const out: TPoint[] = [];
  for (const p of points) {
    const t = Date.parse(p.recorded_at);
    if (p.suspect || !(p.accuracy_m <= MAX_ACCURACY_M) || Number.isNaN(t)) continue;
    out.push({
      t,
      lat: p.lat,
      lon: p.lon,
      acc: p.accuracy_m,
      speedKmh: p.speed_mps == null ? null : p.speed_mps * 3.6,
      battery: p.battery_pct ?? null,
      charging: p.battery_charging ?? null,
      motion: p.motion,
    });
  }
  return out.sort((a, b) => a.t - b.t);
}

/** Length of the step a → b if it counts as movement, else 0 (shared distance rule). */
export function stepM(a: TPoint, b: TPoint): number {
  const d = distanceM(a.lat, a.lon, b.lat, b.lon);
  return d >= Math.max(a.acc, b.acc, MIN_STEP_M) ? d : 0;
}

/** Distance travelled along pts[start..end] (inclusive). */
export function pathM(pts: TPoint[], start = 0, end = pts.length - 1): number {
  let sum = 0;
  for (let i = start + 1; i <= end; i++) sum += stepM(pts[i - 1]!, pts[i]!);
  return sum;
}

type Cluster = { start: number; end: number; lat: number; lon: number; n: number };

/** Consecutive positions within STAY_RADIUS_M of the running centre of their group. */
function clusters(pts: TPoint[]): Cluster[] {
  const out: Cluster[] = [];
  let cur: Cluster | null = null;
  pts.forEach((p, i) => {
    if (cur && distanceM(cur.lat, cur.lon, p.lat, p.lon) <= STAY_RADIUS_M) {
      cur.lat = (cur.lat * cur.n + p.lat) / (cur.n + 1);
      cur.lon = (cur.lon * cur.n + p.lon) / (cur.n + 1);
      cur.n++;
      cur.end = i;
      return;
    }
    cur = { start: i, end: i, lat: p.lat, lon: p.lon, n: 1 };
    out.push(cur);
  });
  return out;
}

/** The nearest place whose circle (plus PLACE_MARGIN_M) contains the point. */
export function placeAt(lat: number, lon: number, places: Place[]): Place | null {
  let best: Place | null = null;
  let bestD = Infinity;
  for (const pl of places) {
    const d = distanceM(lat, lon, pl.lat, pl.lon);
    if (d <= pl.radius_m + PLACE_MARGIN_M && d < bestD) {
      best = pl;
      bestD = d;
    }
  }
  return best;
}

/** Stays: groups lasting MIN_STAY_MS; neighbours at the same spot are merged (a short excursion). */
function findStays(pts: TPoint[]): Cluster[] {
  const all = clusters(pts);
  let stays = all.filter((c) => pts[c.end]!.t - pts[c.start]!.t >= MIN_STAY_MS);
  // A single short group (one position, or a day that only just started) is still a stay.
  if (stays.length === 0 && all.length === 1) stays = all;
  const merged: Cluster[] = [];
  for (const c of stays) {
    const prev = merged[merged.length - 1];
    if (prev && distanceM(prev.lat, prev.lon, c.lat, c.lon) <= STAY_RADIUS_M) {
      const n = prev.n + c.n;
      prev.lat = (prev.lat * prev.n + c.lat * c.n) / n;
      prev.lon = (prev.lon * prev.n + c.lon * c.n) / n;
      prev.n = n;
      prev.end = c.end;
    } else merged.push({ ...c });
  }
  return merged;
}

function move(pts: TPoint[], start: number, end: number): Move {
  const from = pts[start]!.t;
  const to = pts[end]!.t;
  const distance = pathM(pts, start, end);
  const hours = (to - from) / 3_600_000;
  const avgKmh = hours > 0 ? distance / 1000 / hours : 0;
  let top: number | null = null;
  let driving = 0;
  let walking = 0;
  for (let i = start; i <= end; i++) {
    const p = pts[i]!;
    if (p.speedKmh != null) top = Math.max(top ?? 0, p.speedKmh);
    if (p.motion === "driving") driving++;
    else if (p.motion === "walking") walking++;
  }
  const ride = avgKmh > WALK_MAX_KMH || (top ?? 0) > RIDE_TOP_KMH || driving > walking;
  return { kind: "move", from, to, distanceM: distance, mode: ride ? "ride" : "walk", avgKmh, topKmh: top, start, end };
}

/** pts[start..end] as moves, split into move / gap / move wherever no position came for GAP_MS. */
function moves(pts: TPoint[], start: number, end: number, out: Segment[]) {
  let s = start;
  for (let i = start + 1; i <= end; i++) {
    if (pts[i]!.t - pts[i - 1]!.t <= GAP_MS) continue;
    if (i - 1 > s) out.push(move(pts, s, i - 1));
    const a = pts[i - 1]!;
    const b = pts[i]!;
    out.push({ kind: "gap", from: a.t, to: b.t, distanceM: distanceM(a.lat, a.lon, b.lat, b.lon), start: i - 1, end: i });
    s = i;
  }
  if (end > s) out.push(move(pts, s, end));
}

/** The day as stays, moves and gaps in time order (points from `usablePoints`). */
export function buildTimeline(pts: TPoint[], places: Place[]): Segment[] {
  const out: Segment[] = [];
  if (pts.length === 0) return out;
  let prevEnd = 0;
  for (const c of findStays(pts)) {
    if (c.start > prevEnd) moves(pts, prevEnd, c.start, out);
    out.push({ kind: "stay", from: pts[c.start]!.t, to: pts[c.end]!.t, lat: c.lat, lon: c.lon, place: placeAt(c.lat, c.lon, places), start: c.start, end: c.end });
    prevEnd = c.end;
  }
  if (out.length === 0) moves(pts, 0, pts.length - 1, out);
  else if (prevEnd < pts.length - 1) moves(pts, prevEnd, pts.length - 1, out);
  return out;
}

/** Totals of the day. Distance = the shared rule over all positions (equals the week overview). */
export function summarize(pts: TPoint[], segments: Segment[]): DaySummary {
  const stays = segments.filter((s): s is Stay => s.kind === "stay");
  return {
    distanceM: pathM(pts),
    movingMs: segments.reduce((sum, s) => (s.kind === "move" ? sum + (s.to - s.from) : sum), 0),
    stays: stays.length,
    places: new Set(stays.flatMap((s) => (s.place ? [s.place.id] : []))).size,
    first: pts[0]?.t ?? null,
    last: pts[pts.length - 1]?.t ?? null,
    battery: pts.flatMap((p) => (p.battery == null ? [] : [{ t: p.t, pct: p.battery, charging: p.charging }])),
  };
}

export type Playhead = { lat: number; lon: number; battery: number | null; speedKmh: number | null; noData: boolean };

/**
 * Where the person was at time t: interpolated between the neighbouring positions, or held at the
 * last one during a gap (noData). Battery and speed come from the last position before t.
 */
export function positionAt(pts: TPoint[], t: number): Playhead | null {
  if (pts.length === 0) return null;
  let lo = 0;
  let hi = pts.length - 1;
  if (t <= pts[0]!.t) lo = hi = 0;
  else if (t >= pts[hi]!.t) lo = hi;
  else {
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (pts[mid]!.t <= t) lo = mid;
      else hi = mid;
    }
  }
  const a = pts[lo]!;
  const b = pts[hi]!;
  const gap = b.t - a.t > GAP_MS;
  const f = lo === hi || gap ? 0 : (t - a.t) / (b.t - a.t);
  return {
    lat: a.lat + (b.lat - a.lat) * f,
    lon: a.lon + (b.lon - a.lon) * f,
    battery: a.battery,
    speedKmh: lo === hi || gap ? null : stepM(a, b) > 0 ? distanceM(a.lat, a.lon, b.lat, b.lon) / 1000 / ((b.t - a.t) / 3_600_000) : 0,
    noData: gap && t > a.t,
  };
}

/** Bounds for GET /history/days: local midnights from `monday` for `days` days (plus the end). */
export function weekBounds(monday: string, days = 7): string[] {
  const [y, m, d] = monday.split("-").map(Number) as [number, number, number];
  return Array.from({ length: days + 1 }, (_, i) => new Date(y, m - 1, d + i).toISOString());
}

/** The Monday (YYYY-MM-DD) of the week that contains `date`. */
export function mondayOf(date: string): string {
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  const day = new Date(y, m - 1, d, 12);
  const back = (day.getDay() + 6) % 7;
  const mon = new Date(y, m - 1, d - back, 12);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${mon.getFullYear()}-${p(mon.getMonth() + 1)}-${p(mon.getDate())}`;
}

/** `date` shifted by `n` days (YYYY-MM-DD, local calendar). */
export function addDays(date: string, n: number): string {
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  const x = new Date(y, m - 1, d + n, 12);
  const p = (v: number) => String(v).padStart(2, "0");
  return `${x.getFullYear()}-${p(x.getMonth() + 1)}-${p(x.getDate())}`;
}

/** "3,4 km" / "850 m" – distances in the timeline. */
export function formatDistance(m: number, locale: string): string {
  if (m < 1000) return `${Math.round(m / 10) * 10} m`;
  return `${(m / 1000).toLocaleString(locale, { maximumFractionDigits: m < 10_000 ? 1 : 0 })} km`;
}

/** "1 h 25 min" / "12 min" / "< 1 min". */
export function formatDuration(ms: number): string {
  const min = Math.round(ms / 60_000);
  if (min < 1) return "< 1 min";
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60);
  const rest = min % 60;
  return rest === 0 ? `${h} h` : `${h} h ${rest} min`;
}
