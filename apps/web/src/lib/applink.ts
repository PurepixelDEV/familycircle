// Links that hand an invite or pairing URL from the browser to the FamilyCircle Android app.
// Format: docs/QR_FORMAT.md ("App-Link").

const RELEASES = "https://github.com/PurepixelDEV/familycircle/releases";

/** `familycircle://link?u=<url>` – the app parses `u` exactly like a scanned QR code. */
export function appLink(url: string): string {
  return `familycircle://link?u=${encodeURIComponent(url)}`;
}

/**
 * Android intent URL for a button in the browser: opens the app if installed (release or debug
 * build), otherwise Chrome follows the fallback (the APK download).
 */
export function androidIntentUrl(url: string, fallback: string): string {
  return `intent://link?u=${encodeURIComponent(url)}#Intent;scheme=familycircle;S.browser_fallback_url=${encodeURIComponent(fallback)};end`;
}

/** Download of the signed APK that matches the server version; the release list for dev builds. */
export function apkUrl(version: string): string {
  return /^\d+\.\d+\.\d+$/.test(version) ? `${RELEASES}/download/v${version}/familycircle-${version}.apk` : RELEASES;
}

export function isAndroid(userAgent: string = navigator.userAgent): boolean {
  return /\bAndroid\b/i.test(userAgent);
}

/** WhatsApp share link (works with the app and WhatsApp Web). */
export function whatsAppUrl(text: string): string {
  return `https://wa.me/?text=${encodeURIComponent(text)}`;
}
