import { useEffect, useRef } from "react";
import * as maplibregl from "maplibre-gl";
import type { GeoJSONSource } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?url";

maplibregl.setWorkerUrl(workerUrl);

const SOURCE = "fc-track";
const ENDS = "fc-track-ends";

type Props = {
  styleUrl: string;
  /** [lon, lat], oldest first. */
  track: [number, number][];
  color: string;
  label: string;
  /** Fly here when `token` changes (a row of the time list was chosen). */
  focus: { lat: number; lon: number; token: number } | null;
};

/** A day's route as a line with start (hollow) and end (filled) dots. */
export function HistoryMap({ styleUrl, track, color, label, focus }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);

  useEffect(() => {
    if (!container.current) return;
    const map = new maplibregl.Map({ container: container.current, style: styleUrl, center: [8.54, 47.37], zoom: 3, attributionControl: { compact: true } });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "bottom-right");
    map.on("load", () => {
      map.addSource(SOURCE, { type: "geojson", data: { type: "FeatureCollection", features: [] } });
      map.addSource(ENDS, { type: "geojson", data: { type: "FeatureCollection", features: [] } });
      map.addLayer({ id: "fc-track-casing", type: "line", source: SOURCE, layout: { "line-join": "round", "line-cap": "round" }, paint: { "line-color": "#ffffff", "line-width": 7, "line-opacity": 0.9 } });
      map.addLayer({ id: "fc-track-line", type: "line", source: SOURCE, layout: { "line-join": "round", "line-cap": "round" }, paint: { "line-color": ["get", "color"], "line-width": 4 } });
      map.addLayer({
        id: "fc-track-ends",
        type: "circle",
        source: ENDS,
        paint: {
          "circle-radius": 7,
          "circle-color": ["case", ["==", ["get", "end"], 1], ["get", "color"], "#ffffff"],
          "circle-stroke-color": ["get", "color"],
          "circle-stroke-width": 3,
        },
      });
    });
    mapRef.current = map;
    return () => {
      map.remove();
      mapRef.current = null;
    };
  }, [styleUrl]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const draw = () => {
      (map.getSource(SOURCE) as GeoJSONSource | undefined)?.setData({
        type: "FeatureCollection",
        features: track.length > 1 ? [{ type: "Feature", properties: { color }, geometry: { type: "LineString", coordinates: track } }] : [],
      });
      const ends = track.length === 0 ? [] : track.length === 1 ? [track[0]!] : [track[0]!, track[track.length - 1]!];
      (map.getSource(ENDS) as GeoJSONSource | undefined)?.setData({
        type: "FeatureCollection",
        features: ends.map((c, i) => ({ type: "Feature" as const, properties: { color, end: i === ends.length - 1 ? 1 : 0 }, geometry: { type: "Point" as const, coordinates: c } })),
      });
      if (track.length === 1) map.jumpTo({ center: track[0]!, zoom: 15 });
      else if (track.length > 1) {
        const lons = track.map((c) => c[0]);
        const lats = track.map((c) => c[1]);
        map.fitBounds(
          [
            [Math.min(...lons), Math.min(...lats)],
            [Math.max(...lons), Math.max(...lats)],
          ],
          { padding: 48, maxZoom: 16, animate: false },
        );
      }
    };
    if (map.isStyleLoaded() && map.getSource(SOURCE)) draw();
    else map.once("load", draw);
  }, [track, color]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !focus) return;
    map.flyTo({ center: [focus.lon, focus.lat], zoom: Math.max(map.getZoom(), 16) });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focus?.token]);

  return <div ref={container} className="map" role="region" aria-label={label} />;
}
