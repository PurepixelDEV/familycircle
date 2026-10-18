import { useI18n } from "../i18n";
import { androidIntentUrl, apkUrl, isAndroid } from "../lib/applink";

/**
 * On Android phones (an invite or pairing link opened from WhatsApp, a camera app …): download the
 * APK, then open the very same link in the app. Elsewhere nothing is shown.
 *
 * @param url the full invite/pairing URL of this page; it never leaves the device.
 * @param version server version, selects the matching APK (dev builds: release list).
 */
export function AppHandoff({ url, version, openLabel }: { url: string; version: string; openLabel?: string }) {
  const { t } = useI18n();
  if (!isAndroid()) return null;
  const apk = apkUrl(version);
  return (
    <section className="app-handoff panel pad" aria-labelledby="handoff-h">
      <h2 id="handoff-h">{t("applink.title")}</h2>
      <p className="muted">{t("applink.body")}</p>
      <a className="btn secondary" href={apk} rel="noopener noreferrer">
        {t("applink.install")}
      </a>
      <p className="small muted">{t("applink.installHint")}</p>
      <a className="btn" href={androidIntentUrl(url, apk)}>
        {openLabel ?? t("applink.open")}
      </a>
    </section>
  );
}
