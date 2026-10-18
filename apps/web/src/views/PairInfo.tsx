import { useI18n } from "../i18n";
import { navigate } from "../lib/router";
import { Logo } from "../components/ui";

/**
 * Landing page for /pair?code=… when a pairing QR code is scanned with a normal camera app.
 * The code itself is never shown, stored or sent anywhere from here.
 */
export function PairInfo() {
  const { t } = useI18n();
  return (
    <div className="auth">
      <div className="auth-card">
        <div className="auth-mark">
          <Logo size={44} /> {t("app.name")}
        </div>
        <h1>{t("pair.title")}</h1>
        <p>{t("pair.body")}</p>
        <p className="muted">{t("pair.noApp")}</p>
        <p className="muted">{t("pair.web")}</p>
        <button className="btn secondary" type="button" onClick={() => navigate("/", true)}>
          {t("pair.toWeb")}
        </button>
      </div>
    </div>
  );
}
