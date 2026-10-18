import { circlePolygon, distanceM } from "./geo";

describe("circlePolygon", () => {
  it("is a closed GeoJSON ring with steps + 1 coordinates", () => {
    const ring = circlePolygon(8.54, 47.37, 150, 32);
    expect(ring).toHaveLength(33);
    expect(ring[0]).toEqual(ring[32]);
  });

  it.each([
    [8.54, 47.37, 50],
    [8.54, 47.37, 2000],
    [24.94, 69.65, 500], // far north: longitudes must stretch, the circle stays round
    [-58.38, -34.6, 150],
  ])("puts every vertex at the radius (lon %f, lat %f, %i m)", (lon, lat, r) => {
    for (const [x, y] of circlePolygon(lon, lat, r)) {
      expect(Math.abs(distanceM(lat, lon, y, x) - r)).toBeLessThan(r * 0.001 + 0.01);
    }
  });

  it("starts north of the centre", () => {
    const [lon, lat] = circlePolygon(8, 47, 1000)[0]!;
    expect(lon).toBeCloseTo(8, 9);
    expect(lat).toBeGreaterThan(47);
  });
});

describe("distanceM", () => {
  it("measures known distances", () => {
    expect(distanceM(47.37, 8.54, 47.37, 8.54)).toBe(0);
    // One degree of latitude ≈ 111.2 km.
    expect(distanceM(47, 8, 48, 8)).toBeGreaterThan(111_000);
    expect(distanceM(47, 8, 48, 8)).toBeLessThan(111_400);
  });
});
