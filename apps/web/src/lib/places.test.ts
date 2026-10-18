import type { Place } from "../api/client";
import { placeChanges } from "./places";

const place: Place = {
  id: "p1", family_id: "f1", name: "Zuhause", lat: 47.37, lon: 8.54, radius_m: 150,
  created_by: "me", created_at: "2026-10-18T10:00:00Z", muted: false, present: [],
};

describe("placeChanges", () => {
  it("sends nothing when nothing changed (moving would reset presence)", () => {
    expect(placeChanges(place, { name: " Zuhause ", lat: 47.37, lon: 8.54, radius_m: 150 })).toEqual({});
  });

  it("sends only the changed fields, the centre always as a pair", () => {
    expect(placeChanges(place, { name: "Daheim", lat: 47.37, lon: 8.54, radius_m: 150 })).toEqual({ name: "Daheim" });
    expect(placeChanges(place, { name: "Zuhause", lat: 47.37, lon: 8.55, radius_m: 300 })).toEqual({ lat: 47.37, lon: 8.55, radius_m: 300 });
  });
});
