import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nProvider } from "../i18n";
import { AppProvider } from "../state";
import { MemberActions } from "./MemberActions";

const me = { id: "me", email: "anna@example.test", display_name: "Anna", locale: "de" as const, is_instance_admin: false, has_avatar: false, created_at: "2026-01-01T00:00:00Z" };
const instance = { map_style_url: "", registration_mode: "invite_only" as const, secure_context: true, setup_required: false, version: "test" };
const settings = { sharing_paused: false, history_enabled: true, history_retention_days: 30, share_battery: false, allow_ring: false, allow_locate_request: false, battery_alerts: true };

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function renderActions(userId: string, isSelf: boolean, routes: Record<string, () => Response>, historyGranted = false) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      if (routes[url]) return routes[url]!();
      if (url === "/api/v1/families") return json({ families: [] });
      if (url === "/api/v1/me/settings") return json(settings);
      if (url === "/api/v1/shares")
        return json({ outgoing: [], incoming: [{ user_id: "bob", display_name: "Bob", since: "2026-01-01T00:00:00Z", history: historyGranted }] });
      return json({ error: { code: "not_found", message: "x" } }, 404);
    }),
  );
  render(
    <I18nProvider initial="de">
      <AppProvider instance={instance} me={me} setMe={() => undefined} onSignedOut={() => undefined}>
        <MemberActions userId={userId} name={isSelf ? "Anna" : "Bob"} isSelf={isSelf} />
      </AppProvider>
    </I18nProvider>,
  );
}

const expires = new Date(Date.now() + 5 * 60_000).toISOString();

describe("MemberActions", () => {
  beforeAll(() => {
    // jsdom has no modal dialogs.
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
      this.open = true;
    };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
      this.open = false;
    };
  });
  afterEach(() => vi.unstubAllGlobals());

  it("explains a refused ring request (403) instead of failing silently", async () => {
    renderActions("bob", false, { "/api/v1/users/bob/ring": () => json({ error: { code: "forbidden", message: "no" } }, 403) });
    await userEvent.click(screen.getByRole("button", { name: "Handy klingeln lassen" }));
    // Ringing is loud: it is confirmed first.
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Klingeln lassen" }));
    expect(await screen.findByText(/Bob hat nicht erlaubt, dass die Familie das Handy klingeln lässt/)).toBeInTheDocument();
  });

  it("says whether a location request reached the phone or waits for it", async () => {
    let online = false;
    renderActions("bob", false, { "/api/v1/users/bob/locate": () => json({ alert_id: 1, expires_at: expires, online }, 202) });
    await userEvent.click(screen.getByRole("button", { name: "Standort jetzt aktualisieren" }));
    expect(await screen.findByText(/Sie wartet, bis das Handy von Bob online ist/)).toBeInTheDocument();
    online = true;
    await userEvent.click(screen.getByRole("button", { name: "Standort jetzt aktualisieren" }));
    expect(await screen.findByText(/Das Handy von Bob ist online/)).toBeInTheDocument();
  });

  it("explains a refused location request", async () => {
    renderActions("bob", false, { "/api/v1/users/bob/locate": () => json({ error: { code: "forbidden", message: "no" } }, 403) });
    await userEvent.click(screen.getByRole("button", { name: "Standort jetzt aktualisieren" }));
    expect(await screen.findByText("Bob hat nicht erlaubt, dass andere den Standort aktualisieren lassen.")).toBeInTheDocument();
  });

  it("offers the history only when it was granted", async () => {
    renderActions("bob", false, {}, true);
    expect(await screen.findByRole("button", { name: "Verlauf ansehen" })).toBeInTheDocument();
  });

  it("hides the history of someone who did not grant it", async () => {
    renderActions("bob", false, {}, false);
    await screen.findByRole("button", { name: "Handy klingeln lassen" });
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByRole("button", { name: "Verlauf ansehen" })).not.toBeInTheDocument();
  });

  it("offers ring-my-phones, my history and check-in for myself", async () => {
    renderActions("me", true, {});
    expect(screen.getByRole("button", { name: "Meine Handys klingeln lassen" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Bin angekommen" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Mein Verlauf" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Standort jetzt aktualisieren" })).not.toBeInTheDocument();
  });
});
