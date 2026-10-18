import { render, screen } from "@testing-library/react";
import { I18nProvider } from "../i18n";
import { androidIntentUrl, apkUrl, appLink, isAndroid, whatsAppUrl } from "../lib/applink";
import { AppHandoff } from "./AppHandoff";

const INVITE = "https://fc.example.org/register?code=K7QF-2M9X-HD4R";

describe("app links", () => {
  it("wraps the original URL so the app can parse it like a QR code", () => {
    expect(appLink(INVITE)).toBe("familycircle://link?u=https%3A%2F%2Ffc.example.org%2Fregister%3Fcode%3DK7QF-2M9X-HD4R");
  });

  it("builds an intent URL with the APK as fallback and without a fixed package (debug builds)", () => {
    const url = androidIntentUrl(INVITE, "https://example.org/a.apk");
    expect(url).toMatch(/^intent:\/\/link\?u=https%3A%2F%2Ffc\.example\.org%2Fregister%3Fcode%3DK7QF-2M9X-HD4R#Intent;/);
    expect(url).toContain(";scheme=familycircle;");
    expect(url).toContain("S.browser_fallback_url=https%3A%2F%2Fexample.org%2Fa.apk;");
    expect(url).not.toContain("package=");
    expect(url.endsWith(";end")).toBe(true);
  });

  it("picks the APK of the server version, the release list otherwise", () => {
    expect(apkUrl("0.2.1")).toBe("https://github.com/PurepixelDEV/familycircle/releases/download/v0.2.1/familycircle-0.2.1.apk");
    expect(apkUrl("dev")).toBe("https://github.com/PurepixelDEV/familycircle/releases");
    expect(apkUrl("")).toBe("https://github.com/PurepixelDEV/familycircle/releases");
    expect(apkUrl("0.2.1/../../x")).toBe("https://github.com/PurepixelDEV/familycircle/releases");
  });

  it("detects Android user agents", () => {
    expect(isAndroid("Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 Chrome/141 Mobile")).toBe(true);
    expect(isAndroid("Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X)")).toBe(false);
    expect(isAndroid("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).toBe(false);
  });

  it("encodes the whole message for WhatsApp", () => {
    expect(whatsAppUrl("Hallo & tschüss\nhttps://x.org/?a=1")).toBe("https://wa.me/?text=Hallo%20%26%20tsch%C3%BCss%0Ahttps%3A%2F%2Fx.org%2F%3Fa%3D1");
  });
});

describe("AppHandoff", () => {
  const ua = navigator.userAgent;
  const setUa = (value: string) => Object.defineProperty(navigator, "userAgent", { value, configurable: true });
  afterEach(() => setUa(ua));

  it("offers download and open-in-app on Android", () => {
    setUa("Mozilla/5.0 (Linux; Android 16) Chrome/141 Mobile");
    render(
      <I18nProvider initial="de">
        <AppHandoff url={INVITE} version="0.2.1" />
      </I18nProvider>,
    );
    expect(screen.getByRole("link", { name: "1. App herunterladen" })).toHaveAttribute("href", apkUrl("0.2.1"));
    expect(screen.getByRole("link", { name: "2. In der App öffnen" })).toHaveAttribute("href", androidIntentUrl(INVITE, apkUrl("0.2.1")));
  });

  it("shows nothing on other devices", () => {
    setUa("Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X)");
    const { container } = render(
      <I18nProvider initial="de">
        <AppHandoff url={INVITE} version="0.2.1" />
      </I18nProvider>,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
