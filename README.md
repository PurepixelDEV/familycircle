# FamilyCircle

**Private Standortfreigabe für Familien – selbst gehostet, quelloffen, ohne Tracker.**
*Self-hosted, privacy-first family location sharing – an open-source alternative to Life360 (AGPL-3.0-or-later).*

> **Status: 0.1.0 – Prototyp (nicht produktionsreif).** Was getestet ist und was nicht, steht ehrlich in
> [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md). Offene Risiken: [docs/RISKS.md](docs/RISKS.md).

## Was FamilyCircle kann
* **Android-App** als eigenständiger GPS-Client (keine Zusatz-App wie OwnTracks nötig, keine Google-Dienste):
  Standort im Hintergrund mit sichtbarem Status, vier Tracking-Modi, Offline-Warteschlange, Pause per Fingertipp.
* **Web-App / PWA**: Familienkarte mit Profilbildern, Live-Aktualisierung, deutlich markierten veralteten Positionen,
  Familien und Einladungen verwalten, Privatsphäre-Einstellungen, Geräte und Sitzungen, Datenexport.
* **Privatsphäre zuerst**: Jede Person entscheidet selbst, mit wem sie ihren Standort teilt. Familien-Admins sehen
  nichts ohne Freigabe. Widerruf wirkt sofort, auch auf offene Live-Verbindungen. Verlauf nur nach Opt-in.
  Keine Telemetrie, keine Werbung, keine Tracker.
* **Einfacher Betrieb**: zwei Container (FamilyCircle + PostgreSQL/PostGIS), Installation per Portainer-Stack,
  automatische Datenbankmigrationen, Backups mit Standardwerkzeugen.

Repository: https://github.com/PurepixelDEV/familycircle · Image: `ghcr.io/purepixeldev/familycircle` (ab dem ersten Release)

### Android-App prüfen
Releases: https://github.com/PurepixelDEV/familycircle/releases – Prüfsumme mit `sha256sum -c SHA256SUMS.txt`.
Die offizielle APK (`org.familycircle.android`) ist mit diesem Zertifikat signiert
(`apksigner verify --print-certs familycircle-<version>.apk`):

```
CN=FamilyCircle, O=PurePixel, C=CH
SHA-256: 6b62aaf9dd78df8cb5329edf9027afc6df73854aab28ea9822a5a0392014845c
```
Eine APK mit anderem Fingerabdruck stammt nicht aus diesem Projekt.

## Installation
→ **[INSTALL_PORTAINER.md](INSTALL_PORTAINER.md)** (Portainer-Stack, HTTPS mit Nginx Proxy Manager)
→ [BACKUP_RESTORE.md](BACKUP_RESTORE.md) · [SECURITY.md](SECURITY.md) · [RELEASE.md](RELEASE.md)

Kurzfassung mit Docker Compose:
```sh
cp .env.example .env    # FC_IMAGE, FC_DB_PASSWORD, FC_PUBLIC_URL setzen
docker compose up -d
docker compose logs familycircle | grep setup_code   # Einrichtungscode für den Assistenten
```
Dann `http://<server>:9092` öffnen.

## Aufbau
```
apps/web/              React + TypeScript + Vite + MapLibre (PWA)
apps/android/          Kotlin + Jetpack Compose, LocationManager, Room, WorkManager
services/api/          Go-Server: REST + WebSocket, liefert die Web-App aus
packages/api-contract/ OpenAPI 3.1 – verbindlicher API-Vertrag
deploy/docker/         Multi-Stage-Dockerfile
docs/                  Architektur, ADRs, Threat Model, Status, Risiken
scripts/               Entwickler- und Testskripte
```
Architektur: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · Entscheidungen: [docs/adr/](docs/adr/) ·
Threat Model: [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md)

## Entwicklung
Voraussetzungen: Docker, Node.js 24, für Android JDK 21 + Android SDK (z. B. aus Android Studio).
Go muss nicht installiert sein – `scripts/go.sh` nutzt das offizielle `golang:1.27`-Image.

```sh
# Datenbank für Entwicklung und Tests (localhost:5433)
docker compose -f compose.dev.yaml up -d db

# Backend: Tests inkl. Datenbank-Integrationstests
FC_TEST_DATABASE_URL="postgres://familycircle:devpassword@db:5432/postgres?sslmode=disable" \
  FC_REQUIRE_DB=1 scripts/go.sh test -race ./...

# Komplettes System lokal aus dem Quellcode (http://localhost:9092, Setup-Code: DEV-SETUP)
docker compose -f compose.dev.yaml up -d --build

# Web-App mit Hot-Reload (http://localhost:5173, API-Proxy auf :9092)
cd apps/web && npm ci && npm run dev

# Ende-zu-Ende-Smoke-Test gegen eine frische Instanz
FC_URL=http://localhost:9092 FC_SETUP_CODE=DEV-SETUP scripts/smoke-test.sh

# Android
cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Lokales Produktions-Image bauen (statt GHCR):
```sh
docker build -f deploy/docker/Dockerfile --build-arg VERSION=0.1.0 -t familycircle:0.1.0 .
# dann FC_IMAGE=familycircle:0.1.0 in .env
```

## Mitmachen
[CONTRIBUTING.md](CONTRIBUTING.md) · [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) · Sicherheitslücken bitte vertraulich
melden: [SECURITY.md](SECURITY.md).

## Lizenz
[AGPL-3.0-or-later](LICENSE). Drittanbieter-Lizenzen: [docs/LICENSES.md](docs/LICENSES.md).
Kartendaten © OpenStreetMap-Mitwirkende (über [OpenFreeMap](https://openfreemap.org), konfigurierbar).
