import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nProvider } from "../i18n";
import { PasswordInput } from "./ui";

describe("PasswordInput", () => {
  it("hides the password by default and toggles visibility accessibly", async () => {
    render(
      <I18nProvider initial="de">
        <label htmlFor="pw">Passwort</label>
        <PasswordInput id="pw" defaultValue="geheim" />
      </I18nProvider>,
    );
    const input = screen.getByLabelText("Passwort");
    expect(input).toHaveAttribute("type", "password");
    const toggle = screen.getByRole("button", { name: "Passwort anzeigen" });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(toggle);
    expect(input).toHaveAttribute("type", "text");
    expect(screen.getByRole("button", { name: "Passwort verbergen" })).toHaveAttribute("aria-pressed", "true");
  });
});
