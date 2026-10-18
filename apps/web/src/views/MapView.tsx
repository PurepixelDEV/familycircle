import { Fragment, useEffect, useMemo, useState } from "react";
import { useApp } from "../state";
import { useI18n } from "../i18n";
import { useFamilyMap } from "../hooks/useFamilyMap";
import { usePlaces } from "../hooks/usePlaces";
import { useAlerts } from "../hooks/useAlerts";
import { FamilyMap, type FocusOverlay, type PlaceOverlay, type SosOverlay } from "../components/FamilyMap";
import { MemberRow } from "../components/MemberList";
import { MemberActions } from "../components/MemberActions";
import { CheckInButton } from "../components/CheckIn";
import { SosButton } from "../components/Sos";
import { navigate } from "../lib/router";
import { relativeTime } from "../lib/time";

export function MapView() {
  const { t, locale } = useI18n();
  const app = useApp();
  const alerts = useAlerts();
  const { map, status, now, error } = useFamilyMap(app.familyId, app.reloadFamilies);
  const { places } = usePlaces(app.familyId);
  const [selected, setSelected] = useState<string | null>(null);
  const [fitToken, setFitToken] = useState(0);
  const [expanded, setExpanded] = useState(true);

  // A focus request ("show on map") is for this visit only.
  const { clearFocus } = alerts;
  useEffect(() => clearFocus, [clearFocus]);

  const members = useMemo(() => map?.members ?? [], [map]);
  const visibleOthers = members.filter((m) => !m.is_self && m.location).length;
  const family = app.families.find((f) => f.id === app.familyId);

  const placeOverlays = useMemo<PlaceOverlay[]>(() => {
    const names = new Map(members.map((m) => [m.user_id, m.display_name]));
    return (places ?? []).map((p) => ({
      id: p.id, name: p.name, lat: p.lat, lon: p.lon, radius_m: p.radius_m,
      present: p.present.map((id) => names.get(id)).filter((n): n is string => !!n),
    }));
  }, [places, members]);

  const sosOverlays = useMemo<SosOverlay[]>(
    () =>
      alerts.sos.others
        .filter((a) => a.location)
        .map((a) => ({
          id: a.id,
          name: a.actor.display_name,
          lat: a.location!.lat,
          lon: a.location!.lon,
          label: t("sos.markerLabel", { name: a.actor.display_name, when: relativeTime(a.created_at, now, locale, t("time.justNow")) }),
        })),
    [alerts.sos.others, now, locale, t],
  );

  const focus = useMemo<FocusOverlay | null>(
    () => (alerts.focus ? { lat: alerts.focus.lat, lon: alerts.focus.lon, label: alerts.focus.label, pin: !alerts.focus.sos, token: alerts.focus.token } : null),
    [alerts.focus],
  );

  const outgoing = app.shares?.outgoing.length ?? 0;
  const paused = app.settings?.sharing_paused ?? false;
  const chipClass = paused ? "chip paused" : outgoing === 0 ? "chip off" : "chip";
  const chipText = paused
    ? t("map.paused")
    : outgoing === 0
      ? t("map.sharingNobody")
      : outgoing === 1
        ? t("map.sharingWithOne")
        : t("map.sharingWith", { n: outgoing });

  return (
    <div className="mapview">
      <FamilyMap
        styleUrl={app.instance.map_style_url}
        members={members}
        now={now}
        selected={selected}
        onSelect={(id) => {
          setSelected(id);
          setExpanded(true);
        }}
        fitToken={fitToken}
        places={placeOverlays}
        sos={sosOverlays}
        focus={focus}
      />
      <div className="map-top">
        <div className="map-chips">
          <button className={chipClass} type="button" onClick={() => navigate("/privacy")}>
            <span className="dot" aria-hidden="true" />
            {chipText}
          </button>
          {status === "closed" && (
            <span className="chip warn" role="status">
              {t("map.offline")}
            </span>
          )}
        </div>
        <SosButton />
      </div>
      <section className={"sheet" + (expanded ? "" : " collapsed")} aria-label={t("map.members")}>
        <div className="sheet-top">
          <button className="sheet-handle" type="button" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
            <span className="grip" aria-hidden="true" />
            <h2>{family?.name ?? t("map.members")}</h2>
          </button>
          {members.some((m) => m.location) && (
            <button
              className="btn small secondary"
              type="button"
              onClick={() => {
                setSelected(null);
                setFitToken((n) => n + 1);
              }}
            >
              {t("map.showAll")}
            </button>
          )}
        </div>
        <div className="sheet-body">
          <div className="quick-actions">
            <CheckInButton />
          </div>
          {error != null && !map && <p className="notice error">{t("common.error")}</p>}
          {!map && error == null && <p className="muted">{t("common.loading")}</p>}
          {members.map((m) => (
            <Fragment key={m.user_id}>
              <MemberRow m={m} now={now} selected={selected === m.user_id} onSelect={() => setSelected(selected === m.user_id ? null : m.user_id)} />
              {selected === m.user_id && <MemberActions userId={m.user_id} name={m.display_name} isSelf={m.is_self} />}
            </Fragment>
          ))}
          {map && visibleOthers === 0 && <p className="muted small" style={{ padding: "8px 0" }}>{t("map.empty")}</p>}
        </div>
      </section>
    </div>
  );
}
