import type { HistoryPoint } from "../api/client";
import { dayRange, groupStays, localDate, previousDay, trackOf } from "./history";

function point(id: string, recorded_at: string, lat: number, lon: number, suspect = false): HistoryPoint {
  return { id, recorded_at, received_at: recorded_at, lat, lon, accuracy_m: 10, motion: "unknown", freshness: "stale", suspect };
}

describe("day range", () => {
  it("runs from local midnight to the next local midnight", () => {
    for (const day of ["2026-10-18", "2026-03-29", "2026-10-25", "2026-12-31"]) {
      const { from, to } = dayRange(day);
      const f = new Date(from);
      const t = new Date(to);
      expect(localDate(f.getTime())).toBe(day);
      expect([f.getHours(), f.getMinutes(), f.getSeconds()]).toEqual([0, 0, 0]);
      expect([t.getHours(), t.getMinutes()]).toEqual([0, 0]);
      expect(previousDay(localDate(t.getTime()))).toBe(day);
      // 24 h, or 23/25 h on a daylight-saving day.
      expect([23, 24, 25]).toContain((t.getTime() - f.getTime()) / 3_600_000);
      expect(from).toMatch(/Z$/); // RFC 3339 in UTC for the API
    }
  });

  it("computes yesterday across month and year boundaries", () => {
    expect(previousDay("2026-10-18")).toBe("2026-10-17");
    expect(previousDay("2026-03-01")).toBe("2026-02-28");
    expect(previousDay("2024-03-01")).toBe("2024-02-29");
    expect(previousDay("2026-01-01")).toBe("2025-12-31");
  });

  it("formats the local date like <input type=date>", () => {
    expect(localDate(new Date(2026, 0, 5, 23, 59).getTime())).toBe("2026-01-05");
  });
});

describe("track", () => {
  const newestFirst = [
    point("4", "2026-10-18T10:30:00Z", 47.4, 8.6),
    point("3", "2026-10-18T10:20:00Z", 10, 10, true), // GPS jump
    point("2", "2026-10-18T10:10:00Z", 47.3701, 8.5401),
    point("1", "2026-10-18T10:00:00Z", 47.37, 8.54),
  ];

  it("leaves out implausible points and sorts oldest first", () => {
    expect(trackOf(newestFirst).map((p) => p.id)).toEqual(["1", "2", "4"]);
  });

  it("groups nearby consecutive points into stays", () => {
    const stays = groupStays(trackOf(newestFirst));
    expect(stays).toHaveLength(2);
    expect(stays[0]).toMatchObject({ from: "2026-10-18T10:00:00Z", to: "2026-10-18T10:10:00Z", count: 2 });
    expect(stays[1]).toMatchObject({ from: "2026-10-18T10:30:00Z", to: "2026-10-18T10:30:00Z", count: 1 });
  });
});
