import { useState } from "react";
import { useApp } from "../state";
import { useI18n } from "../i18n";
import { useFamilyMap } from "../hooks/useFamilyMap";
import { FamilyMap } from "../components/FamilyMap";
import { MemberRow } from "../components/MemberList";
import { navigate } from "../lib/router";

export function MapView() {
  const { t } = useI18n();
  const app = useApp();
  const { map, status, now, error } = useFamilyMap(app.familyId, app.reloadFamilies);
  const [selected, setSelected] = useState<string | null>(null);
  const [fitToken, setFitToken] = useState(0);
  const [expanded, setExpanded] = useState(true);

  const members = map?.members ?? [];
  const visibleOthers = members.filter((m) => !m.is_self && m.location).length;
  const family = app.families.find((f) => f.id === app.familyId);

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
      />
      <div className="map-top">
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
          {error != null && !map && <p className="notice error">{t("common.error")}</p>}
          {!map && error == null && <p className="muted">{t("common.loading")}</p>}
          {members.map((m) => (
            <MemberRow key={m.user_id} m={m} now={now} selected={selected === m.user_id} onSelect={() => setSelected(m.user_id)} />
          ))}
          {map && visibleOthers === 0 && <p className="muted small" style={{ padding: "8px 0" }}>{t("map.empty")}</p>}
        </div>
      </section>
    </div>
  );
}
