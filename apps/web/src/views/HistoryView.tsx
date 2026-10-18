import { useEffect, useMemo, useState } from "react";
import { api, ApiError, type HistoryPoint } from "../api/client";
import { useApp } from "../state";
import { useI18n } from "../i18n";
import { HistoryMap } from "../components/HistoryMap";
import { dayRange, groupStays, localDate, previousDay, trackOf } from "../lib/history";
import { navigate } from "../lib/router";
import { useNow } from "../lib/useNow";
import { useErrorText } from "../components/ui";

const LIMIT = 5000;
const TRACK_COLOR = "#2f6fde";

type Loaded = { key: string; points: HistoryPoint[] } | { key: string; error: unknown };

/** One day of a person's history (mine, or someone who granted it): route on a map plus a time list. */
export function HistoryView({ userId }: { userId: string }) {
  const { t, locale } = useI18n();
  const app = useApp();
  const errorText = useErrorText();
  const isSelf = userId === app.me.id;
  const name = isSelf ? app.me.display_name : (app.shares?.incoming.find((s) => s.user_id === userId)?.display_name ?? "");
  const today = localDate(useNow(60_000));
  const yesterday = previousDay(today);
  const [day, setDay] = useState(today);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [focus, setFocus] = useState<{ lat: number; lon: number; token: number } | null>(null);
  const selfOff = isSelf && app.settings?.history_enabled === false;
  const key = `${userId}:${day}`;

  useEffect(() => {
    if (selfOff || !day) return;
    let cancelled = false;
    const range = dayRange(day);
    api.userLocations(userId, { ...range, limit: LIMIT }).then(
      ({ points }) => {
        if (!cancelled) setLoaded({ key, points });
      },
      (error: unknown) => {
        if (!cancelled) setLoaded({ key, error });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [userId, day, key, selfOff]);

  const current = loaded?.key === key ? loaded : null;
  const points = current && "points" in current ? current.points : null;
  const track = useMemo(() => (points ? trackOf(points) : []), [points]);
  const stays = useMemo(() => groupStays(track), [track]);
  const coords = useMemo(() => track.map((p) => [p.lon, p.lat] as [number, number]), [track]);
  const suspect = points ? points.length - track.length : 0;
  const hhmm = (iso: string) => new Date(iso).toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" });

  let notice: string | null = null;
  if (selfOff) notice = t("history.offSelf");
  else if (current && "error" in current) {
    notice = current.error instanceof ApiError && current.error.status === 404 ? t("history.unavailable", { name: name || t("map.members") }) : errorText(current.error);
  } else if (points && track.length === 0) notice = t("history.empty") + (suspect > 0 ? ` (${t("history.suspect", { n: suspect })})` : "");

  return (
    <div className="page history">
      <div className="page-inner">
        <header className="section">
          <button className="btn ghost" type="button" style={{ justifySelf: "start" }} onClick={() => (history.length > 1 ? history.back() : navigate("/"))}>
            ← {t("common.back")}
          </button>
          <h1>{isSelf ? t("history.titleSelf") : t("history.title", { name })}</h1>
          <div className="day-picker" role="group" aria-label={t("history.date")}>
            <button className="btn small secondary" type="button" aria-pressed={day === today} onClick={() => setDay(today)}>
              {t("history.today")}
            </button>
            <button className="btn small secondary" type="button" aria-pressed={day === yesterday} onClick={() => setDay(yesterday)}>
              {t("history.yesterday")}
            </button>
            <label className="visually-hidden" htmlFor="history-day">
              {t("history.date")}
            </label>
            <input id="history-day" className="input" type="date" value={day} max={today} onChange={(e) => e.target.value && setDay(e.target.value)} />
          </div>
        </header>

        {notice ? (
          <div className="panel pad" role="status">
            <p>{notice}</p>
            {selfOff && (
              <button className="btn secondary" type="button" onClick={() => navigate("/privacy")}>
                {t("history.toPrivacy")}
              </button>
            )}
          </div>
        ) : !points ? (
          <p className="muted" role="status">
            {t("common.loading")}
          </p>
        ) : (
          <>
            <section className="history-map" aria-label={t("history.mapLabel")}>
              <HistoryMap styleUrl={app.instance.map_style_url} track={coords} color={TRACK_COLOR} label={t("history.mapLabel")} focus={focus} />
            </section>
            <section className="section" aria-labelledby="times-h">
              <h2 id="times-h">{t("history.times")}</h2>
              <p className="small muted">
                {t("history.points", { n: track.length })}
                {suspect > 0 && ` · ${t("history.suspect", { n: suspect })}`}
                {points.length >= LIMIT && ` · ${t("history.truncated")}`}
              </p>
              <ol className="panel stay-list">
                {stays.map((s, i) => (
                  <li key={s.from + i}>
                    <button className="stay" type="button" onClick={() => setFocus({ lat: s.lat, lon: s.lon, token: Date.now() })}>
                      <span className="name">{s.from === s.to ? hhmm(s.from) : `${hhmm(s.from)} – ${hhmm(s.to)}`}</span>
                      <span className="small muted">{t("map.accuracy", { m: Math.round(s.accuracy_m) })}</span>
                    </button>
                  </li>
                ))}
              </ol>
            </section>
          </>
        )}
      </div>
    </div>
  );
}
