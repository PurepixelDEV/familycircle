import { useState } from "react";
import { api, ApiError, type Alert } from "../api/client";
import { useAlerts } from "../hooks/useAlerts";
import { useI18n } from "../i18n";
import { currentPosition } from "../lib/geo";
import { relativeTime } from "../lib/time";
import { useNow } from "../lib/useNow";
import { HoldButton } from "./HoldButton";
import { toast, useErrorText } from "./ui";

/** Round SOS button for the map: hold 3 s, then send with the browser position if available. */
export function SosButton() {
  const { t } = useI18n();
  const alerts = useAlerts();
  const [sending, setSending] = useState(false);
  if (alerts.sos.mine) return null; // the banner offers "I am safe" instead

  const send = async () => {
    setSending(true);
    toast(t("sos.sending"));
    try {
      // A short wait for a fresh fix; without one the server uses the last known position.
      const pos = await currentPosition({ timeoutMs: 4000, maxAgeMs: 60_000, hardTimeoutMs: 5000 }).catch(() => undefined);
      const res = await api.sos(pos);
      alerts.add(res.alert);
      toast(res.recipients > 0 ? t("sos.sent", { n: res.recipients }) : t("sos.sentNobody"));
    } catch (e) {
      toast(e instanceof ApiError && e.status === 429 ? t("common.tooMany") : t("sos.failed"));
    } finally {
      setSending(false);
    }
  };

  return (
    <HoldButton
      className="sos-btn"
      label={t("sos.hold")}
      help={t("sos.holdHelp")}
      countdownText={(n) => t("sos.countdown", { n })}
      releasedText={t("sos.released")}
      disabled={sending}
      onComplete={() => void send()}
    >
      {t("sos.button")}
    </HoldButton>
  );
}

/** Red banners for active SOS alarms: other people's (with "show on map") and my own ("I am safe"). */
export function SosBanners() {
  const { t, locale } = useI18n();
  const alerts = useAlerts();
  const errorText = useErrorText();
  const now = useNow(30_000);
  const [busy, setBusy] = useState(false);
  const { mine, others } = alerts.sos;
  if (!mine && others.length === 0) return null;

  const since = (a: Alert) =>
    t("sos.since", {
      when: relativeTime(a.created_at, now, locale, t("time.justNow")),
      time: new Date(a.created_at).toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" }),
    });

  const resolve = async () => {
    setBusy(true);
    try {
      await api.resolveSos();
      await alerts.refresh(); // brings the own "sos_resolved" and ends the banner
      toast(t("sos.safeDone"));
    } catch (e) {
      toast(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="sos-banners">
      {others.map((a) => (
        <div className="sos-banner" role="alert" key={a.id}>
          <span className="sos-badge" aria-hidden="true">
            SOS
          </span>
          <div className="grow">
            <strong>{t("alert.sos", { name: a.actor.display_name })}</strong>
            <span className="small">
              {since(a)}
              {a.location ? "" : ` · ${t("sos.noPosition")}`}
            </span>
          </div>
          {a.location && (
            <button className="btn small light" type="button" onClick={() => alerts.showOnMap(a)}>
              {t("alerts.showOnMap")}
            </button>
          )}
        </div>
      ))}
      {mine && (
        <div className="sos-banner" role="alert">
          <span className="sos-badge" aria-hidden="true">
            SOS
          </span>
          <div className="grow">
            <strong>{t("sos.activeTitle")}</strong>
            <span className="small">{t("sos.activeBody")}</span>
          </div>
          <button className="btn small light" type="button" disabled={busy} onClick={() => void resolve()}>
            {t("sos.safe")}
          </button>
        </div>
      )}
    </div>
  );
}
