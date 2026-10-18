# Changelog

Alle nennenswerten Änderungen. Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
Versionierung nach [Semantic Versioning](https://semver.org/lang/de/).

## [Unreleased]

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
