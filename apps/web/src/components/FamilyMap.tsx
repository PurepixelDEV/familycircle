import { useEffect, useEffectEvent, useRef } from "react";
import * as maplibregl from "maplibre-gl";
import type { GeoJSONSource, LngLatBoundsLike } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
// MapLibre 6 locates its worker relative to its own module file, which no longer exists after
// bundling. Ship the (self-contained) worker as a hashed asset and point MapLibre at it.
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?url";
import { avatarUrl, type MapMember } from "../api/client";
import { useI18n } from "../i18n";
import { freshnessOf, relativeTime } from "../lib/time";
import { initials } from "./ui";

/** Polygon approximating a circle of radius `m` metres (for accuracy rings). */
export function circlePolygon(lon: number, lat: number, m: number, steps = 48): [number, number][] {
  const coords: [number, number][] = [];
  const dLat = m / 111_320;
  const dLon = m / (111_320 * Math.cos((lat * Math.PI) / 180));
  for (let i = 0; i <= steps; i++) {
    const a = (i / steps) * 2 * Math.PI;
    coords.push([lon + dLon * Math.cos(a), lat + dLat * Math.sin(a)]);
  }
  return coords;
}

type Props = {
  styleUrl: string;
  members: MapMember[];
  now: number;
  selected: string | null;
  onSelect: (userId: string) => void;
  fitToken: number; // increment to fit all members into view
};

const ACCURACY_SOURCE = "fc-accuracy";

maplibregl.setWorkerUrl(workerUrl);

export function FamilyMap({ styleUrl, members, now, selected, onSelect, fitToken }: Props) {
  const { t, locale } = useI18n();
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markers = useRef(new Map<string, { marker: maplibregl.Marker; el: HTMLButtonElement }>());
  const fittedOnce = useRef(false);
  const select = useEffectEvent((id: string) => onSelect(id));

  useEffect(() => {
    if (!container.current) return;
    const map = new maplibregl.Map({
      container: container.current,
      style: styleUrl,
      center: [8.54, 47.37],
      zoom: 3,
      attributionControl: { compact: true },
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "bottom-right");
    map.on("load", () => {
      map.addSource(ACCURACY_SOURCE, { type: "geojson", data: { type: "FeatureCollection", features: [] } });
      map.addLayer({ id: "fc-accuracy-fill", type: "fill", source: ACCURACY_SOURCE, paint: { "fill-color": ["get", "color"], "fill-opacity": 0.12 } });
      map.addLayer({ id: "fc-accuracy-line", type: "line", source: ACCURACY_SOURCE, paint: { "line-color": ["get", "color"], "line-opacity": 0.45, "line-width": 1.5 } });
    });
    mapRef.current = map;
    const current = markers.current;
    return () => {
      current.clear();
      map.remove();
      mapRef.current = null;
    };
  }, [styleUrl]);

  // Markers and accuracy circles.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const visible = members.filter((m) => m.location);
    const seen = new Set<string>();
    for (const m of visible) {
      const loc = m.location!;
      seen.add(m.user_id);
      let entry = markers.current.get(m.user_id);
      if (!entry) {
        const el = document.createElement("button");
        el.type = "button";
        el.className = "fc-marker";
        el.addEventListener("click", (e) => {
          e.stopPropagation();
          select(m.user_id);
        });
        const marker = new maplibregl.Marker({ element: el, anchor: "bottom", offset: [0, -6] }).setLngLat([loc.lon, loc.lat]).addTo(map);
        entry = { marker, el };
        markers.current.set(m.user_id, entry);
      }
      const fresh = freshnessOf(loc.recorded_at, now, loc.freshness);
      entry.marker.setLngLat([loc.lon, loc.lat]);
      const el = entry.el;
      el.className = `fc-marker ${fresh}${selected === m.user_id ? " selected" : ""}`;
      el.style.setProperty("--ring", m.color);
      const age = relativeTime(loc.recorded_at, now, locale, t("time.justNow"));
      el.setAttribute("aria-label", `${m.display_name}: ${fresh === "stale" ? t("map.staleHint") : age}`);
      // Rebuild content only when it changes (avoid image flicker).
      const key = `${m.has_avatar}:${m.avatar_version}:${fresh}:${fresh === "live" ? "" : age}`;
      if (el.dataset.key !== key) {
        el.dataset.key = key;
        el.replaceChildren();
        if (fresh === "live") {
          const halo = document.createElement("span");
          halo.className = "halo";
          el.append(halo);
        } else {
          const ageEl = document.createElement("span");
          ageEl.className = "age";
          ageEl.textContent = age;
          el.append(ageEl);
        }
        const face = document.createElement("span");
        face.className = "face";
        if (m.has_avatar) {
          const img = document.createElement("img");
          img.alt = "";
          img.src = avatarUrl(m.user_id, m.avatar_version);
          img.onerror = () => face.replaceChildren(document.createTextNode(initials(m.display_name)));
          face.append(img);
        } else {
          face.textContent = initials(m.display_name);
        }
        el.append(face);
      }
    }
    for (const [id, entry] of markers.current) {
      if (!seen.has(id)) {
        entry.marker.remove();
        markers.current.delete(id);
      }
    }
    const updateCircles = () => {
      const src = map.getSource(ACCURACY_SOURCE) as GeoJSONSource | undefined;
      src?.setData({
        type: "FeatureCollection",
        features: visible
          .filter((m) => m.location!.accuracy_m >= 15)
          .map((m) => ({
            type: "Feature" as const,
            properties: { color: m.color },
            geometry: { type: "Polygon" as const, coordinates: [circlePolygon(m.location!.lon, m.location!.lat, m.location!.accuracy_m)] },
          })),
      });
    };
    if (map.isStyleLoaded() && map.getSource(ACCURACY_SOURCE)) updateCircles();
    else map.once("load", updateCircles);

    if (!fittedOnce.current && visible.length > 0) {
      fittedOnce.current = true;
      fit(map, visible, false);
    }
  }, [members, now, selected, locale, t]);

  // Fit on demand ("show everyone").
  useEffect(() => {
    const map = mapRef.current;
    if (!map || fitToken === 0) return;
    fit(map, members.filter((m) => m.location), true);
  }, [fitToken, members]);

  // Fly to the selected member.
  useEffect(() => {
    const map = mapRef.current;
    const m = members.find((x) => x.user_id === selected);
    if (!map || !m?.location) return;
    map.flyTo({ center: [m.location.lon, m.location.lat], zoom: Math.max(map.getZoom(), 15), essential: false });
    // Only when the selection changes, not on every position update.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected]);

  return <div ref={container} className="map" role="region" aria-label={t("nav.map")} />;
}

function fit(map: maplibregl.Map, members: MapMember[], animate: boolean) {
  if (members.length === 0) return;
  const padding = fitPadding(map);
  if (members.length === 1) {
    const l = members[0]!.location!;
    map.jumpTo({ center: [l.lon, l.lat], zoom: 15 });
    return;
  }
  const lons = members.map((m) => m.location!.lon);
  const lats = members.map((m) => m.location!.lat);
  const bounds: LngLatBoundsLike = [
    [Math.min(...lons), Math.min(...lats)],
    [Math.max(...lons), Math.max(...lats)],
  ];
  map.fitBounds(bounds, { padding, maxZoom: 16, animate });
}

/**
 * Keeps markers clear of the floating UI: the member sheet (bottom on phones, left rail on
 * desktop), the status chip on top, and the marker's own height above its anchor point.
 */
function fitPadding(map: maplibregl.Map) {
  const host = map.getContainer().getBoundingClientRect();
  const sheet = map.getContainer().parentElement?.querySelector(".sheet")?.getBoundingClientRect();
  const markerHeight = 80; // avatar + age pill above the anchored tip
  if (window.innerWidth >= 900) {
    // Right: zoom controls (bottom-right) plus half a marker width.
    return { top: markerHeight + 40, bottom: 70, left: (sheet ? sheet.right - host.left : 400) + 48, right: 110 };
  }
  const covered = sheet ? Math.max(0, host.bottom - sheet.top) : host.height * 0.45;
  return { top: markerHeight + 56, bottom: covered + 24, left: 48, right: 64 };
}
