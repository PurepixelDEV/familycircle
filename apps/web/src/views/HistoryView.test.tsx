import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nProvider } from "../i18n";
import { AppProvider } from "../state";
import { HistoryView } from "./HistoryView";

// MapLibre needs WebGL; the map itself is not under test here.
vi.mock("../components/HistoryMap", () => ({ HistoryMap: () => <div data-testid="history-map" /> }));

const me = { id: "me", email: "anna@example.test", display_name: "Anna", locale: "de" as const, is_instance_admin: false, has_avatar: false, created_at: "2026-01-01T00:00:00Z" };
const instance = { map_style_url: "", registration_mode: "invite_only" as const, secure_context: true, setup_required: false, version: "test" };
const settings = (history: boolean) => ({ sharing_paused: false, history_enabled: history, history_retention_days: 30, share_battery: false, allow_ring: false, allow_locate_request: false, battery_alerts: true });

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const midnight = new Date();
midnight.setHours(0, 0, 0, 0);
const at = (h: number, m = 0) => new Date(midnight.getTime() + (h * 60 + m) * 60_000).toISOString();
const HOME = { lat: 47.3769, lon: 8.5417 };

function pt(iso: string, lat: number, lon: number, motion = "stationary") {
  return { id: iso, recorded_at: iso, received_at: iso, lat, lon, accuracy_m: 10, motion, freshness: "stale", suspect: false, battery_pct: null };
}

/** 07:00–08:00 at home, walk north ~1.5 km until 08:20, a stay there until 10:00, no data until 13:00, back home. */
function day() {
  const pts = [];
  for (let m = 0; m <= 60; m += 5) pts.push(pt(at(7, m), HOME.lat, HOME.lon));
  for (let i = 1; i < 10; i++) pts.push(pt(at(8, i * 2), HOME.lat + i * 0.00135, HOME.lon, "walking"));
  for (let m = 20; m <= 120; m += 5) pts.push(pt(at(8, m), HOME.lat + 0.0135, HOME.lon));
  for (let m = 0; m <= 30; m += 5) pts.push(pt(at(13, m), HOME.lat, HOME.lon));
  return pts.reverse(); // the API answers newest first
}

const checkIn = {
  id: 7, kind: "check_in", actor: { user_id: "bob", display_name: "Bob" }, from_device_id: null, place: null,
  location: { lat: HOME.lat + 0.0135, lon: HOME.lon, accuracy_m: 10 }, battery_pct: null, message: "Bin da",
  created_at: at(8, 25), expires_at: null, resolved_at: null, active: false, mine: false,
};

function renderHistory(userId: string | undefined, opts: { history?: boolean; granted?: boolean; routes?: Record<string, () => Response> } = {}) {
  const calls: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      calls.push(url);
      const path = url.split("?")[0]!;
      if (opts.routes?.[path]) return opts.routes[path]!();
      if (path === "/api/v1/families") return json({ families: [{ id: "fam", name: "Demo", role: "admin" }] });
      if (path === "/api/v1/families/fam/places")
        return json({ places: [{ id: "home", family_id: "fam", name: "Zuhause", lat: HOME.lat, lon: HOME.lon, radius_m: 120, created_by: null, created_at: "", muted: false, present: [] }] });
      if (path === "/api/v1/me/settings") return json(settings(opts.history ?? true));
      if (path === "/api/v1/shares")
        return json({ outgoing: [], incoming: [{ user_id: "bob", display_name: "Bob", since: "2026-01-01T00:00:00Z", history: opts.granted ?? true }] });
      return json({ error: { code: "not_found", message: "x" } }, 404);
    }),
  );
  render(
    <I18nProvider initial="de">
      <AppProvider instance={instance} me={me} setMe={() => undefined} onSignedOut={() => undefined}>
        <HistoryView userId={userId} />
      </AppProvider>
    </I18nProvider>,
  );
  return calls;
}

describe("HistoryView", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows a day as a timeline of stays, moves, gaps and events", async () => {
    renderHistory("bob", {
      routes: {
        "/api/v1/users/bob/locations": () => json({ points: day() }),
        "/api/v1/users/bob/history/events": () => json({ events: [checkIn] }),
        "/api/v1/users/bob/history/days": () => json({ days: [] }),
      },
    });
    // Stays are named once the places of my families are loaded.
    await screen.findAllByText("Zuhause");
    const timeline = screen.getByRole("list");
    const rows = within(timeline).getAllByRole("listitem");
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringContaining("Zuhause"),
      expect.stringMatching(/Zu Fuss · 1,5 km/),
      expect.stringContaining("Aufenthalt"),
      expect.stringContaining("Bob ist angekommen"),
      expect.stringContaining("Keine Standortdaten"),
      expect.stringContaining("Zuhause"),
    ]);
    expect(within(rows[3]!).getByText("Bin da")).toBeInTheDocument();
    // Totals: the walk plus the straight line across the gap back home.
    expect(screen.getByText("Strecke").nextSibling).toHaveTextContent("3 km");
    expect(screen.getByText("Unterwegs").nextSibling).toHaveTextContent("20 min");
    expect(screen.getByTestId("history-map")).toBeInTheDocument();

    // Choosing a move marks it and moves the playback there.
    const walk = within(rows[1]!).getByRole("button");
    await userEvent.click(walk);
    expect(walk).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("slider", { name: "Zeitpunkt" })).toHaveValue(String(Date.parse(at(8))));
  });

  it("offers everyone who granted their history, and switches between people", async () => {
    renderHistory(undefined, {
      routes: {
        "/api/v1/users/me/locations": () => json({ points: [] }),
        "/api/v1/users/me/history/events": () => json({ events: [] }),
        "/api/v1/users/me/history/days": () => json({ days: [] }),
      },
    });
    const people = await screen.findByRole("group", { name: "Wessen Verlauf" });
    expect(within(people).getByRole("button", { name: "Ich" })).toHaveAttribute("aria-pressed", "true");
    expect(within(people).getByRole("button", { name: "Bob" })).toBeInTheDocument();
    expect(await screen.findByText("An diesem Tag wurde nichts aufgezeichnet.")).toBeInTheDocument();
    // An empty day shows no map.
    expect(document.querySelector(".history-stage")).toHaveAttribute("hidden");
  });

  it("explains that my own history is off and loads nothing", async () => {
    const calls = renderHistory(undefined, { history: false, granted: false });
    expect(await screen.findByText(/noch niemand hat dir den Verlauf freigegeben/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Zu den Einstellungen" })).toBeInTheDocument();
    expect(calls.some((u) => u.includes("/locations"))).toBe(false);
  });

  it("explains a history that is not shared (404)", async () => {
    renderHistory("bob", {});
    expect(await screen.findByText(/Der Verlauf von Bob ist nicht verfügbar/)).toBeInTheDocument();
  });
});
