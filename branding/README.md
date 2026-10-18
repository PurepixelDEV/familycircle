# Branding

| Datei | Verwendung |
|---|---|
| `familycircle-logo.png` | Logo mit Schriftzug für helle Hintergründe (README, Dokumente) |
| `familycircle-logo-dark.png` | Dasselbe für dunkle Hintergründe (Schriftzug hell) |
| `familycircle-mark.png` | Nur das Symbol, quadratisch, transparent – Quelle aller App-Icons |

Die App-Icons werden aus `familycircle-mark.png` erzeugt (transparente Störpixel entfernt, Symbol
vom Schriftzug getrennt):

* Web (`apps/web`): `src/assets/logo-mark.png`, `public/favicon.png`, `public/icon-192.png`,
  `public/icon-512.png`, `public/icon-maskable-512.png` (weisser Hintergrund, Symbol in der sicheren
  Zone), `public/apple-touch-icon.png`.
* Android (`apps/android/app/src/main/res`): `mipmap-*/ic_launcher_foreground.png` (Symbol auf 52 von
  108 dp, Hintergrund weiss), `mipmap-*/ic_launcher_monochrome.png` (Themen-Icon ab Android 13),
  `drawable-*/ic_notification.png` (weisse Silhouette), `drawable-nodpi/logo_mark.png` (288 px).

Der Schriftzug ist Teil der Bilddatei; in der App steht der Name als Text neben dem Symbol, damit er
übersetzbar, skalierbar und im Dunkelmodus lesbar bleibt.
