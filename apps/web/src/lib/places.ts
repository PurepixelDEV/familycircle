import type { Place, PlaceInput } from "../api/client";

export const RADIUS_MIN = 50;
export const RADIUS_MAX = 2000;
export const RADIUS_DEFAULT = 150;

export type Center = { lat: number; lon: number };

/** Only the fields that changed: a new centre or radius resets presence on the server. */
export function placeChanges(before: Place, after: { name: string; lat: number; lon: number; radius_m: number }): PlaceInput {
  const out: PlaceInput = {};
  if (after.name.trim() !== before.name) out.name = after.name.trim();
  if (after.lat !== before.lat || after.lon !== before.lon) {
    out.lat = after.lat;
    out.lon = after.lon;
  }
  if (after.radius_m !== before.radius_m) out.radius_m = after.radius_m;
  return out;
}
