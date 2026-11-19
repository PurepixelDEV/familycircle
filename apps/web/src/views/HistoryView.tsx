import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError, type Alert, type HistoryDay, type HistoryPoint, type Place } from "../api/client";
import { useApp } from "../state";
import { useI18n } from "../i18n";
import { HistoryMap, type MapStay } from "../components/HistoryMap";
import { ChevronLeftIcon, ChevronRightIcon, GapIcon, PauseIcon, PinIcon, PlayIcon, RideIcon, WalkIcon } from "../components/icons";
import { useErrorText } from "../components/ui";
import { alertText } from "../lib/alerts";
import { dayRange, localDate } from "../lib/history";
import { navigate } from "../lib/router";
import { useNow } from "../lib/useNow";
import {
  addDays,
  buildTimeline,
  formatDistance,
  formatDuration,
  mondayOf,
  positionAt,
  summarize,
  usablePoints,
  weekBounds,
  type DaySummary,
  type Segment,
} from "../lib/timeline";
import { alertKindIcon } from "./AlertsView";

const LIMIT = 5000;
const TRACK_COLOR = "#2f6fde";
/** The whole day plays in this time. */
const PLAY_MS = 30_000;
/** Today's history is reloaded this often while open. */
const REFRESH_MS = 120_000;

type DayData = { key: string; points: HistoryPoint[]; events: Alert[] } | { key: string; error: unknown };
type WeekData = { key: string; days: HistoryDay[] | null };
type Item = { kind: "seg"; seg: Segment; idx: number; t: number } | { kind: "event"; alert: Alert; t: number };

/**
 * Places of all my families (a stay is named after any of them); loaded once per visit, places
 * rarely change while someone looks at a past day. Without places the timeline still works.
 */
function useAllPlaces(): Place[] {
  const { families } = useApp();
  const ids = families.map((f) => f.id).join(",");
  const [state, setState] = useState<{ ids: string; places: Place[] }>({ ids: "", places: [] });
  useEffect(() => {
    if (!ids) return;
    let cancelled = false;
    Promise.all(ids.split(",").map((id) => api.places(id).then((r) => r.places, () => [] as Place[]))).then((lists) => {
      if (!cancelled) setState({ ids, places: lists.flat() });
    });
    return () => {
      cancelled = true;
    };
  }, [ids]);
  return state.ids === ids ? state.places : EMPTY_PLACES;
}

const EMPTY_PLACES: Place[] = [];

/** The people whose history I may open: myself (if mine is on) and everyone who granted me theirs. */
function useHistoryPeople() {
  const app = useApp();
  const { t } = useI18n();
  return useMemo(() => {
    const others = (app.shares?.incoming ?? []).filter((s) => s.history).map((s) => ({ id: s.user_id, name: s.display_name }));
    // selfKnown: my settings are loaded – until then nothing of my own is requested.
    return { selfKnown: app.settings != null, selfOn: app.settings?.history_enabled === true, people: [{ id: app.me.id, name: t("history.me") }, ...others] };
  }, [app.shares, app.settings, app.me.id, t]);
}

/**
 * History: a day of one person (me, or someone who granted it) as a timeline of stays, moves and
 * events, with a week overview, totals, the route on a map and playback.
 */
export function HistoryView({ userId: requested }: { userId?: string }) {
  const { t, locale } = useI18n();
  const app = useApp();
  const errorText = useErrorText();
  const { selfKnown, selfOn, people } = useHistoryPeople();
  // Without a person in the URL: me, or the first person who shared theirs if mine is off.
  const userId = requested ?? (!selfKnown || selfOn || people.length === 1 ? app.me.id : people[1]!.id);
  const isSelf = userId === app.me.id;
  const name = isSelf ? app.me.display_name : (people.find((p) => p.id === userId)?.name ?? "");
  const today = localDate(useNow(60_000));
  const [day, setDay] = useState(today);
  const key = `${userId}:${day}`;
  const selfOff = isSelf && selfKnown && !selfOn;
  const skip = selfOff || (isSelf && !selfKnown);

  // Reload today's data now and then; other days do not change.
  const tick = useNow(REFRESH_MS);
  const refresh = day === today ? tick : 0;
  const [data, setData] = useState<DayData | null>(null);
  useEffect(() => {
    if (skip) return;
    let cancelled = false;
    const range = dayRange(day);
    Promise.all([api.userLocations(userId, { ...range, limit: LIMIT }), api.historyEvents(userId, range).catch(() => ({ events: [] as Alert[] }))]).then(
      ([{ points }, { events }]) => {
        if (!cancelled) setData({ key, points, events });
      },
      (error: unknown) => {
        if (!cancelled) setData({ key, error });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [userId, day, key, skip, refresh]);

  const monday = mondayOf(day);
  const weekKey = `${userId}:${monday}`;
  const [week, setWeek] = useState<WeekData | null>(null);
  useEffect(() => {
    if (skip) return;
    let cancelled = false;
    api.historyDays(userId, weekBounds(monday)).then(
      ({ days }) => {
        if (!cancelled) setWeek({ key: weekKey, days });
      },
      () => {
        if (!cancelled) setWeek({ key: weekKey, days: null });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [userId, monday, weekKey, skip, refresh]);

  const places = useAllPlaces();
  const current = data?.key === key ? data : null;
  const raw = current && "points" in current ? current.points : null;
  const events = useMemo(() => (current && "events" in current ? current.events : []), [current]);
  const pts = useMemo(() => (raw ? usablePoints(raw) : []), [raw]);
  const segments = useMemo(() => buildTimeline(pts, places), [pts, places]);
  const summary = useMemo(() => summarize(pts, segments), [pts, segments]);
  const track = useMemo(() => pts.map((p) => [p.lon, p.lat] as [number, number]), [pts]);
  const stays = useMemo<MapStay[]>(() => segments.flatMap((s) => (s.kind === "stay" ? [{ lat: s.lat, lon: s.lon, place: s.place !== null }] : [])), [segments]);

  const [selected, setSelected] = useState<{ key: string; from: number } | null>(null);
  const [fit, setFit] = useState<{ coords: [number, number][]; token: number } | null>(null);
  const play = usePlayback(key, summary.first, summary.last);
  const sel = selected?.key === key ? segments.find((s) => s.from === selected.from) ?? null : null;
  const highlight = useMemo(() => (sel && sel.kind !== "stay" ? track.slice(sel.start, sel.end + 1) : null), [sel, track]);
  const playhead = play.t === null ? null : positionAt(pts, play.t);

  const fitTo = (coords: [number, number][]) => setFit((f) => ({ coords, token: (f?.token ?? 0) + 1 }));
  const choose = (seg: Segment) => {
    setSelected({ key, from: seg.from });
    fitTo(track.slice(seg.start, seg.end + 1));
    play.seek(seg.from);
  };
  const chooseEvent = (a: Alert) => {
    setSelected(null);
    if (a.location) fitTo([[a.location.lon, a.location.lat]]);
    play.seek(Date.parse(a.created_at));
  };

  const items = useMemo<Item[]>(() => {
    const list: Item[] = [...segments.map((seg, idx) => ({ kind: "seg" as const, seg, idx, t: seg.from })), ...events.map((alert) => ({ kind: "event" as const, alert, t: Date.parse(alert.created_at) }))];
    return list.sort((a, b) => a.t - b.t || (a.kind === "seg" ? -1 : 1));
  }, [segments, events]);

  const hhmm = (ms: number) => new Date(ms).toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" });
  const span = (a: number, b: number) => (hhmm(a) === hhmm(b) ? hhmm(a) : `${hhmm(a)} – ${hhmm(b)}`);
  const dropped = raw ? raw.length - pts.length : 0;

  let notice: { text: string; privacy?: boolean } | null = null;
  if (selfOff) notice = { text: people.length === 1 ? t("history.nobody") : t("history.offSelf"), privacy: true };
  else if (current && "error" in current) {
    notice = { text: current.error instanceof ApiError && current.error.status === 404 ? t("history.unavailable", { name: name || t("map.members") }) : errorText(current.error) };
  }

  // An empty day needs no map (it would only show the whole continent); it stays mounted though.
  const showMap = !notice && !(raw && pts.length === 0);

  const pick = (id: string) => {
    play.stop();
    navigate(id === app.me.id ? "/history" : `/history/${id}`);
  };
  // At a boundary (end of a stay = start of the next move) the later segment is the current one.
  const activeIdx = play.t === null ? -1 : segments.findLastIndex((s) => play.t! >= s.from && play.t! <= s.to);

  return (
    <div className="page history-page">
      <div className={"history-grid" + (showMap ? "" : " no-map")}>
        <div className="history-rail">
          <header className="history-head">
            <h1>{isSelf ? t("history.heading") : t("history.title", { name })}</h1>
            {people.length > 1 && (
              <div className="person-chips" role="group" aria-label={t("history.people")}>
                {people.map((p) => (
                  <button key={p.id} type="button" className="btn small secondary" aria-pressed={p.id === userId} onClick={() => pick(p.id)}>
                    {p.name}
                  </button>
                ))}
              </div>
            )}
            {!selfOff && <DayNav day={day} today={today} onDay={setDay} />}
            {!selfOff && <WeekStrip monday={monday} day={day} today={today} days={week?.key === weekKey ? week.days : undefined} onDay={setDay} />}
          </header>

          <div className="history-body">
            {notice ? (
              <div className="panel pad" role="status">
                <p>{notice.text}</p>
                {notice.privacy && (
                  <button className="btn secondary" type="button" onClick={() => navigate("/privacy")}>
                    {t("history.toPrivacy")}
                  </button>
                )}
              </div>
            ) : !raw ? (
              <p className="muted" role="status">
                {t("common.loading")}
              </p>
            ) : pts.length === 0 ? (
              <div className="panel pad" role="status">
                <p>
                  {t("history.empty")}
                  {dropped > 0 && ` (${t("history.suspect", { n: dropped })})`}
                </p>
              </div>
            ) : (
              <>
                <Summary summary={summary} hhmm={hhmm} />
                <section className="section" aria-labelledby="timeline-h">
                  <h2 id="timeline-h">{t("history.timeline")}</h2>
                  <ol className="timeline">
                    {items.map((it) =>
                      it.kind === "event" ? (
                        <li key={"e" + it.alert.id} className={"tl-item tl-event kind-" + it.alert.kind}>
                          <button type="button" className="tl-row" onClick={() => chooseEvent(it.alert)}>
                            <span className="tl-icon" aria-hidden="true">
                              {alertKindIcon(it.alert.kind)}
                            </span>
                            <span className="tl-text">
                              <span className="tl-title">{alertText(it.alert, t)}</span>
                              {it.alert.message && <span className="alert-message">{it.alert.message}</span>}
                              <span className="tl-meta">{hhmm(it.t)}</span>
                            </span>
                          </button>
                        </li>
                      ) : (
                        <li key={"s" + it.seg.from} className={"tl-item tl-" + it.seg.kind + (it.seg.kind === "stay" && it.seg.place ? " at-place" : "")} aria-current={it.idx === activeIdx ? "time" : undefined}>
                          <button type="button" className="tl-row" aria-pressed={sel?.from === it.seg.from} onClick={() => choose(it.seg)}>
                            <SegmentRow seg={it.seg} span={span} />
                          </button>
                        </li>
                      ),
                    )}
                  </ol>
                  <p className="small muted">
                    {t("history.about")}
                    {dropped > 0 && ` ${t("history.suspect", { n: dropped })}.`}
                    {raw.length >= LIMIT && ` ${t("history.truncated")}`}
                  </p>
                </section>
              </>
            )}
          </div>
        </div>

        {!notice && (
          <section className="history-stage" aria-label={t("history.mapLabel")} hidden={!showMap}>
            <HistoryMap styleUrl={app.instance.map_style_url} track={track} color={TRACK_COLOR} label={t("history.mapLabel")} stays={stays} highlight={highlight} playhead={playhead} fit={fit} />
            {summary.first !== null && summary.last !== null && summary.last > summary.first && (
              <Playback first={summary.first} last={summary.last} play={play} hhmm={hhmm} battery={playhead?.battery ?? null} speed={playhead?.speedKmh ?? null} noData={playhead?.noData ?? false} />
            )}
          </section>
        )}
      </div>
    </div>
  );
}

function SegmentRow({ seg, span }: { seg: Segment; span: (a: number, b: number) => string }) {
  const { t, locale } = useI18n();
  const dur = formatDuration(seg.to - seg.from);
  if (seg.kind === "stay") {
    return (
      <>
        <span className="tl-icon" aria-hidden="true">
          <PinIcon />
        </span>
        <span className="tl-text">
          <span className="tl-title">{seg.place?.name ?? t("history.stay")}</span>
          <span className="tl-meta">
            {span(seg.from, seg.to)}
            {seg.to > seg.from && ` · ${dur}`}
          </span>
        </span>
      </>
    );
  }
  if (seg.kind === "gap") {
    return (
      <>
        <span className="tl-icon" aria-hidden="true">
          <GapIcon />
        </span>
        <span className="tl-text">
          <span className="tl-title">{t("history.gap")}</span>
          <span className="tl-meta">
            {span(seg.from, seg.to)} · {dur}
            {seg.distanceM > 500 && ` · ${t("history.gapDistance", { d: formatDistance(seg.distanceM, locale) })}`}
          </span>
        </span>
      </>
    );
  }
  return (
    <>
      <span className="tl-icon" aria-hidden="true">
        {seg.mode === "walk" ? <WalkIcon /> : <RideIcon />}
      </span>
      <span className="tl-text">
        <span className="tl-title">
          {seg.mode === "walk" ? t("history.walk") : t("history.ride")} · {formatDistance(seg.distanceM, locale)}
        </span>
        <span className="tl-meta">
          {span(seg.from, seg.to)} · {dur}
          {seg.avgKmh >= 1 && ` · ${t("history.avgSpeed", { v: Math.round(seg.avgKmh) })}`}
        </span>
      </span>
    </>
  );
}

function DayNav({ day, today, onDay }: { day: string; today: string; onDay: (d: string) => void }) {
  const { t, locale } = useI18n();
  const [y, m, d] = day.split("-").map(Number) as [number, number, number];
  const label = new Date(y, m - 1, d).toLocaleDateString(locale, { weekday: "long", day: "numeric", month: "long" });
  const input = useRef<HTMLInputElement>(null);
  return (
    <div className="day-nav">
      <button className="btn small secondary icon-btn" type="button" aria-label={t("history.prevDay")} onClick={() => onDay(addDays(day, -1))}>
        <ChevronLeftIcon />
      </button>
      <span className="day-label">
        <button
          type="button"
          className="day-button"
          aria-label={`${t("history.date")}: ${label}`}
          onClick={() => {
            const el = input.current;
            if (!el) return;
            try {
              el.showPicker();
            } catch {
              el.focus();
            }
          }}
        >
          {label}
        </button>
        <input
          ref={input}
          className="day-input"
          type="date"
          tabIndex={-1}
          aria-hidden="true"
          value={day}
          max={today}
          onChange={(e) => e.target.value && onDay(e.target.value > today ? today : e.target.value)}
        />
      </span>
      <button className="btn small secondary icon-btn" type="button" aria-label={t("history.nextDay")} disabled={day >= today} onClick={() => onDay(addDays(day, 1))}>
        <ChevronRightIcon />
      </button>
      {day !== today && (
        <button className="btn small secondary" type="button" onClick={() => onDay(today)}>
          {t("history.today")}
        </button>
      )}
    </div>
  );
}

function WeekStrip({ monday, day, today, days, onDay }: { monday: string; day: string; today: string; days: HistoryDay[] | null | undefined; onDay: (d: string) => void }) {
  const { t, locale } = useI18n();
  if (days === null) return null; // overview not available – the day itself still works
  const max = Math.max(1000, ...(days ?? []).map((d) => d.distance_m));
  return (
    <div className="week-strip" role="group" aria-label={t("history.week")}>
      {Array.from({ length: 7 }, (_, i) => {
        const date = addDays(monday, i);
        const [y, m, d] = date.split("-").map(Number) as [number, number, number];
        const dt = new Date(y, m - 1, d);
        const info = days?.[i];
        const has = info !== undefined && info.points > 0;
        const km = has ? formatDistance(info.distance_m, locale) : "–";
        return (
          <button
            key={date}
            type="button"
            className={"week-day" + (date === today ? " today" : "")}
            aria-pressed={date === day}
            disabled={date > today}
            aria-label={t("history.weekDay", { day: dt.toLocaleDateString(locale, { weekday: "long", day: "numeric", month: "long" }), km: has ? km : t("history.weekNone") })}
            onClick={() => onDay(date)}
          >
            <span className="wd">{dt.toLocaleDateString(locale, { weekday: "short" })}</span>
            <span className="dn">{d}</span>
            <span className="bar" aria-hidden="true">
              <span style={{ height: has ? `${Math.max(8, (info.distance_m / max) * 100)}%` : 0 }} />
            </span>
            <span className="km" aria-hidden="true">
              {info === undefined ? " " : km}
            </span>
          </button>
        );
      })}
    </div>
  );
}

function Summary({ summary, hhmm }: { summary: DaySummary; hhmm: (ms: number) => string }) {
  const { t, locale } = useI18n();
  const b = summary.battery;
  return (
    <section className="section" aria-labelledby="summary-h">
      <h2 id="summary-h" className="visually-hidden">
        {t("history.summary")}
      </h2>
      <dl className="stats">
        <div>
          <dt>{t("history.distance")}</dt>
          <dd>{formatDistance(summary.distanceM, locale)}</dd>
        </div>
        <div>
          <dt>{t("history.moving")}</dt>
          <dd>{formatDuration(summary.movingMs)}</dd>
        </div>
        <div>
          <dt>{t("history.stays")}</dt>
          <dd>
            {summary.stays}
            {summary.places > 0 && <span className="sub">{t("history.placesN", { n: summary.places })}</span>}
          </dd>
        </div>
        <div>
          <dt>{t("history.active")}</dt>
          <dd>{summary.first !== null && summary.last !== null ? `${hhmm(summary.first)} – ${hhmm(summary.last)}` : "–"}</dd>
        </div>
        {b.length >= 2 && (
          <div className="wide">
            <dt>{t("history.battery")}</dt>
            <dd>
              {t("history.batteryRange", { a: b[0]!.pct, b: b[b.length - 1]!.pct })}
              <Sparkline values={b} from={summary.first!} to={summary.last!} />
            </dd>
          </div>
        )}
      </dl>
    </section>
  );
}

function Sparkline({ values, from, to }: { values: { t: number; pct: number }[]; from: number; to: number }) {
  const w = 300;
  const h = 40;
  const x = (t: number) => (to > from ? ((t - from) / (to - from)) * w : 0);
  const y = (p: number) => h - 2 - (p / 100) * (h - 4);
  const d = values.map((v, i) => `${i ? "L" : "M"}${x(v.t).toFixed(1)},${y(v.pct).toFixed(1)}`).join("");
  return (
    <svg className="spark" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" aria-hidden="true">
      <path d={`M0,${y(15)}H${w}`} className="spark-low" />
      <path d={d} className="spark-line" />
    </svg>
  );
}

type PlaybackState = ReturnType<typeof usePlayback>;

/** Playback over [first, last]: a time that runs through the day in PLAY_MS, or is set by hand. */
function usePlayback(key: string, first: number | null, last: number | null) {
  const [state, setState] = useState<{ key: string; t: number | null; playing: boolean }>({ key, t: null, playing: false });
  const cur = state.key === key ? state : { key, t: null, playing: false };
  const stop = useCallback(() => setState((s) => ({ ...s, playing: false })), []);
  const seek = useCallback((t: number) => setState({ key, t, playing: false }), [key]);
  const toggle = useCallback(() => {
    if (first === null || last === null) return;
    setState((s) => {
      const t = s.key === key ? s.t : null;
      if (s.key === key && s.playing) return { ...s, playing: false };
      return { key, t: t === null || t >= last ? first : t, playing: true };
    });
  }, [key, first, last]);

  useEffect(() => {
    if (!cur.playing || first === null || last === null) return;
    let raf = 0;
    let prev = performance.now();
    const step = (now: number) => {
      const dt = ((now - prev) * (last - first)) / PLAY_MS;
      prev = now;
      setState((s) => {
        if (!s.playing || s.t === null) return s;
        const t = Math.min(last, s.t + dt);
        return { ...s, t, playing: t < last };
      });
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [cur.playing, key, first, last]);

  return { t: cur.t, playing: cur.playing, stop, seek, toggle };
}

function Playback(props: { first: number; last: number; play: PlaybackState; hhmm: (ms: number) => string; battery: number | null; speed: number | null; noData: boolean }) {
  const { t } = useI18n();
  const { first, last, play, hhmm, battery, speed, noData } = props;
  const at = play.t ?? first;
  const info = [hhmm(at)];
  if (play.t !== null) {
    if (noData) info.push(t("history.noData"));
    else {
      if (battery !== null) info.push(t("history.batteryAt", { p: battery }));
      if (speed !== null && speed >= 1) info.push(t("history.speed", { v: Math.round(speed) }));
    }
  }
  return (
    <div className="playback">
      <button className="btn small play-btn" type="button" aria-label={play.playing ? t("history.pause") : t("history.play")} onClick={play.toggle}>
        {play.playing ? <PauseIcon /> : <PlayIcon />}
      </button>
      <div className="playback-track">
        <input
          className="range"
          type="range"
          min={first}
          max={last}
          step={60_000}
          value={at}
          aria-label={t("history.playhead")}
          aria-valuetext={info.join(", ")}
          onChange={(e) => play.seek(Number(e.target.value))}
        />
        <div className="playback-info small" aria-live="off">
          {info.join(" · ")}
        </div>
      </div>
    </div>
  );
}
