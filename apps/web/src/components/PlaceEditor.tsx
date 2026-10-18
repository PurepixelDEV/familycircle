import { useEffect, useEffectEvent, useId, useRef, useState, type FormEvent } from "react";
import * as maplibregl from "maplibre-gl";
import type { GeoJSONSource } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?url";
import { api, ApiError, type Place } from "../api/client";
import { useI18n } from "../i18n";
import { circlePolygon, currentPosition, GeoError } from "../lib/geo";
import { placeChanges, RADIUS_DEFAULT, RADIUS_MAX, RADIUS_MIN, type Center } from "../lib/places";
import { useApp } from "../state";
import { Field, toast, useErrorText } from "./ui";

maplibregl.setWorkerUrl(workerUrl);

/** Add or edit a place: name, radius slider and a centre picked on the map or from the browser. */
export function PlaceEditor({ familyId, place, onDone }: { familyId: string; place: Place | null; onDone: (saved: boolean) => void }) {
  const { t } = useI18n();
  const app = useApp();
  const errorText = useErrorText();
  const id = useId();
  const [name, setName] = useState(place?.name ?? "");
  const [radius, setRadius] = useState(place?.radius_m ?? RADIUS_DEFAULT);
  const [center, setCenter] = useState<Center | null>(place ? { lat: place.lat, lon: place.lon } : null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [locating, setLocating] = useState(false);

  const takeMyPosition = async () => {
    setError(null);
    setLocating(true);
    try {
      const p = await currentPosition({ timeoutMs: 15_000 });
      setCenter({ lat: p.lat, lon: p.lon });
    } catch (e) {
      setError(t(`geo.${e instanceof GeoError ? e.code : "unavailable"}`));
    } finally {
      setLocating(false);
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!center) {
      setError(t("places.noCenter"));
      return;
    }
    setError(null);
    setBusy(true);
    const values = { name: name.trim(), lat: center.lat, lon: center.lon, radius_m: radius };
    try {
      if (place) {
        const changes = placeChanges(place, values);
        if (Object.keys(changes).length > 0) await api.updatePlace(place.id, changes);
      } else {
        await api.createPlace(familyId, values);
      }
      toast(t("places.saved"));
      onDone(true);
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) setError(t("places.forbidden"));
      else if (err instanceof ApiError && err.status === 409) setError(t("places.tooMany"));
      else setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="panel pad place-editor" onSubmit={submit} aria-labelledby={id + "-t"}>
      <h3 id={id + "-t"}>{place ? t("places.editTitle") : t("places.addTitle")}</h3>
      <Field label={t("places.name")}>
        {(fid) => <input id={fid} className="input" value={name} maxLength={64} required placeholder={t("places.namePlaceholder")} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <div className="field">
        <label htmlFor={id + "-r"}>{t("places.radiusLabel", { m: radius })}</label>
        <input
          id={id + "-r"}
          className="range"
          type="range"
          min={RADIUS_MIN}
          max={RADIUS_MAX}
          step={10}
          value={radius}
          aria-valuetext={`${radius} m`}
          onChange={(e) => setRadius(Number(e.target.value))}
        />
      </div>
      <div className="field">
        <span className="small muted" id={id + "-h"}>
          {t("places.pickHelp")}
        </span>
        <PickerMap styleUrl={app.instance.map_style_url} familyId={familyId} center={center} radius={radius} onPick={setCenter} label={t("places.mapLabel")} describedBy={id + "-h"} />
        <button className="btn small secondary" type="button" style={{ justifySelf: "start" }} disabled={locating} onClick={() => void takeMyPosition()}>
          {locating ? t("checkin.locating") : t("places.myPosition")}
        </button>
      </div>
      {error && (
        <p className="notice error" role="alert">
          {error}
        </p>
      )}
      <div className="actions" style={{ display: "flex", gap: 10, justifyContent: "flex-end", flexWrap: "wrap" }}>
        <button className="btn secondary" type="button" onClick={() => onDone(false)}>
          {t("common.cancel")}
        </button>
        <button className="btn" disabled={busy || !name.trim()}>
          {t("common.save")}
        </button>
      </div>
    </form>
  );
}

const PREVIEW = "fc-place-preview";

function PickerMap(props: { styleUrl: string; familyId: string; center: Center | null; radius: number; onPick: (c: Center) => void; label: string; describedBy: string }) {
  const { styleUrl, familyId, center, radius, onPick } = props;
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markerRef = useRef<maplibregl.Marker | null>(null);
  const pick = useEffectEvent((c: Center) => onPick(c));
  const initial = useRef(center);

  useEffect(() => {
    if (!container.current) return;
    const start = initial.current;
    const map = new maplibregl.Map({
      container: container.current,
      style: styleUrl,
      center: start ? [start.lon, start.lat] : [8.23, 46.8],
      zoom: start ? 15 : 7,
      attributionControl: { compact: true },
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "bottom-right");
    map.on("load", () => {
      map.addSource(PREVIEW, { type: "geojson", data: { type: "FeatureCollection", features: [] } });
      map.addLayer({ id: "fc-preview-fill", type: "fill", source: PREVIEW, paint: { "fill-color": "#1e5a52", "fill-opacity": 0.15 } });
      map.addLayer({ id: "fc-preview-line", type: "line", source: PREVIEW, paint: { "line-color": "#1e5a52", "line-width": 2 } });
    });
    map.on("click", (e) => pick({ lat: e.lngLat.lat, lon: e.lngLat.lng }));
    mapRef.current = map;
    // Without a centre yet, start where I am on the family map (if I share a position at all).
    if (!start) {
      api.familyMap(familyId).then(
        (fm) => {
          const self = fm.members.find((m) => m.is_self && m.location)?.location;
          if (self && mapRef.current === map && !markerRef.current) map.jumpTo({ center: [self.lon, self.lat], zoom: 15 });
        },
        () => undefined,
      );
    }
    return () => {
      map.remove();
      mapRef.current = null;
      markerRef.current = null;
    };
  }, [styleUrl, familyId]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const draw = () => {
      (map.getSource(PREVIEW) as GeoJSONSource | undefined)?.setData({
        type: "FeatureCollection",
        features: center ? [{ type: "Feature", properties: {}, geometry: { type: "Polygon", coordinates: [circlePolygon(center.lon, center.lat, radius)] } }] : [],
      });
    };
    if (map.isStyleLoaded() && map.getSource(PREVIEW)) draw();
    else map.once("load", draw);
    if (center) {
      if (!markerRef.current) markerRef.current = new maplibregl.Marker({ color: "#1e5a52" }).setLngLat([center.lon, center.lat]).addTo(map);
      else markerRef.current.setLngLat([center.lon, center.lat]);
    }
  }, [center, radius]);

  // Keep the whole circle in view when the centre moves (e.g. "my current position").
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !center) return;
    const ring = circlePolygon(center.lon, center.lat, radius, 16);
    const lons = ring.map((c) => c[0]);
    const lats = ring.map((c) => c[1]);
    const b = map.getBounds();
    const inside = ring.every(([lo, la]) => b.contains([lo, la]));
    if (!inside) map.fitBounds([[Math.min(...lons), Math.min(...lats)], [Math.max(...lons), Math.max(...lats)]], { padding: 24, maxZoom: 17 });
  }, [center, radius]);

  return <div ref={container} className="picker-map" role="application" aria-label={props.label} aria-describedby={props.describedBy} />;
}
