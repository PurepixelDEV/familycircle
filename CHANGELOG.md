# Changelog

Alle nennenswerten Änderungen. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
Versionierung nach [Semantic Versioning](https://semver.org/lang/de/).

## [Unreleased]

### Hinzugefügt – Phase 3 (docs/ALERTS.md)
- **SOS**: Gedrückt halten → Alarm mit Position an die ganze Familie, „Ich bin in Sicherheit“ beendet ihn.
- **Orte mit Ankunft/Abfahrt**: Orte pro Familie (Radius 50–2000 m); der Server erkennt Betreten und
  Verlassen mit Hysterese und meldet es Personen, die den Standort sehen dürfen; pro Ort stummschaltbar.
- **Akku-Warnung und letzter Standort** bei ≤ 15 % Akku und beim Ausschalten (abschaltbar).
- **„Standort jetzt aktualisieren“** und **„Handy klingeln lassen“** – eigene Geräte immer, andere nur mit
  deren Einwilligung (Standard aus).
- **Check-in „Bin angekommen“** mit optionaler Nachricht.
- **Verlauf / Zeitstrahl**: eigener Verlauf; anderer nur, wenn die Person „darf meinen Verlauf sehen“ erteilt.
- Zustellung ohne Google-Push: Live-Verbindung beim Teilen oder mit „Alarme immer sofort empfangen“,
  sonst Nachholen spätestens alle 15 min (`GET /alerts`).
- API 0.3.0, Migration `0003_alerts_places`.

### Hinzugefügt
- Familie per Link einladen (WhatsApp, SMS, E-Mail): Web „Link teilen“ / „Per WhatsApp senden“ /
  „Nachricht kopieren“; Android „Einladungslink erstellen und teilen“ für Verwalter.
- Einladungs- und Kopplungsseite im Browser bieten auf Android „App herunterladen“ (APK passend zur
  Serverversion) und „In der App öffnen“ (`familycircle://link`, docs/QR_FORMAT.md). Angemeldete Nutzer
  treten mit einem Einladungslink nach Rückfrage einer weiteren Familie bei.

### Behoben
- Web: Ein Einladungslink, der in einem Browser ohne Sitzung geöffnet wurde, leitete auf `/login` um und
  verlor den Code (Sitzungsprüfung beim Start löste die Abmelde-Weiterleitung aus).

## [0.2.1] - 2026-10-18

Teilen läuft weiter, wenn die App weggewischt wird; neues Logo. Update über 0.2.0 ohne Neuanmeldung
(gleicher Signaturschlüssel); Server ohne Datenbankänderung. Hardwaretests weiterhin offen.

### Geändert
- Android: Das Teilen endet nur noch durch den Benutzer. Wegwischen der App aus der Übersicht beendet
  es nicht (`stopWithTask=false`); die Benachrichtigung hat neben „Pausieren“ jetzt „Beenden“.
- Android: Beendet Android das Teilen trotzdem (Hersteller-Taskkiller, Neustart, **App-Update**), startet
  es von selbst wieder, sofern „Standort immer erlauben“ erteilt ist (Watchdog alle 15 min, Prüfung
  1 min nach dem Wegwischen, `MY_PACKAGE_REPLACED`). Sonst erscheint ein Hinweis zum Fortsetzen; der
  Status bleibt „pausiert“ statt „aus“.
- Android: Startseite bietet während des Teilens „Akku-Optimierung ausschalten“ und „Standort immer
  erlauben“ an, solange eines davon fehlt.
- Android und Web: Passwort anzeigen/verbergen bei Anmeldung und Registrierung.
- Neues Logo (Familien-Herz) überall: Web-Favicon, PWA-Icons, Anmeldeseiten; Android-App-Icon
  (inkl. Themen-Icon), Benachrichtigungssymbol, Willkommens- und Anmeldebildschirm. Quellen in `branding/`.

## [0.2.0] - 2026-10-18

Phase 1.5: benutzbare Familien-App, Hardware-Testbereitschaft, vereinfachtes Deployment. Pre-Release zum Testen – Android-Hardwaretests stehen noch aus (docs/HARDWARE_TESTS.md).

### Hinzugefügt
- Kopplungscodes: Handy per QR-Code mit bestehendem Konto verbinden (`POST /me/pairing-codes`,
  `grant_type=pairing_code`, 10 min, einmalig) – Migration `0002_pairing_codes`.
- Web: QR-Codes für „Handy verbinden“ und Einladungen, Hinweisseite `/pair`.
- `FC_DB_PASSWORD_FILE` (Passwort als Datei/Secret); Log-Hinweis mit der Adresse eines nicht vertrauten Proxys.
- Testskripte: Datenbank-Upgrade (`scripts/upgrade-test.sh`), HTTPS über Reverse Proxy
  (`scripts/https-proxy-test.sh`), Entwicklungs-Testdaten (`scripts/seed-dev-data.py`),
  Image-Archiv für Portainer-Import (`scripts/build-image-archive.sh`).

- Android: Familienkarte (MapLibre Native) mit Profilbildern, Genauigkeitskreisen, eigenem Standort,
  Aktualität (live/kürzlich/veraltet) und Live-Updates per WebSocket; Navigation Karte/Teilen/Familie/
  Einstellungen (Seitenleiste ab 600 dp); QR-Onboarding (CameraX + ZXing) für Kopplung und Einladung;
  Diagnose-Bericht ohne personenbezogene Daten; Debug-Build `org.familycircle.android.debug`.
- Hardware-Testprotokoll mit 24 Fällen und Tester-Anleitung (`docs/TESTING_ANDROID.md`).

### Geändert
- Repository `PurepixelDEV/familycircle`, Image `ghcr.io/purepixeldev/familycircle`.

### Behoben
- Android „Ausgewogen“: keine Positionen ohne Netzwerk-Ortung; falsche GPS-Sprünge durch wechselnde
  Provider; Bewegung trotz gemeldeter Geschwindigkeit 0 als „ruhend“ erkannt (gefunden im Zwei-Geräte-Test).

## [0.1.0] - unveröffentlicht

Erster vertikaler Prototyp (Phase 0 + erster Teil von Phase 1). Noch nicht als Release getaggt.

### Hinzugefügt
- Go-Backend: Ersteinrichtung mit Setup-Code, Registrierung per Einladung, Web-Login (Cookie) und
  Geräte-Login (rotierende Tokens), Familien, Rollen, Einladungen, Freigaben pro Person, Pause,
  Standort-Upload (idempotent, Offline-Stapel, GPS-Sprungerkennung), Familienkarte, Verlauf (Opt-in),
  Datenexport, Konto- und Standortlöschung, Sitzungs- und Geräteverwaltung, WebSocket-Live-Updates.
- PostgreSQL/PostGIS-Schema mit eingebettetem, transaktionalem Migrator.
- OpenAPI-3.1-Vertrag mit Konsistenztest.
- Web-App/PWA (React, MapLibre): Karte mit Profilbild-Markern und Aktualitätsanzeige, Familienverwaltung,
  Privatsphäre-Einstellungen, Deutsch/Englisch, hell/dunkel.
- Android-App (Kotlin/Compose): siehe `apps/android/README.md`.
- Docker-Image (distroless, Multi-Arch), `compose.yaml` für Portainer, CI/CD-Workflows, Dokumentation.
