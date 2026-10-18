import { useState } from "react";
import { api, type PairingCode } from "../api/client";
import { useI18n } from "../i18n";
import { useNow } from "../lib/useNow";
import { pairUrl, QrCode } from "../components/QrCode";
import { useErrorText } from "../components/ui";

/** "Connect a phone": shows a short-lived pairing QR code (docs/QR_FORMAT.md). */
export function ConnectPhone() {
  const { t, locale } = useI18n();
  const errorText = useErrorText();
  const now = useNow(5_000);
  const [code, setCode] = useState<PairingCode | null>(null);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const create = async () => {
    setBusy(true);
    setError(undefined);
    try {
      setCode(await api.createPairingCode());
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const expired = code ? Date.parse(code.expires_at) <= now : false;
  return (
    <section className="section">
      <h2>{t("phone.title")}</h2>
      <div className="panel pad">
        <p>{t("phone.intro")}</p>
        {code && !expired && (
          <>
            <QrCode value={pairUrl(location.origin, code.code)} label={t("phone.qrLabel")} />
            <p className="small muted" style={{ textAlign: "center" }}>
              {t("phone.validUntil", { time: new Date(code.expires_at).toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" }) })}
            </p>
            <p className="notice">{t("phone.warning")}</p>
          </>
        )}
        {code && expired && <p className="notice">{t("phone.expired")}</p>}
        {error && (
          <p className="notice error" role="alert">
            {error}
          </p>
        )}
        <button className={code ? "btn secondary" : "btn"} type="button" disabled={busy} onClick={() => void create()}>
          {code ? t("phone.renew") : t("phone.show")}
        </button>
        <p className="small muted">{t("phone.manual", { url: location.origin })}</p>
      </div>
    </section>
  );
}
