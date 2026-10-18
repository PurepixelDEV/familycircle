# Lizenzprüfung

FamilyCircle steht unter **AGPL-3.0-or-later** ([LICENSE](../LICENSE), Originaltext von gnu.org).
Prüfung am 2026-10-18 anhand der tatsächlich ausgelieferten Abhängigkeiten
(`npm ls --omit=dev`, `go list -deps ./cmd/familycircle`, Lizenzdateien der Module).

Ergebnis: Alle Laufzeitabhängigkeiten stehen unter freizügigen, AGPL-kompatiblen Lizenzen.
Es gibt keine Copyleft-Konflikte und keine proprietären Komponenten.

## Server (Go)
| Modul | Lizenz |
|---|---|
| github.com/jackc/pgx/v5, pgpassfile, pgservicefile, puddle/v2 | MIT |
| github.com/coder/websocket | ISC |
| github.com/google/uuid | BSD-3-Clause |
| golang.org/x/crypto, x/image, x/sync, x/sys, x/text | BSD-3-Clause |
| gopkg.in/yaml.v3 (nur Tests) | MIT / Apache-2.0 |

## Web-App (npm, nur Produktionsabhängigkeiten)
| Paket(e) | Lizenz |
|---|---|
| react, react-dom und 10 weitere Hilfspakete | MIT |
| maplibre-gl, @mapbox/vector-tile, pbf | BSD-3-Clause |
| @maplibre/maplibre-gl-style-spec, @maplibre/geojson-vt, kdbush, quickselect, tinyqueue, @mapbox/point-geometry, … | ISC |
| @mapbox/tiny-sdf, @mapbox/unitbezier | BSD-2-Clause |
| @maplibre/mlt | MIT OR Apache-2.0 |
| Schriften Bricolage Grotesque, Atkinson Hyperlegible Next (@fontsource-variable) | SIL OFL 1.1 – Bündelung erlaubt, Schriften bleiben unter OFL |

## Container-Basis
* `gcr.io/distroless/static-debian13` – Debian-Pakete (ca-certificates, tzdata, base-files), freie Lizenzen.
* `postgis/postgis` – PostgreSQL-Lizenz (PostgreSQL), GPL-2.0-or-later (PostGIS). Wird als separater Dienst
  betrieben, nicht mit FamilyCircle gelinkt.

## Kartendaten
OpenStreetMap-Daten (ODbL) über OpenFreeMap; die Attribution wird von MapLibre aus dem Style angezeigt und darf
nicht entfernt werden.

## Android
Siehe Abschnitt in [apps/android/README.md](../apps/android/README.md) – wird nach Abschluss der App-Prüfung ergänzt.
