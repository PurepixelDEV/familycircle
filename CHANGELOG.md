# Changelog

Alle nennenswerten Änderungen. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
Versionierung nach [Semantic Versioning](https://semver.org/lang/de/).

## [Unreleased]

## [0.2.0] - unveröffentlicht

Phase 1.5: benutzbare Familien-App, Hardware-Testbereitschaft, vereinfachtes Deployment (in Arbeit).

### Hinzugefügt
- Kopplungscodes: Handy per QR-Code mit bestehendem Konto verbinden (`POST /me/pairing-codes`,
  `grant_type=pairing_code`, 10 min, einmalig) – Migration `0002_pairing_codes`.
- Web: QR-Codes für „Handy verbinden“ und Einladungen, Hinweisseite `/pair`.
- `FC_DB_PASSWORD_FILE` (Passwort als Datei/Secret); Log-Hinweis mit der Adresse eines nicht vertrauten Proxys.
- Testskripte: Datenbank-Upgrade (`scripts/upgrade-test.sh`), HTTPS über Reverse Proxy
  (`scripts/https-proxy-test.sh`), Entwicklungs-Testdaten (`scripts/seed-dev-data.py`),
  Image-Archiv für Portainer-Import (`scripts/build-image-archive.sh`).

### Geändert
- Repository `PurepixelDEV/familycircle`, Image `ghcr.io/purepixeldev/familycircle`.

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
