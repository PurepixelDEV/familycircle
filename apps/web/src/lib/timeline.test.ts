import type { HistoryPoint, Place } from "../api/client";
import { addDays, buildTimeline, formatDistance, formatDuration, mondayOf, pathM, positionAt, stepM, summarize, usablePoints, weekBounds, type TPoint } from "./timeline";

const T0 = Date.parse("2026-11-16T06:00:00Z");
const min = (n: number) => T0 + n * 60_000;
const HOME = { lat: 47.0, lon: 8.0 };
const SCHOOL = { lat: 47.0135, lon: 8.0 }; // ~1.5 km north

function hp(at: number, lat: number, lon: number, extra: Partial<HistoryPoint> = {}): HistoryPoint {
  const iso = new Date(at).toISOString();
  return { id: String(at), recorded_at: iso, received_at: iso, lat, lon, accuracy_m: 10, motion: "unknown", freshness: "stale", suspect: false, ...extra };
}

function tp(at: number, lat: number, lon: number, extra: Partial<TPoint> = {}): TPoint {
  return { t: at, lat, lon, acc: 10, speedKmh: null, battery: null, charging: null, motion: "unknown", ...extra };
}

/** Points every `step` minutes from `a` to `b` (inclusive) around a spot, with ~1 m jitter. */
function stayPoints(a: number, b: number, spot: { lat: number; lon: number }, step = 5): HistoryPoint[] {
  const out: HistoryPoint[] = [];
  for (let m = a, i = 0; m <= b; m += step, i++) out.push(hp(min(m), spot.lat + (i % 2) * 0.00001, spot.lon, { motion: "stationary", battery_pct: 80 - i }));
  return out;
}

const home: Place = { id: "home", family_id: "f", name: "Zuhause", lat: HOME.lat, lon: HOME.lon, radius_m: 100, created_by: null, created_at: "", muted: false, present: [] };

/** 07:00–08:00 at home, walk 08:00–08:20, school until 12:00, no data until 14:00, home again. */
function schoolDay(): HistoryPoint[] {
  const walk = Array.from({ length: 9 }, (_, i) => hp(min(122 + i * 2), HOME.lat + (i + 1) * 0.00135, HOME.lon, { motion: "walking", speed_mps: 1.3 }));
  return [...stayPoints(60, 120, HOME), ...walk, ...stayPoints(140, 360, SCHOOL), ...stayPoints(480, 540, HOME)];
}

describe("usable points", () => {
  it("drops GPS jumps and vague positions and sorts oldest first", () => {
    const pts = usablePoints([hp(min(2), 47, 8), hp(min(1), 47, 8), hp(min(3), 10, 10, { suspect: true }), hp(min(4), 47, 8, { accuracy_m: 900 })]);
    expect(pts.map((p) => p.t)).toEqual([min(1), min(2)]);
  });
});

describe("distance rule", () => {
  it("ignores jitter shorter than the accuracy and counts real steps", () => {
    expect(stepM(tp(0, 47, 8), tp(1, 47.0001, 8))).toBe(0); // 11 m
    expect(stepM(tp(0, 47, 8, { acc: 60 }), tp(1, 47.0005, 8))).toBe(0); // 56 m < 60 m accuracy
    expect(stepM(tp(0, 47, 8), tp(1, 47.001, 8))).toBeCloseTo(111.2, 0);
    expect(pathM([tp(0, 47, 8), tp(1, 47.0001, 8), tp(2, 47.01, 8)])).toBeGreaterThan(1090);
  });
});

describe("timeline", () => {
  it("turns a school day into stays, a walk and a gap", () => {
    const pts = usablePoints(schoolDay());
    const segs = buildTimeline(pts, [home]);
    expect(segs.map((s) => s.kind)).toEqual(["stay", "move", "stay", "gap", "stay"]);
    const [h, walk, school, gap, back] = segs;
    expect(h).toMatchObject({ kind: "stay", from: min(60), to: min(120), place: { name: "Zuhause" } });
    expect(walk).toMatchObject({ kind: "move", from: min(120), to: min(140), mode: "walk" });
    if (walk?.kind !== "move") throw new Error();
    expect(walk.distanceM).toBeGreaterThan(1400);
    expect(walk.distanceM).toBeLessThan(1600);
    expect(walk.avgKmh).toBeGreaterThan(4);
    expect(walk.avgKmh).toBeLessThan(5);
    expect(school).toMatchObject({ kind: "stay", from: min(140), to: min(360), place: null });
    expect(gap).toMatchObject({ kind: "gap", from: min(360), to: min(480) });
    expect(back).toMatchObject({ kind: "stay", place: { id: "home" } });

    const sum = summarize(pts, segs);
    expect(sum.stays).toBe(3);
    expect(sum.places).toBe(1);
    expect(sum.movingMs).toBe(20 * 60_000);
    expect(sum.first).toBe(min(60));
    expect(sum.last).toBe(min(540));
    expect(sum.battery[0]).toEqual({ t: min(60), pct: 80, charging: null });
    // The day's distance uses the shared rule over all positions: walk + the jump across the gap.
    expect(sum.distanceM).toBeGreaterThan(2900);
  });

  it("merges a short excursion into the stay around it", () => {
    const pts = usablePoints([...stayPoints(0, 30, HOME), hp(min(32), HOME.lat + 0.0014, HOME.lon), ...stayPoints(34, 60, HOME)]);
    expect(buildTimeline(pts, []).map((s) => s.kind)).toEqual(["stay"]);
  });

  it("classifies fast moves as rides", () => {
    const ride = Array.from({ length: 6 }, (_, i) => hp(min(i * 2), 47 + i * 0.01, 8)); // 1.1 km per 2 min ≈ 33 km/h
    const segs = buildTimeline(usablePoints(ride), []);
    expect(segs).toHaveLength(1);
    expect(segs[0]).toMatchObject({ kind: "move", mode: "ride" });
  });

  it("shows a single position as a short stay", () => {
    const segs = buildTimeline(usablePoints([hp(min(0), 47, 8)]), [home]);
    expect(segs).toEqual([expect.objectContaining({ kind: "stay", from: min(0), to: min(0) })]);
  });

  it("is empty without positions", () => {
    expect(buildTimeline([], [home])).toEqual([]);
  });
});

describe("playhead", () => {
  const pts = [tp(min(0), 47, 8, { battery: 50 }), tp(min(10), 47.01, 8, { battery: 49 }), tp(min(100), 47.02, 8)];

  it("interpolates between positions", () => {
    const p = positionAt(pts, min(5))!;
    expect(p.lat).toBeCloseTo(47.005, 6);
    expect(p.battery).toBe(50);
    expect(p.noData).toBe(false);
    expect(p.speedKmh).toBeCloseTo(6.7, 1);
  });

  it("holds the last position during a gap", () => {
    const p = positionAt(pts, min(50))!;
    expect(p.lat).toBe(47.01);
    expect(p.noData).toBe(true);
  });

  it("clamps to the first and last position", () => {
    expect(positionAt(pts, min(-5))!.lat).toBe(47);
    expect(positionAt(pts, min(500))!.lat).toBe(47.02);
    expect(positionAt([], 0)).toBeNull();
  });
});

describe("calendar helpers", () => {
  it("finds the Monday of a week", () => {
    expect(mondayOf("2026-11-19")).toBe("2026-11-16"); // Thursday
    expect(mondayOf("2026-11-16")).toBe("2026-11-16");
    expect(mondayOf("2026-11-22")).toBe("2026-11-16"); // Sunday
    expect(mondayOf("2027-01-01")).toBe("2026-12-28");
  });

  it("adds days across months", () => {
    expect(addDays("2026-11-30", 1)).toBe("2026-12-01");
    expect(addDays("2026-03-01", -1)).toBe("2026-02-28");
  });

  it("gives 8 increasing local midnights for a week", () => {
    const b = weekBounds("2026-10-19");
    expect(b).toHaveLength(8);
    for (let i = 1; i < b.length; i++) expect(Date.parse(b[i]!)).toBeGreaterThan(Date.parse(b[i - 1]!));
    expect(new Date(b[0]!).getHours()).toBe(0);
  });
});

describe("formatting", () => {
  it("formats distances and durations", () => {
    expect(formatDistance(843, "de")).toBe("840 m");
    expect(formatDistance(3420, "de")).toBe("3,4 km");
    expect(formatDistance(23_600, "de")).toBe("24 km");
    expect(formatDuration(20_000)).toBe("< 1 min");
    expect(formatDuration(12 * 60_000)).toBe("12 min");
    expect(formatDuration(85 * 60_000)).toBe("1 h 25 min");
    expect(formatDuration(120 * 60_000)).toBe("2 h");
  });
});
