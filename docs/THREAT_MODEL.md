# FamilyCircle – Threat Model

Stand: 2026-10-18 · Methode: Assets → Angreifer → Szenarien (STRIDE-orientiert) → Gegenmassnahmen → Restrisiko.
Status der Massnahmen: ✅ umgesetzt und automatisiert getestet · 🟡 umgesetzt, nicht automatisiert getestet · ⏳ geplant.

## Schützenswerte Güter
1. Aktuelle und historische Standorte (höchste Schutzstufe: Stalking, Einbruch, Profilbildung).
2. Freigabebeziehungen (wer sieht wen) und Familienzugehörigkeit.
3. Zugangsdaten: Passwörter, Sitzungs-/Geräte-Tokens, Einladungs- und Setup-Codes.
4. Profilbilder (können EXIF-GPS enthalten).
5. Verfügbarkeit der Instanz (Familien verlassen sich darauf).

## Angreifer
| ID | Angreifer | Fähigkeiten |
|---|---|---|
| A1 | Dieb/Finder eines entsperrten oder gesperrten Telefons | physischer Gerätezugriff |
| A2 | Übernommenes Konto (Passwort-Wiederverwendung, Phishing) | gültige Zugangsdaten |
| A3 | Missbräuchlicher Familien-Admin (z. B. kontrollierende Partnerin/Partner) | Admin-Rolle in einer Familie |
| A4 | Anderer Benutzer derselben Instanz, fremde Familie | gültiges Konto, kennt/errät IDs |
| A5 | Netzwerkangreifer (WLAN, Proxy) | Mitlesen/Manipulieren unverschlüsselter Verbindungen |
| A6 | Internet-Angreifer ohne Konto | Brute Force, Scans, CSRF/XSS über fremde Websites |
| A7 | Stalker mit kurzem Zugriff auf das Telefon des Opfers | kann heimlich Freigaben erteilen |
| A8 | Server-Betreiber/Backup-Leser | Zugriff auf DB, Logs, Backups |

## Szenarien und Massnahmen

### Gestohlene Geräte (A1)
* Tokens liegen AES-GCM-verschlüsselt mit Keystore-Schlüssel auf dem Gerät; `allowBackup=false`. 🟡
* Betroffene Person entfernt das Gerät in der Web-App → alle Tokens des Geräts sofort ungültig, Live-Verbindungen
  werden geschlossen. ✅ (`TestSessionAndDeviceManagementIsolation`)
* Passwortänderung meldet alle anderen Sitzungen ab. ✅
* Gestohlenes Refresh-Token: Rotation + Wiederverwendungserkennung widerruft die Sitzung. ✅
* Restrisiko: Ein entsperrtes Telefon zeigt die Familienkarte, bis das Gerät entfernt wird. ⏳ optionale App-Sperre.

### Kompromittiertes Konto (A2)
* Argon2id, Längenregel, Rate-Limit pro Konto und IP, Dummy-Verifikation gegen Timing-Enumeration. ✅
* Sitzungsliste mit „Abmelden“ für jede Sitzung; Konto- und Standortlöschung erfordern das Passwort. ✅
* ⏳ Zwei-Faktor-Authentifizierung (TOTP/WebAuthn), ⏳ Benachrichtigung über neue Geräte-Anmeldungen.

### Missbräuchlicher Familien-Admin (A3)
* Rollen sind von Sichtbarkeit entkoppelt; Admin kann keine Freigaben für andere anlegen, keine Einstellungen
  anderer ändern, keine Verläufe lesen. ✅ (`TestAdminCannotBypassSharing`)
* Admin-Entfernung eines Mitglieds beendet gegenseitige Sichtbarkeit sofort. ✅
* Restrisiko: Admin kann ein Mitglied aus der Familie werfen (Verfügbarkeit, nicht Vertraulichkeit).
* ⏳ Audit-Log für Admin-Aktionen, für Betroffene einsehbar.

### Zugriff auf fremde Standortdaten / IDOR (A4)
* Jede Standortabfrage wertet die zentrale Sichtbarkeitsregel in der SQL-Abfrage aus. ✅
* Fremde und ungültige IDs → einheitlich 404. ✅ (`TestCrossFamilyIDOR`)
* Upload-Identität nur aus der Gerätesitzung, Body-Felder wie `user_id` werden ignoriert. ✅
  (`TestUploadCannotImpersonateOtherUser`)
* Web-Sitzungen dürfen keine Standorte hochladen (nur Gerätesitzungen). ✅
* Einladungen: 60 Bit, Ablauf, einmalig, zeilengesperrt eingelöst, Rate-Limit. ✅ (`TestInviteConcurrentRedemption`)

### Stalking-Risiken (A3, A7)
* Freigabe ist explizit pro Person; neue Mitglieder sehen nichts automatisch. ✅
* Tracking ist auf Android immer sichtbar (FGS-Benachrichtigung, Statuskarte in der App). 🟡
* Die Web-App zeigt dauerhaft, mit wie vielen Personen man teilt („Du teilst deinen Standort mit 2 Personen“). 🟡
* Pause wirkt sofort für alle. ✅ Widerruf entfernt den Marker live. ✅ (`TestLiveUpdatesRespectRevocation`)
* Wiedereintritt in eine Familie reaktiviert keine alten Freigaben. ✅
* Restrisiko A7: Wer kurz das entsperrte Telefon hat, kann eine Freigabe erteilen. ⏳ periodische Erinnerung
  („Du teilst seit 30 Tagen mit X“) und Benachrichtigung bei neuen ausgehenden Freigaben.

### Meldungen, Orte, Fernaktionen und Verlauf (Phase 3, docs/ALERTS.md)
* Empfänger jeder Meldung berechnet der Server in SQL (`store/alerts.go`, `audience`), nie der Client. ✅
* Automatische Meldungen (Ankunft/Abfahrt, Akku, letzter Standort) nur an Personen, die den Standort
  ohnehin sehen dürfen; beim Lesen erneut geprüft – ein Widerruf verbirgt auch ältere Meldungen. ✅
  (`TestBatteryAlertOnlyForViewersAndDeduplicated`, `TestPlacesArrivalDepartureWithHysteresis`)
* SOS/Check-in gehen bewusst an die ganze Familie (ausdrückliche Handlung der Person). ✅
* „Klingeln lassen“ und „Standort aktualisieren“ bei anderen nur mit deren Einwilligung (Standard aus);
  „Standort aktualisieren“ zusätzlich nur, wenn der Standort sichtbar ist; Rate-Limit pro Person. ✅
  (`TestRingAndLocateNeedOptIn`). Restrisiko: Belästigung durch wiederholtes Klingeln innerhalb einer
  Familie – Einwilligung jederzeit widerrufbar, Limit 10 Anfragen/Burst, 6/min.
* Anwesenheit an Orten (`present`) nur für sichtbare Personen. Orte kann jedes Mitglied anlegen – ein
  Ort verrät nichts, solange niemand teilt. ✅
* Verlauf anderer nur mit Freigabe + gegenseitiger Sichtbarkeit + eingeschaltetem Verlauf; jede
  fehlende Bedingung ist ein einfaches 404. ✅ (`TestHistorySharing`)
* Standortdaten löschen entfernt auch Positionen in eigenen Meldungen und die Anwesenheit an Orten;
  der Datenexport enthält gesendete Meldungen. ✅

### Netzwerk (A5)
* Produktion: HTTPS über Reverse-Proxy; `Secure`-Cookies und HSTS bei `https://`-`FC_PUBLIC_URL`. 🟡
* HTTP nur für LAN-Tests: Warnung im Log, in der Web-App und (mit Bestätigung) in der Android-App. 🟡
* Datenbank ohne veröffentlichten Port. 🟡

### Web-Angriffe (A6)
* CSRF: Pflicht-Header + Origin/Sec-Fetch-Site-Prüfung, SameSite=Lax, keine CORS-Freigaben. ✅ (`TestCSRFProtection`)
* XSS: React-Escaping, strikte CSP (`script-src 'self'`, kein Inline-Script), `nosniff`, `frame-ancestors 'none'`. 🟡
* Cross-Site-WebSocket-Hijacking: Origin-Prüfung beim Upgrade. 🟡
* Injection: ausschliesslich parametrisierte SQL-Abfragen. 🟡 (CodeQL in CI)
* Brute Force / Ressourcen: Rate Limits, Body-Limits, begrenzte Argon2-Parallelität, Header-Timeouts. ✅/🟡
* Übernahme einer frischen Instanz: Setup-Code aus dem Log. ✅ (`TestInstanceAndSetup`)

### Betreiber, Logs und Backups (A8)
* Keine Koordinaten, Tokens oder Request-Bodies in Logs (CI prüft Logs auf Testkoordinaten). ✅
* Standardmässig nur der letzte Standort; Verlauf nur nach Opt-in mit Aufbewahrungsfrist. ✅
* Secrets nur als Hash in der DB. ✅
* Restrisiko: Der Betreiber hat Zugriff auf die Datenbank. Selbsthosting heisst: Vertrauen in die eigene
  Instanz. ⏳ Option für Ende-zu-Ende-verschlüsselte Standorte prüfen (erschwert serverseitige Geofences).
* Backups enthalten Standortdaten → verschlüsselt ablegen (BACKUP_RESTORE.md). 🟡

### Drittanbieter
* Kartenkacheln: Der Anbieter sieht IP und Kartenausschnitt, nicht die Familienpositionen; selbst hostbar. 🟡
* Keine Telemetrie, keine Analytics, keine Google-Dienste, Schriften lokal. ✅
