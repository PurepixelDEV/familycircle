# ADR-0001: Technologiestack und Monorepo

Status: akzeptiert · 2026-10-18

## Kontext
Selbstgehostete Familien-Ortung, kleine Betreiber (Heimserver, NAS), Open Source, möglichst wenig
Infrastruktur und Abhängigkeiten, langfristig wartbar.

## Entscheidung
* **Monorepo**: `services/api` (Go), `apps/web` (React), `apps/android` (Kotlin), `packages/api-contract` (OpenAPI).
* **Backend Go 1.27** mit Standardbibliothek-Routing (`net/http` Patterns seit Go 1.22) statt Framework.
  Abhängigkeiten bewusst klein: pgx v5, coder/websocket, google/uuid, x/crypto (Argon2id), x/image (Avatar-Skalierung).
* **PostgreSQL 17 + PostGIS 3.5** (`postgis/postgis:17-3.5`). PostGIS speichert Positionen als `geography`
  und ist die Grundlage für Geofences (Phase 3, `ST_DWithin`).
* **Eigener Migrator** (≈150 Zeilen): eingebettete SQL-Dateien, Advisory-Lock, Checksummen, Downgrade-Schutz.
  Eine Bibliothek wie golang-migrate hätte ähnlich viel Integrationscode gebraucht und mehr Abhängigkeiten.
* **Web: React 19 + TypeScript 5.9 + Vite 8 + MapLibre GL JS 6**. TypeScript 7 (nativer Compiler) wird von
  typescript-eslint und openapi-typescript noch nicht unterstützt (Peer-Dependency `<6.1` bzw. `^5`), daher 5.9.
  Kein Router, kein State-Framework, kein i18n-Framework: Die App hat fünf Ansichten.
* **API-first**: `openapi.yaml` ist die Quelle der Wahrheit. Ein Go-Test prüft, dass Routen und Spezifikation
  identisch sind; die TypeScript-Typen werden daraus generiert (CI prüft Abweichungen).

## Abweichung von der Vorgabe
Migrationen liegen in `services/api/migrations` statt `database/migrations`, weil `go:embed` keine Dateien
ausserhalb des Moduls einbetten kann und das Binary ohne Zusatzdateien laufen soll. `database/README.md` verweist darauf.

## Konsequenzen
* Ein statisch gelinktes Binary (~20 MB) + Datenbank; kein Node.js zur Laufzeit.
* Redis oder Message-Broker sind nicht nötig (siehe ADR-0003).
