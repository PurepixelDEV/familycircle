import type { Alert, AlertKind } from "../api/client";
import type { MessageKey } from "../i18n/de";

/** How long an unresolved SOS counts as active (server rule, docs/ALERTS.md). */
export const SOS_ACTIVE_MS = 24 * 60 * 60_000;

/** Ring/locate requests are for phones; the web neither lists nor announces them. */
const PHONE_ONLY: ReadonlySet<AlertKind> = new Set(["ring", "locate"]);

/** Kinds announced with the browser's Notification API while the page is hidden. */
export const NOTIFY_KINDS: ReadonlySet<AlertKind> = new Set(["sos", "check_in", "arrival", "departure", "battery_low", "last_location"]);

export type AlertsState = {
  /** Displayable alerts, ascending by id, unique by id. */
  alerts: Alert[];
  /** Highest id confirmed by GET /alerts – the cursor for the next catch-up (`after`). */
  syncedId: number;
  /** Highest id seen from any source (REST or live). */
  lastId: number;
};

export const emptyAlerts: AlertsState = { alerts: [], syncedId: 0, lastId: 0 };

export function isDisplayable(a: Alert): boolean {
  return !PHONE_ONLY.has(a.kind);
}

/**
 * Merges alerts into the state. Pure; deduplicates by id (a later copy of the same id replaces the
 * earlier one) and keeps the list sorted. `fromRest` advances the catch-up cursor: alerts that only
 * arrived live never move it, so a catch-up after a reconnect cannot skip alerts missed in between.
 */
export function mergeAlerts(state: AlertsState, incoming: Alert[], fromRest: boolean): AlertsState {
  let lastId = state.lastId;
  let syncedId = state.syncedId;
  for (const a of incoming) {
    lastId = Math.max(lastId, a.id);
    if (fromRest) syncedId = Math.max(syncedId, a.id);
  }
  const shown = incoming.filter(isDisplayable);
  if (shown.length === 0) {
    return lastId === state.lastId && syncedId === state.syncedId ? state : { ...state, lastId, syncedId };
  }
  const byId = new Map(state.alerts.map((a) => [a.id, a]));
  for (const a of shown) byId.set(a.id, a);
  const alerts = [...byId.values()].sort((x, y) => x.id - y.id);
  return { alerts, syncedId, lastId };
}

/** Alerts from `incoming` that are not in the state yet (used to announce only new ones). */
export function newAlerts(state: AlertsState, incoming: Alert[]): Alert[] {
  const known = new Set(state.alerts.map((a) => a.id));
  return incoming.filter((a) => isDisplayable(a) && !known.has(a.id));
}

/**
 * SOS alerts that are active now: unresolved according to the server, younger than 24 h and not
 * followed by an `sos_resolved` of the same person (the stored SOS copy is not updated when it is
 * resolved – the resolution arrives as its own alert). Newest first; one per person.
 */
export function activeSos(alerts: Alert[], now: number): Alert[] {
  const resolvedAfter = new Map<string, number>(); // actor → id of their latest sos_resolved
  for (const a of alerts) {
    if (a.kind === "sos_resolved") resolvedAfter.set(a.actor.user_id, Math.max(resolvedAfter.get(a.actor.user_id) ?? 0, a.id));
  }
  const latest = new Map<string, Alert>();
  for (const a of alerts) {
    if (a.kind !== "sos" || !a.active || a.resolved_at) continue;
    if (now - Date.parse(a.created_at) >= SOS_ACTIVE_MS) continue;
    if ((resolvedAfter.get(a.actor.user_id) ?? 0) > a.id) continue;
    const cur = latest.get(a.actor.user_id);
    if (!cur || cur.id < a.id) latest.set(a.actor.user_id, a);
  }
  return [...latest.values()].sort((x, y) => y.id - x.id);
}

/** Splits active SOS alerts into my own one (any of my devices) and those of other people. */
export function sosState(alerts: Alert[], now: number, myId: string): { mine: Alert | null; others: Alert[] } {
  const active = activeSos(alerts, now);
  const isMine = (a: Alert) => a.mine || a.actor.user_id === myId;
  return { mine: active.find(isMine) ?? null, others: active.filter((a) => !isMine(a)) };
}

/** Number of received (not own) alerts newer than the seen cursor. */
export function unreadCount(alerts: Alert[], seenId: number): number {
  return alerts.filter((a) => a.id > seenId && !a.mine).length;
}

type T = (key: MessageKey, vars?: Record<string, string | number>) => string;

/** Main line of an alert, matching the Android wording (docs/ALERTS.md). */
export function alertText(a: Alert, t: T): string {
  const name = a.actor.display_name;
  const place = a.place?.name ?? "";
  switch (a.kind) {
    case "sos":
      return a.mine ? t("alert.sosMine") : t("alert.sos", { name });
    case "sos_resolved":
      return a.mine ? t("alert.sosResolvedMine") : t("alert.sosResolved", { name });
    case "check_in":
      if (a.mine) return place ? t("alert.checkInMinePlace", { place }) : t("alert.checkInMine");
      return place ? t("alert.checkInPlace", { name, place }) : t("alert.checkIn", { name });
    case "arrival":
      return t("alert.arrival", { name, place });
    case "departure":
      return t("alert.departure", { name, place });
    case "battery_low":
      return t("alert.batteryLow", { name, p: a.battery_pct ?? "?" });
    case "last_location":
      return t("alert.lastLocation", { name });
    case "ring":
      return t("alert.ring", { name });
    case "locate":
      return t("alert.locate", { name });
  }
}
