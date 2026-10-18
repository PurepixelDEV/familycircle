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
Prüfung am 2026-10-18 anhand von `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`
(135 aufgelöste Module inkl. 3 BOMs) und der `<licenses>`-Angaben in den POMs auf Google Maven bzw.
Maven Central (bei `listenablefuture` geerbt von `guava-parent`).

| Lizenz | Artefaktgruppen | Anzahl |
|---|---|---|
| Apache-2.0 | `androidx.*` (activity, annotation, arch.core, autofill, collection, compose.*, concurrent, core, customview, documentfile, dynamicanimation, emoji2, graphics, interpolator, legacy, lifecycle, loader, localbroadcastmanager, navigation, navigationevent, print, profileinstaller, room, savedstate, sqlite, startup, tracing, transition, versionedparcelable, window, work) | 118 |
| Apache-2.0 | `org.jetbrains.kotlin`, `org.jetbrains.kotlinx` (coroutines, serialization), `org.jetbrains:annotations` | 11 |
| Apache-2.0 | `com.squareup.okhttp3`, `com.squareup.okio` | 4 |
| Apache-2.0 | `com.google.guava:listenablefuture`, `org.jspecify` | 2 |

Ergebnis: Alle 135 Module stehen unter Apache-2.0 (mit AGPL-3.0-or-later kompatibel); keine Google Play Services,
kein Firebase, keine proprietären oder lokalen JAR/AAR-Abhängigkeiten – F-Droid-tauglich.
