import { freshnessOf, relativeTime } from "./time";

const now = Date.parse("2026-10-18T12:00:00Z");
const ago = (ms: number) => new Date(now - ms).toISOString();

describe("freshnessOf", () => {
  it("classifies by the fix's own timestamp", () => {
    expect(freshnessOf(ago(30_000), now)).toBe("live");
    expect(freshnessOf(ago(5 * 60_000), now)).toBe("recent");
    expect(freshnessOf(ago(20 * 60_000), now)).toBe("stale");
  });

  it("never shows an aged position as live even if the server snapshot said live", () => {
    // Loaded while live, page stayed open for 20 minutes.
    expect(freshnessOf(ago(20 * 60_000), now, "live")).toBe("stale");
  });

  it("takes the stricter server value (client clock behind)", () => {
    expect(freshnessOf(ago(10_000), now, "stale")).toBe("stale");
  });
});

describe("relativeTime", () => {
  it("formats short relative ages", () => {
    expect(relativeTime(ago(10_000), now, "de", "gerade eben")).toBe("gerade eben");
    expect(relativeTime(ago(5 * 60_000), now, "en", "just now")).toMatch(/5 min/);
    expect(relativeTime(ago(3 * 3_600_000), now, "de", "gerade eben")).toMatch(/3 Std/);
  });
});
