import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { Alert } from "../api/client";
import type { LiveEvent } from "../api/live";
import { I18nProvider } from "../i18n";
import { AppProvider } from "../state";
import { SosBanners } from "../components/Sos";
import { AlertsProvider, useAlerts } from "./useAlerts";

// Capture the shared live subscription instead of opening a WebSocket.
const live = vi.hoisted(() => ({ handlers: [] as { onEvent?: (e: LiveEvent) => void; onOpen?: () => void }[] }));
vi.mock("../api/live", () => ({
  subscribeLive: (h: { onEvent?: (e: LiveEvent) => void; onOpen?: () => void }) => {
    live.handlers.push(h);
    return () => undefined;
  },
}));

const me = { id: "me", email: "anna@example.test", display_name: "Anna", locale: "de" as const, is_instance_admin: false, has_avatar: false, created_at: "2026-01-01T00:00:00Z" };
const instance = { map_style_url: "", registration_mode: "invite_only" as const, secure_context: true, setup_required: false, version: "test" };
const settings = { sharing_paused: false, history_enabled: false, history_retention_days: 30, share_battery: false, allow_ring: false, allow_locate_request: false, battery_alerts: true };

function alert(over: Partial<Alert> & Pick<Alert, "id" | "kind">): Alert {
  return {
    actor: { user_id: "bob", display_name: "Bob" }, from_device_id: null, place: null, location: { lat: 47.37, lon: 8.54, accuracy_m: 12 },
    battery_pct: null, message: null, created_at: new Date().toISOString(), expires_at: null, resolved_at: null, active: false, mine: false, ...over,
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function Unread() {
  return <span data-testid="unread">{useAlerts().unread}</span>;
}

function setup(responses: { alerts: Alert[][]; resolve?: () => Response }) {
  const alertCalls: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/alerts")) {
        alertCalls.push(url);
        return json({ alerts: responses.alerts.shift() ?? [], server_time: new Date().toISOString() });
      }
      if (url === "/api/v1/sos/resolve" && init?.method === "POST") return responses.resolve?.() ?? json({ resolved: 1 });
      if (url === "/api/v1/families") return json({ families: [] });
      if (url === "/api/v1/shares") return json({ outgoing: [], incoming: [] });
      if (url === "/api/v1/me/settings") return json(settings);
      return json({ error: { code: "not_found", message: "x" } }, 404);
    }),
  );
  render(
    <I18nProvider initial="de">
      <AppProvider instance={instance} me={me} setMe={() => undefined} onSignedOut={() => undefined}>
        <AlertsProvider>
          <SosBanners />
          <Unread />
        </AlertsProvider>
      </AppProvider>
    </I18nProvider>,
  );
  return { alertCalls };
}

describe("alerts store", () => {
  beforeEach(() => {
    live.handlers = [];
    localStorage.clear();
  });
  afterEach(() => vi.unstubAllGlobals());

  it("shows another member's active SOS and ends it on sos_resolved", async () => {
    setup({ alerts: [[alert({ id: 7, kind: "sos", active: true })]] });
    expect(await screen.findByText("Bob hat einen SOS-Alarm ausgelöst")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Auf der Karte zeigen" })).toBeInTheDocument();

    act(() => live.handlers.forEach((h) => h.onEvent?.({ type: "alert", alert: alert({ id: 8, kind: "sos_resolved", location: null }) })));
    await waitFor(() => expect(screen.queryByText("Bob hat einen SOS-Alarm ausgelöst")).not.toBeInTheDocument());
  });

  it("catches up after a reconnect with the last REST cursor, not a live id", async () => {
    const { alertCalls } = setup({ alerts: [[alert({ id: 5, kind: "arrival", place: { id: "p", name: "Zuhause" } })], []] });
    await waitFor(() => expect(alertCalls).toHaveLength(1));
    expect(alertCalls[0]).toContain("after=0");
    await waitFor(() => expect(screen.getByTestId("unread")).toHaveTextContent("0")); // first load = seen

    // Live alert 9 arrives, then the connection drops and comes back.
    act(() => live.handlers.forEach((h) => h.onEvent?.({ type: "alert", alert: alert({ id: 9, kind: "departure", place: { id: "p", name: "Zuhause" } }) })));
    await waitFor(() => expect(screen.getByTestId("unread")).toHaveTextContent("1"));
    act(() => live.handlers.forEach((h) => h.onOpen?.()));
    await waitFor(() => expect(alertCalls).toHaveLength(2));
    expect(alertCalls[1]).toContain("after=5");
  });

  it("offers 'I am safe' for my own active SOS and resolves it", async () => {
    const mine = alert({ id: 3, kind: "sos", active: true, mine: true, actor: { user_id: "me", display_name: "Anna" } });
    const resolved = alert({ id: 4, kind: "sos_resolved", mine: true, actor: { user_id: "me", display_name: "Anna" }, location: null });
    const { alertCalls } = setup({ alerts: [[mine], [resolved]] });
    expect(await screen.findByText("SOS aktiv")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Ich bin in Sicherheit" }));
    await waitFor(() => expect(screen.queryByText("SOS aktiv")).not.toBeInTheDocument());
    expect(alertCalls[1]).toContain("after=3");
  });
});
