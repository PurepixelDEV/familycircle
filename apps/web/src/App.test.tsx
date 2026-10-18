import { render, screen } from "@testing-library/react";
import { App } from "./App";
import { I18nProvider } from "./i18n";

const instance = { map_style_url: "", registration_mode: "invite_only", secure_context: true, setup_required: false, version: "0.2.1" };

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("App start-up", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState(null, "", "/");
  });

  // Regression: the 401 of the session probe redirected to /login and dropped the invite code.
  it("keeps an invite link opened in a browser without a session", async () => {
    window.history.replaceState(null, "", "/register?code=K7QF-2M9X-HD4R");
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) =>
        url.endsWith("/instance") ? json(200, instance) : json(401, { error: { code: "unauthorized", message: "no session" } }),
      ),
    );
    render(
      <I18nProvider initial="de">
        <App />
      </I18nProvider>,
    );
    expect(await screen.findByRole("heading", { name: "Einladung annehmen" })).toBeInTheDocument();
    expect(screen.getByLabelText("Einladungscode")).toHaveValue("K7QF-2M9X-HD4R");
    expect(window.location.pathname).toBe("/register");
  });
});
