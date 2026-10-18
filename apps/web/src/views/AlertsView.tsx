import { useEffect } from "react";
import type { Alert } from "../api/client";
import { useAlerts } from "../hooks/useAlerts";
import { useI18n } from "../i18n";
import { activeSos, alertText } from "../lib/alerts";
import { relativeTime } from "../lib/time";
import { useNow } from "../lib/useNow";
import { BatteryIcon, CheckPinIcon, PinIcon, ShieldIcon } from "../components/icons";

/** "Meldungen": received and own alerts, newest first. Opening the list marks everything as seen. */
export function AlertsView() {
  const { t, locale } = useI18n();
  const alerts = useAlerts();
  const now = useNow(30_000);
  const { markSeen } = alerts;
  const count = alerts.alerts.length;
  useEffect(() => {
    markSeen();
  }, [markSeen, count]);

  const active = new Set(activeSos(alerts.alerts, now).map((a) => a.id));
  const list = [...alerts.alerts].reverse();
  const notifyOff = typeof Notification !== "undefined" && Notification.permission === "default";

  return (
    <div className="page">
      <div className="page-inner">
        <header className="section">
          <h1>{t("alerts.title")}</h1>
          <p className="muted">{t("alerts.intro")}</p>
        </header>
        <section className="section" aria-label={t("alerts.title")}>
          {list.length === 0 ? (
            <div className="panel pad">
              <p className="muted">{t("alerts.empty")}</p>
            </div>
          ) : (
            <ul className="panel alert-list">
              {list.map((a) => (
                <AlertRow key={a.id} a={a} active={active.has(a.id)} when={relativeTime(a.created_at, now, locale, t("time.justNow"))} onShow={() => alerts.showOnMap(a)} />
              ))}
            </ul>
          )}
          <p className="small muted">
            {t("alerts.webHint")} {notifyOff && t("alerts.notifyHint")}
          </p>
        </section>
      </div>
    </div>
  );
}

function AlertRow({ a, active, when, onShow }: { a: Alert; active: boolean; when: string; onShow: () => void }) {
  const { t, locale } = useI18n();
  const abs = new Date(a.created_at).toLocaleString(locale, { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
  return (
    <li className={"alert-row kind-" + a.kind + (active ? " active" : "")}>
      <span className="alert-icon" aria-hidden="true">
        {kindIcon(a.kind)}
      </span>
      <div className="grow">
        <div className="name">
          {alertText(a, t)}
          {active && <span className="alert-tag">{t("alerts.active")}</span>}
        </div>
        {a.message && <p className="alert-message">{a.message}</p>}
        <div className="small muted">
          <time dateTime={a.created_at} title={abs}>
            {when}
          </time>{" "}
          · {abs}
        </div>
      </div>
      {a.location && (
        <button className="btn small secondary" type="button" onClick={onShow}>
          {t("alerts.showOnMap")}
        </button>
      )}
    </li>
  );
}

function kindIcon(kind: Alert["kind"]) {
  switch (kind) {
    case "sos":
      return <span className="sos-text">SOS</span>;
    case "sos_resolved":
      return <ShieldIcon />;
    case "check_in":
      return <CheckPinIcon />;
    case "battery_low":
    case "last_location":
      return <BatteryIcon />;
    default:
      return <PinIcon />;
  }
}
