# QR-Codes für die Verbindung der App

Alle QR-Codes enthalten eine normale **https-/http-URL** des eigenen Servers. Eine Handy-Kamera ohne App öffnet
damit eine Hinweisseite der Web-App; die FamilyCircle-App erkennt dieselben URLs im eingebauten Scanner.

Kein QR-Code enthält ein Passwort oder ein langlebiges Token. Codes in QR-Codes sind kurzlebig und einmalig.

| Zweck | URL | Gültigkeit | Erzeugt in |
|---|---|---|---|
| Nur Server verbinden | `<origin>/` | – | Web: Privatsphäre → „Handy verbinden“ (Fallback) |
| **Handy koppeln** (bestehendes Konto) | `<origin>/pair?code=XXXX-XXXX-XXXX` | 10 min, einmalig | Web: Privatsphäre → „Handy verbinden“ |
| **Einladung** (neues Konto) | `<origin>/register?code=XXXX-XXXX-XXXX` | ≤ 7 Tage, Standard einmalig | Web: Familie → „Jemanden einladen“ |

`<origin>` ist `scheme://host[:port]` ohne Pfad (Unterpfade werden nicht unterstützt).

## Verarbeitung in der App
1. URL parsen; nur `http`/`https`, keine Benutzerinfo, kein Fragment. Server = Origin.
2. `GET <origin>/api/v1/instance` prüfen (wie bei manueller Eingabe, inkl. HTTP-Warnung).
3. Pfad `/pair` + `code` → `POST /api/v1/auth/token` mit `grant_type=pairing_code`.
   Pfad `/register` + `code` → Registrierungsformular mit vorausgefülltem Einladungscode.
   Sonst → Anmeldeformular.
4. Codes werden nie gespeichert oder geloggt.

## Einladung per Link (WhatsApp, SMS, E-Mail)
Einladungen werden als normale Nachricht mit dem `/register?code=…`-Link geteilt:
Web → Familie → „Einladungscode erstellen“ → „Link teilen“ / „Per WhatsApp senden“ / „Nachricht kopieren“;
Android → Familie → „Einladungslink erstellen und teilen“ (System-Teilen-Menü). Nur Verwalter.

Öffnet der Empfänger den Link im Browser, zeigt die Registrierungsseite auf Android-Handys zusätzlich:
1. **App herunterladen** – die signierte APK passend zur Serverversion
   (`https://github.com/PurepixelDEV/familycircle/releases/download/vX.Y.Z/familycircle-X.Y.Z.apk`,
   bei Entwicklungsversionen die Release-Liste).
2. **In der App öffnen** – App-Link (siehe unten). Ohne installierte App folgt Chrome dem APK-Download.

Auf anderen Geräten (iPhone, Computer) bleibt nur die Registrierung im Browser.

## App-Link
`familycircle://link?u=<URL-kodierte QR-URL>` – vom Browser per
`intent://link?u=…#Intent;scheme=familycircle;S.browser_fallback_url=…;end` geöffnet (ohne festes Paket, damit
auch der Debug-Build `…android.debug` reagiert). Die App prüft `u` exakt wie einen gescannten QR-Code.

| Zustand der App | Einladung `/register` | Kopplung `/pair` |
|---|---|---|
| Noch kein Server | Server prüfen → Registrierung mit Code | Server prüfen → Kopplung |
| Abgemeldet, gleicher Server | Registrierung mit Code | Neu beginnen beim Server-Schritt |
| Abgemeldet, anderer Server | Neu beginnen beim Server-Schritt | Neu beginnen beim Server-Schritt |
| Angemeldet, gleicher Server | Rückfrage „Der Familie beitreten?“ → `POST /invites/accept` | Hinweis „bereits verbunden“ |
| Angemeldet, anderer Server | Hinweis, nichts wird geändert | Hinweis, nichts wird geändert |

Ein Link meldet nie ab und wechselt nie den Server eines angemeldeten Handys.

## Kopplungscode (API)
* `POST /api/v1/me/pairing-codes` (angemeldet) → `201 {"code": "...", "expires_at": "..."}`.
  Höchstens 5 offene Codes pro Konto; jeder neue Code ist 10 Minuten gültig.
* `POST /api/v1/auth/token` mit `{"grant_type":"pairing_code","pairing_code":"...","device_name":"...","app_version":"..."}`
  → `TokenResponse` (neues Gerät, wie beim Passwort-Login). Ungültig/abgelaufen/benutzt → `401 invalid_grant`.
* Der Code ist nach der ersten Einlösung verbraucht (zeilengesperrt, nebenläufig sicher).
