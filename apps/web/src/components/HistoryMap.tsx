import { useEffect, useLayoutEffect, useRef } from "react";
import * as maplibregl from "maplibre-gl";
import type { GeoJSONSource } from "maplibre-gl";
import type { FeatureCollection } from "geojson";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?url";

maplibregl.setWorkerUrl(workerUrl);

type LonLat = [number, number];
type FC = FeatureCollection;

const EMPTY: FC = { type: "FeatureCollection", features: [] };
const SOURCES = ["fc-track", "fc-highlight", "fc-stays", "fc-ends", "fc-playhead"] as const;
const HIGHLIGHT = "#e0592a";

export type MapStay = { lat: number; lon: number; place: boolean };

type Props = {
  styleUrl: string;
  /** The day's route [lon, lat], oldest first. */
  track: LonLat[];
  color: string;
  label: string;
  stays: MapStay[];
  /** Part of the route to emphasise (a chosen move), or null. */
  highlight: LonLat[] | null;
  /** The playback position, or null when nothing is played. */
  playhead: { lat: number; lon: number; noData: boolean } | null;
  /** Zoom to these coordinates when `token` changes (a timeline row was chosen). */
  fit: { coords: LonLat[]; token: number } | null;
};

function line(coords: LonLat[] | null): FC {
  if (!coords || coords.length < 2) return EMPTY;
  return { type: "FeatureCollection", features: [{ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: coords } }] };
}

function points(items: { c: LonLat; props: Record<string, number> }[]): FC {
  return { type: "FeatureCollection", features: items.map(({ c, props }) => ({ type: "Feature", properties: props, geometry: { type: "Point", coordinates: c } })) };
}

function fitTo(map: maplibregl.Map, coords: LonLat[], animate: boolean) {
  if (coords.length === 0) return;
  const lons = coords.map((c) => c[0]);
  const lats = coords.map((c) => c[1]);
  const bounds: [LonLat, LonLat] = [
    [Math.min(...lons), Math.min(...lats)],
    [Math.max(...lons), Math.max(...lats)],
  ];
  if (bounds[0][0] === bounds[1][0] && bounds[0][1] === bounds[1][1]) map.easeTo({ center: coords[0]!, zoom: Math.max(map.getZoom(), 16), animate });
  // Extra room at the bottom: the playback bar floats there.
  else map.fitBounds(bounds, { padding: { top: 56, left: 48, right: 56, bottom: 132 }, maxZoom: 17, animate });
}

/**
 * A day's route: line with start (hollow) and end (filled) dots, stays as dots (filled at a known
 * place), an emphasised move and the playback position.
 */
export function HistoryMap({ styleUrl, track, color, label, stays, highlight, playhead, fit }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const ready = useRef(false);
  const latest = useRef({ track, color, stays, highlight, playhead });
  // Layout effects run before the effects below, so every draw sees the current props.
  useLayoutEffect(() => {
    latest.current = { track, color, stays, highlight, playhead };
  });

  const source = (id: (typeof SOURCES)[number]) => mapRef.current?.getSource(id) as GeoJSONSource | undefined;

  const drawRoute = () => {
    const { track: tr, color: col, stays: st } = latest.current;
    source("fc-track")?.setData(line(tr));
    const ends = tr.length === 0 ? [] : tr.length === 1 ? [tr[0]!] : [tr[0]!, tr[tr.length - 1]!];
    source("fc-ends")?.setData(points(ends.map((c, i) => ({ c, props: { end: i === ends.length - 1 ? 1 : 0 } }))));
    source("fc-stays")?.setData(points(st.map((s) => ({ c: [s.lon, s.lat] as LonLat, props: { place: s.place ? 1 : 0 } }))));
    const map = mapRef.current;
    if (map) {
      for (const id of ["fc-track-line", "fc-ends", "fc-stays"]) {
        const prop = id === "fc-track-line" ? "line-color" : "circle-stroke-color";
        if (map.getLayer(id)) map.setPaintProperty(id, prop, col);
      }
      if (map.getLayer("fc-stays")) map.setPaintProperty("fc-stays", "circle-color", ["case", ["==", ["get", "place"], 1], col, "#ffffff"]);
      if (map.getLayer("fc-ends")) map.setPaintProperty("fc-ends", "circle-color", ["case", ["==", ["get", "end"], 1], col, "#ffffff"]);
    }
  };
  const drawHighlight = () => source("fc-highlight")?.setData(line(latest.current.highlight));
  const drawPlayhead = () => {
    const p = latest.current.playhead;
    source("fc-playhead")?.setData(p ? points([{ c: [p.lon, p.lat], props: { noData: p.noData ? 1 : 0 } }]) : EMPTY);
  };

  useEffect(() => {
    if (!container.current) return;
    const map = new maplibregl.Map({ container: container.current, style: styleUrl, center: [8.54, 47.37], zoom: 3, attributionControl: { compact: true } });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    map.on("load", () => {
      for (const id of SOURCES) map.addSource(id, { type: "geojson", data: EMPTY });
      const round = { "line-join": "round", "line-cap": "round" } as const;
      map.addLayer({ id: "fc-track-casing", type: "line", source: "fc-track", layout: round, paint: { "line-color": "#ffffff", "line-width": 7, "line-opacity": 0.9 } });
      map.addLayer({ id: "fc-track-line", type: "line", source: "fc-track", layout: round, paint: { "line-color": latest.current.color, "line-width": 4 } });
      map.addLayer({ id: "fc-highlight-casing", type: "line", source: "fc-highlight", layout: round, paint: { "line-color": "#ffffff", "line-width": 11 } });
      map.addLayer({ id: "fc-highlight-line", type: "line", source: "fc-highlight", layout: round, paint: { "line-color": HIGHLIGHT, "line-width": 6 } });
      map.addLayer({ id: "fc-stays", type: "circle", source: "fc-stays", paint: { "circle-radius": 6, "circle-color": "#ffffff", "circle-stroke-color": latest.current.color, "circle-stroke-width": 2.5 } });
      map.addLayer({ id: "fc-ends", type: "circle", source: "fc-ends", paint: { "circle-radius": 7, "circle-color": "#ffffff", "circle-stroke-color": latest.current.color, "circle-stroke-width": 3 } });
      map.addLayer({
        id: "fc-playhead",
        type: "circle",
        source: "fc-playhead",
        paint: {
          "circle-radius": 10,
          "circle-color": ["case", ["==", ["get", "noData"], 1], "#7b8a8f", "#1b2a2e"],
          "circle-stroke-color": "#ffffff",
          "circle-stroke-width": 3,
        },
      });
      ready.current = true;
      drawRoute();
      drawHighlight();
      drawPlayhead();
      fitTo(map, latest.current.track, false);
    });
    mapRef.current = map;
    return () => {
      ready.current = false;
      map.remove();
      mapRef.current = null;
    };
    // The draw helpers read `latest`; the map is only rebuilt for a new style.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [styleUrl]);

  // A new day: draw and show the whole route. Places reloading (stays) only redraws.
  useEffect(() => {
    if (!ready.current || !mapRef.current) return;
    drawRoute();
    // The map may just have become visible again (an empty day hides it): measure before fitting.
    mapRef.current.resize();
    fitTo(mapRef.current, track, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [track]);

  useEffect(() => {
    if (ready.current) drawRoute();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [color, stays]);

  useEffect(() => {
    if (ready.current) drawHighlight();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [highlight]);

  useEffect(() => {
    const map = mapRef.current;
    if (!ready.current || !map) return;
    drawPlayhead();
    // Keep the playback position in view without fighting the user's own panning.
    if (playhead && !map.getBounds().contains([playhead.lon, playhead.lat])) map.easeTo({ center: [playhead.lon, playhead.lat], duration: 300 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playhead?.lat, playhead?.lon, playhead?.noData, playhead === null]);

  useEffect(() => {
    const map = mapRef.current;
    if (!ready.current || !map || !fit) return;
    fitTo(map, fit.coords, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fit?.token]);

  return <div ref={container} className="map" role="region" aria-label={label} />;
}
