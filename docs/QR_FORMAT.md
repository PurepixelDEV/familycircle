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

## Kopplungscode (API)
* `POST /api/v1/me/pairing-codes` (angemeldet) → `201 {"code": "...", "expires_at": "..."}`.
  Höchstens 5 offene Codes pro Konto; jeder neue Code ist 10 Minuten gültig.
* `POST /api/v1/auth/token` mit `{"grant_type":"pairing_code","pairing_code":"...","device_name":"...","app_version":"..."}`
  → `TokenResponse` (neues Gerät, wie beim Passwort-Login). Ungültig/abgelaufen/benutzt → `401 invalid_grant`.
* Der Code ist nach der ersten Einlösung verbraucht (zeilengesperrt, nebenläufig sicher).
