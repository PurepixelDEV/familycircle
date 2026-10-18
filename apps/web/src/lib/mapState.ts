import type { FamilyMap } from "../api/client";
import type { LiveEvent } from "../api/live";

export type LiveEffect = "none" | "changed" | "refetch";

/**
 * Applies a live event to the current family map. Pure: returns the new map (or the same
 * object when nothing changed) plus whether a full refetch is needed.
 */
export function applyLiveEvent(map: FamilyMap, ev: LiveEvent): { map: FamilyMap; effect: LiveEffect } {
  switch (ev.type) {
    case "location": {
      const idx = map.members.findIndex((m) => m.user_id === ev.user_id);
      if (idx < 0) return { map, effect: "none" }; // member of another family
      const current = map.members[idx]!;
      // Never let an out-of-order (older) event replace a newer position.
      if (current.location && Date.parse(current.location.recorded_at) > Date.parse(ev.location.recorded_at)) {
        return { map, effect: "none" };
      }
      const members = map.members.slice();
      members[idx] = { ...current, location: ev.location };
      return { map: { ...map, members }, effect: "changed" };
    }
    case "location_hidden": {
      const idx = map.members.findIndex((m) => m.user_id === ev.user_id);
      if (idx < 0 || map.members[idx]!.location === null) return { map, effect: "none" };
      const members = map.members.slice();
      members[idx] = { ...members[idx]!, location: null };
      return { map: { ...map, members }, effect: "changed" };
    }
    case "family_changed":
      return { map, effect: ev.family_id === map.family_id ? "refetch" : "none" };
    default:
      return { map, effect: "none" };
  }
}
