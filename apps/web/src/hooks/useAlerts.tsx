import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, type Alert } from "../api/client";
import { subscribeLive } from "../api/live";
import { useI18n } from "../i18n";
import { alertText, emptyAlerts, mergeAlerts, newAlerts, NOTIFY_KINDS, sosState, unreadCount, type AlertsState } from "../lib/alerts";
import { navigate } from "../lib/router";
import { useNow } from "../lib/useNow";
import { toast } from "../components/ui";
import { useApp } from "../state";

const PAGE = 500;
const MAX_PAGES = 20;

/** A point the map should fly to (from an alert or an SOS banner); `token` makes repeats distinct. */
export type MapFocus = { lat: number; lon: number; label: string; sos: boolean; token: number };

type AlertsCtx = {
  alerts: Alert[];
  sos: { mine: Alert | null; others: Alert[] };
  unread: number;
  markSeen: () => void;
  /** Adds my own alert as returned by POST /sos or /check-ins, before its live echo arrives. */
  add: (a: Alert) => void;
  /** Catch up with GET /alerts?after=<cursor> now (e.g. after resolving an SOS). */
  refresh: () => Promise<void>;
  focus: MapFocus | null;
  showOnMap: (a: Alert) => void;
  clearFocus: () => void;
};

const Ctx = createContext<AlertsCtx | null>(null);

function seenKey(userId: string) {
  return `fc.alertsSeen.${userId}`;
}

// Only an alert id (a counter) is stored – never alert contents or positions.
function loadSeen(userId: string): number | null {
  try {
    const v = localStorage.getItem(seenKey(userId));
    return v === null ? null : Number(v) || 0;
  } catch {
    return null;
  }
}

function storeSeen(userId: string, id: number) {
  try {
    localStorage.setItem(seenKey(userId), String(id));
  } catch {
    // ignore
  }
}

/**
 * Alerts for the signed-in user: loads GET /alerts once, receives new ones over the shared live
 * connection and catches up after every reconnect. Kept in memory only.
 */
export function AlertsProvider({ children }: { children: ReactNode }) {
  const { me } = useApp();
  const { t } = useI18n();
  const [state, setState] = useState<AlertsState>(emptyAlerts);
  const stateRef = useRef<AlertsState>(emptyAlerts);
  const ready = useRef(false);
  const queue = useRef<Promise<void>>(Promise.resolve());
  const [seenId, setSeenId] = useState<number | null>(() => loadSeen(me.id));
  const [focus, setFocus] = useState<MapFocus | null>(null);
  const focusToken = useRef(0);
  const now = useNow(30_000);

  const showOnMap = useCallback(
    (a: Alert) => {
      if (!a.location) return;
      focusToken.current++;
      setFocus({ lat: a.location.lat, lon: a.location.lon, label: alertText(a, t), sos: a.kind === "sos", token: focusToken.current });
      navigate("/");
    },
    [t],
  );

  // Read by the live/REST callbacks below (outside render) to word and route announcements.
  const announceWith = useRef({ t, showOnMap });
  useEffect(() => {
    announceWith.current = { t, showOnMap };
  }, [t, showOnMap]);

  const apply = useCallback((incoming: Alert[], fromRest: boolean, announceNew: boolean) => {
    const fresh = newAlerts(stateRef.current, incoming);
    stateRef.current = mergeAlerts(stateRef.current, incoming, fromRest);
    setState(stateRef.current);
    if (announceNew && fresh.length > 0) announce(fresh, announceWith.current.t, announceWith.current.showOnMap);
  }, []);

  const refresh = useCallback(() => {
    queue.current = queue.current
      .then(async () => {
        let after = stateRef.current.syncedId;
        for (let page = 0; page < MAX_PAGES; page++) {
          const { alerts } = await api.alerts(after, PAGE);
          // The first load only fills the list; everything after it is news worth announcing.
          apply(alerts, true, ready.current);
          if (alerts.length < PAGE) break;
          after = alerts[alerts.length - 1]!.id;
        }
        if (!ready.current) {
          ready.current = true;
          // First visit in this browser: what is already there counts as seen.
          setSeenId((cur) => cur ?? stateRef.current.lastId);
        }
      })
      .catch(() => undefined);
    return queue.current;
  }, [apply]);

  useEffect(() => {
    void refresh();
    return subscribeLive({
      onOpen: () => void refresh(), // catch up with whatever arrived while disconnected
      onEvent: (ev) => {
        if (ev.type === "alert") apply([ev.alert], false, true);
      },
    });
  }, [refresh, apply]);

  const markSeen = useCallback(() => {
    const id = stateRef.current.lastId;
    setSeenId((cur) => (cur !== null && cur >= id ? cur : id));
    storeSeen(me.id, id);
  }, [me.id]);

  /** Adds the alert returned by POST /sos or /check-ins (the caller's own copy, `mine: true`). */
  const add = useCallback((a: Alert) => apply([a], false, false), [apply]);
  /** Called when the map closes, so returning to the map later does not jump to an old alert. */
  const clearFocus = useCallback(() => setFocus(null), []);

  const value = useMemo<AlertsCtx>(
    () => ({
      alerts: state.alerts,
      sos: sosState(state.alerts, now, me.id),
      unread: seenId === null ? 0 : unreadCount(state.alerts, seenId),
      markSeen,
      add,
      refresh,
      focus,
      showOnMap,
      clearFocus,
    }),
    [state.alerts, now, me.id, seenId, markSeen, add, refresh, focus, showOnMap, clearFocus],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAlerts(): AlertsCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useAlerts outside AlertsProvider");
  return ctx;
}

/** Toasts new alerts – or, while the page is hidden, shows them as system notifications. */
function announce(list: Alert[], t: ReturnType<typeof useI18n>["t"], showOnMap: (a: Alert) => void) {
  for (const a of list) {
    if (a.mine) continue;
    const text = alertText(a, t);
    const body = a.message ?? undefined;
    const notifiable = NOTIFY_KINDS.has(a.kind) || a.kind === "sos_resolved";
    if (document.visibilityState === "hidden" && notifiable && notify(text, body, a, () => showOnMap(a))) continue;
    toast(body ? `${text}: ${body}` : text);
  }
}

/** Shows a system notification if the user allowed it (asked only from the settings). */
function notify(title: string, body: string | undefined, a: Alert, onClick: () => void): boolean {
  if (typeof Notification === "undefined" || Notification.permission !== "granted") return false;
  // SOS and its resolution share a tag, so "is safe" replaces the alarm (as on Android).
  const tag = a.kind === "sos" || a.kind === "sos_resolved" ? `fc-sos-${a.actor.user_id}` : `fc-alert-${a.id}`;
  try {
    const n = new Notification(title, { body, tag, icon: "/icon-192.png", requireInteraction: a.kind === "sos" });
    n.onclick = () => {
      window.focus();
      onClick();
      n.close();
    };
    return true;
  } catch {
    // Some mobile browsers only allow notifications through a service worker.
    navigator.serviceWorker?.ready
      .then((reg) => reg.showNotification(title, { body, tag, icon: "/icon-192.png" }))
      .catch(() => undefined);
    return true;
  }
}
