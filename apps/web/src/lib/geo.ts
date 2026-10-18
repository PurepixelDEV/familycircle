const EARTH_M = 6_371_008.8;
const rad = (d: number) => (d * Math.PI) / 180;

/** Great-circle distance in metres (haversine). */
export function distanceM(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dLat = rad(lat2 - lat1);
  const dLon = rad(lon2 - lon1);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/**
 * Closed ring of [lon, lat] pairs approximating a circle of `m` metres around a point (a GeoJSON
 * polygon ring: first and last coordinate are equal). Each vertex is the destination point at the
 * given distance and bearing, so the shape stays round at any latitude.
 */
export function circlePolygon(lon: number, lat: number, m: number, steps = 64): [number, number][] {
  const coords: [number, number][] = [];
  const d = m / EARTH_M;
  const lat1 = rad(lat);
  const lon1 = rad(lon);
  for (let i = 0; i <= steps; i++) {
    const bearing = ((i % steps) / steps) * 2 * Math.PI;
    const lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(bearing));
    const lon2 = lon1 + Math.atan2(Math.sin(bearing) * Math.sin(d) * Math.cos(lat1), Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));
    coords.push([(lon2 * 180) / Math.PI, (lat2 * 180) / Math.PI]);
  }
  return coords;
}

export type GeoErrorCode = "unsupported" | "denied" | "unavailable" | "timeout";

export class GeoError extends Error {
  constructor(readonly code: GeoErrorCode) {
    super(code);
  }
}

/**
 * One position from the browser. Never cached by us; the browser may reuse a fix up to `maxAgeMs`.
 * `hardTimeoutMs` also limits the time the permission prompt may stay unanswered (the browser's own
 * timeout only starts once permission is granted) – for SOS, which must not wait.
 */
export function currentPosition(opts: { timeoutMs: number; maxAgeMs?: number; hardTimeoutMs?: number }): Promise<{ lat: number; lon: number; accuracy_m?: number }> {
  return new Promise((resolve, reject) => {
    if (!("geolocation" in navigator) || !window.isSecureContext) {
      reject(new GeoError("unsupported"));
      return;
    }
    const hard = opts.hardTimeoutMs ? setTimeout(() => reject(new GeoError("timeout")), opts.hardTimeoutMs) : undefined;
    navigator.geolocation.getCurrentPosition(
      (p) => {
        clearTimeout(hard);
        const acc = p.coords.accuracy;
        resolve({ lat: p.coords.latitude, lon: p.coords.longitude, ...(acc > 0 && acc <= 100_000 ? { accuracy_m: acc } : {}) });
      },
      (e) => {
        clearTimeout(hard);
        reject(new GeoError(e.code === e.PERMISSION_DENIED ? "denied" : e.code === e.TIMEOUT ? "timeout" : "unavailable"));
      },
      { enableHighAccuracy: true, timeout: opts.timeoutMs, maximumAge: opts.maxAgeMs ?? 30_000 },
    );
  });
}
