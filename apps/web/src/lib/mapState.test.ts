import type { FamilyMap, Location } from "../api/client";
import { applyLiveEvent } from "./mapState";

const loc = (lat: number, recorded_at: string): Location => ({
  lat, lon: 8, accuracy_m: 10, motion: "unknown", recorded_at, received_at: recorded_at, freshness: "live",
});

const base: FamilyMap = {
  family_id: "f1",
  server_time: "2026-10-18T12:00:00Z",
  members: [
    { user_id: "a", display_name: "Anna", color: "#2f6fde", has_avatar: false, is_self: true, location: loc(47, "2026-10-18T11:59:00Z") },
    { user_id: "b", display_name: "Bob", color: "#e0534a", has_avatar: false, is_self: false, location: null },
  ],
};

describe("applyLiveEvent", () => {
  it("applies a newer location", () => {
    const { map, effect } = applyLiveEvent(base, { type: "location", user_id: "a", location: loc(48, "2026-10-18T12:00:00Z") });
    expect(effect).toBe("changed");
    expect(map.members[0]!.location!.lat).toBe(48);
    expect(base.members[0]!.location!.lat).toBe(47); // input not mutated
  });

  it("ignores an older (out-of-order) location", () => {
    const { map, effect } = applyLiveEvent(base, { type: "location", user_id: "a", location: loc(46, "2026-10-18T11:00:00Z") });
    expect(effect).toBe("none");
    expect(map).toBe(base);
  });

  it("shows a member that starts sharing", () => {
    const { map } = applyLiveEvent(base, { type: "location", user_id: "b", location: loc(46, "2026-10-18T12:00:00Z") });
    expect(map.members[1]!.location).not.toBeNull();
  });

  it("removes the position immediately on location_hidden", () => {
    const { map, effect } = applyLiveEvent(base, { type: "location_hidden", user_id: "a" });
    expect(effect).toBe("changed");
    expect(map.members[0]!.location).toBeNull();
  });

  it("ignores events about users outside this family", () => {
    expect(applyLiveEvent(base, { type: "location", user_id: "zzz", location: loc(1, "2026-10-18T12:00:00Z") }).effect).toBe("none");
  });

  it("requests a refetch when this family changed", () => {
    expect(applyLiveEvent(base, { type: "family_changed", family_id: "f1" }).effect).toBe("refetch");
    expect(applyLiveEvent(base, { type: "family_changed", family_id: "other" }).effect).toBe("none");
  });
});
