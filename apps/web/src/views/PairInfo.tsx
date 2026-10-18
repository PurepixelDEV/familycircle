import { useI18n } from "../i18n";
import { navigate, queryParam } from "../lib/router";
import { AppHandoff } from "../components/AppHandoff";
import { pairUrl } from "../components/QrCode";
import { Logo } from "../components/ui";

/**
 * Landing page for /pair?code=… when a pairing QR code is scanned with a normal camera app.
 * The code is never shown, stored or sent to a server from here; on Android the link can be handed
 * to the installed app (AppHandoff), which redeems it like a scanned code.
 */
export function PairInfo() {
  const { t } = useI18n();
  const code = queryParam("code");
  return (
    <div className="auth">
      <div className="auth-card">
        <div className="auth-mark">
          <Logo size={44} /> {t("app.name")}
        </div>
        <h1>{t("pair.title")}</h1>
        <p>{t("pair.body")}</p>
        {/* Instance not loaded on this page: the release list instead of a version-matched APK. */}
        {code && <AppHandoff url={pairUrl(location.origin, code)} version="" openLabel={t("pair.openApp")} />}
        <p className="muted">{t("pair.noApp")}</p>
        <p className="muted">{t("pair.web")}</p>
        <button className="btn secondary" type="button" onClick={() => navigate("/", true)}>
          {t("pair.toWeb")}
        </button>
      </div>
    </div>
  );
}
