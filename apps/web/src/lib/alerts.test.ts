import type { Alert } from "../api/client";
import { de } from "../i18n/de";
import { format } from "../i18n";
import { activeSos, alertText, emptyAlerts, mergeAlerts, newAlerts, sosState, unreadCount } from "./alerts";

const now = Date.parse("2026-10-18T12:00:00Z");
const ago = (min: number) => new Date(now - min * 60_000).toISOString();

function alert(over: Partial<Alert> & Pick<Alert, "id" | "kind">): Alert {
  return {
    actor: { user_id: "bob", display_name: "Bob" },
    from_device_id: null,
    place: null,
    location: null,
    battery_pct: null,
    message: null,
    created_at: ago(5),
    expires_at: null,
    resolved_at: null,
    active: false,
    mine: false,
    ...over,
  };
}

const t = (key: keyof typeof de, vars?: Record<string, string | number>) => format(de[key], vars);

describe("mergeAlerts", () => {
  it("deduplicates by id, keeps the list sorted and replaces an older copy", () => {
    let s = mergeAlerts(emptyAlerts, [alert({ id: 3, kind: "arrival" }), alert({ id: 1, kind: "check_in" })], true);
    s = mergeAlerts(s, [alert({ id: 3, kind: "arrival", message: "updated" }), alert({ id: 2, kind: "departure" })], false);
    expect(s.alerts.map((a) => a.id)).toEqual([1, 2, 3]);
    expect(s.alerts[2]!.message).toBe("updated");
  });

  it("returns the same state object when nothing changes", () => {
    const s = mergeAlerts(emptyAlerts, [alert({ id: 1, kind: "sos" })], true);
    expect(mergeAlerts(s, [], true)).toBe(s);
  });

  it("advances the catch-up cursor only with REST results, never with live alerts", () => {
    let s = mergeAlerts(emptyAlerts, [alert({ id: 10, kind: "check_in" })], true);
    expect(s.syncedId).toBe(10);
    // A live alert with a higher id must not move the cursor: alerts 11–19 might have been missed.
    s = mergeAlerts(s, [alert({ id: 20, kind: "arrival" })], false);
    expect(s.syncedId).toBe(10);
    expect(s.lastId).toBe(20);
    s = mergeAlerts(s, [alert({ id: 15, kind: "departure" }), alert({ id: 20, kind: "arrival" })], true);
    expect(s.syncedId).toBe(20);
    expect(s.alerts.map((a) => a.id)).toEqual([10, 15, 20]);
  });

  it("does not list ring/locate requests (phones only) but still moves the cursor past them", () => {
    const s = mergeAlerts(emptyAlerts, [alert({ id: 4, kind: "ring", active: true }), alert({ id: 5, kind: "locate" })], true);
    expect(s.alerts).toEqual([]);
    expect(s.syncedId).toBe(5);
  });

  it("reports only alerts that are new to the state", () => {
    const s = mergeAlerts(emptyAlerts, [alert({ id: 1, kind: "sos" })], true);
    expect(newAlerts(s, [alert({ id: 1, kind: "sos" }), alert({ id: 2, kind: "check_in" }), alert({ id: 3, kind: "ring" })]).map((a) => a.id)).toEqual([2]);
  });
});

describe("active SOS", () => {
  it("is active until the same person sends sos_resolved", () => {
    const sos = alert({ id: 1, kind: "sos", active: true });
    expect(activeSos([sos], now).map((a) => a.id)).toEqual([1]);
    expect(activeSos([sos, alert({ id: 2, kind: "sos_resolved" })], now)).toEqual([]);
    // Someone else's resolution does not end Bob's SOS.
    expect(activeSos([sos, alert({ id: 2, kind: "sos_resolved", actor: { user_id: "clara", display_name: "Clara" } })], now)).toHaveLength(1);
  });

  it("is active again for a new SOS after a resolved one", () => {
    const list = [alert({ id: 1, kind: "sos", active: true }), alert({ id: 2, kind: "sos_resolved" }), alert({ id: 3, kind: "sos", active: true })];
    expect(activeSos(list, now).map((a) => a.id)).toEqual([3]);
  });

  it("expires after 24 hours and respects the server's flags", () => {
    expect(activeSos([alert({ id: 1, kind: "sos", active: true, created_at: ago(24 * 60 + 1) })], now)).toEqual([]);
    expect(activeSos([alert({ id: 1, kind: "sos", active: false })], now)).toEqual([]);
    expect(activeSos([alert({ id: 1, kind: "sos", active: true, resolved_at: ago(1) })], now)).toEqual([]);
  });

  it("shows one SOS per person (the latest)", () => {
    const list = [alert({ id: 1, kind: "sos", active: true }), alert({ id: 2, kind: "sos", active: true })];
    expect(activeSos(list, now).map((a) => a.id)).toEqual([2]);
  });

  it("separates my own SOS (mine, from any of my devices) from received ones", () => {
    const list = [
      alert({ id: 1, kind: "sos", active: true, actor: { user_id: "me", display_name: "Anna" }, mine: true }),
      alert({ id: 2, kind: "sos", active: true }),
    ];
    const { mine, others } = sosState(list, now, "me");
    expect(mine?.id).toBe(1);
    expect(others.map((a) => a.id)).toEqual([2]);
    expect(sosState([list[1]!], now, "me").mine).toBeNull();
  });
});

describe("unread and wording", () => {
  it("counts only received alerts newer than the seen cursor", () => {
    const list = [alert({ id: 1, kind: "arrival" }), alert({ id: 2, kind: "check_in", mine: true }), alert({ id: 3, kind: "departure" })];
    expect(unreadCount(list, 0)).toBe(2);
    expect(unreadCount(list, 1)).toBe(1);
    expect(unreadCount(list, 3)).toBe(0);
  });

  it("uses the agreed texts for received and own alerts", () => {
    expect(alertText(alert({ id: 1, kind: "sos" }), t)).toBe("Bob hat einen SOS-Alarm ausgelöst");
    expect(alertText(alert({ id: 1, kind: "sos", mine: true }), t)).toBe("Du hast einen SOS-Alarm ausgelöst");
    expect(alertText(alert({ id: 1, kind: "sos_resolved" }), t)).toBe("Bob ist in Sicherheit");
    expect(alertText(alert({ id: 1, kind: "check_in", place: { id: null, name: "Schule" } }), t)).toBe("Bob ist angekommen – Schule");
    expect(alertText(alert({ id: 1, kind: "arrival", place: { id: "p", name: "Zuhause" } }), t)).toBe("Bob ist bei Zuhause angekommen");
    expect(alertText(alert({ id: 1, kind: "departure", place: { id: "p", name: "Zuhause" } }), t)).toBe("Bob hat Zuhause verlassen");
    expect(alertText(alert({ id: 1, kind: "battery_low", battery_pct: 12 }), t)).toBe("Bob: Akku 12 %");
    expect(alertText(alert({ id: 1, kind: "last_location" }), t)).toBe("Handy von Bob wurde ausgeschaltet – letzter Standort gespeichert");
  });
});
