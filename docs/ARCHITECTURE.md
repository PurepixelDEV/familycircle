# FamilyCircle – Architektur

Stand: 2026-10-18 · Phase 0/1

## 1. Überblick

```
┌──────────────────────┐        HTTPS (REST + WebSocket)        ┌──────────────────────────────────────┐
│ Android-App (Kotlin) │ ─────────────────────────────────────▶ │  familycircle (ein Go-Binary)        │
│  • LocationManager   │   POST /api/v1/locations (Bearer)      │   • REST-API  /api/v1/...            │
│  • Foreground Service│   GET  /api/v1/ws (Live)               │   • WebSocket-Hub (in-process)       │
│  • Room-Warteschlange│                                        │   • ausgelieferte Web-App (embed)    │
└──────────────────────┘                                        │   • Migrationen, Aufbewahrungs-Job   │
┌──────────────────────┐   Cookie-Session + CSRF-Header         │                                      │
│ Web-App / PWA (React)│ ─────────────────────────────────────▶ │                                      │
│  • MapLibre GL JS    │                                        └──────────────┬───────────────────────┘
└──────────┬───────────┘                                                       │ pgx (intern)
           │ Kartenkacheln (konfigurierbar, Standard: OpenFreeMap)    ┌────────▼──────────┐
           └──────────────────────────────────────────────────────▶  │ PostgreSQL+PostGIS│
                                                                      └───────────────────┘
```

Produktiv laufen genau **zwei Container**: `familycircle` und `postgres` (PostGIS).
Redis/Broker sind nicht nötig: Der Live-Hub läuft im Prozess. Solange nur eine API-Instanz läuft
(Zielgrösse: wenige Familien, 6–10 Geräte bis einige hundert Nutzer), ist das korrekt und
ressourcenarm. Für horizontale Skalierung wäre später PostgreSQL `LISTEN/NOTIFY` der erste Schritt
(kein zusätzlicher Container), siehe [ADR-0003](adr/0003-live-updates-websocket-inprocess.md).

## 2. Komponenten

| Komponente | Ort | Technik |
|---|---|---|
| Backend | `services/api` | Go 1.27, `net/http` (Pattern-Routing), pgx v5, coder/websocket, x/crypto/argon2 |
| Migrationen | `services/api/migrations` | nummerierte SQL-Dateien, eingebettet, eigener Migrator mit Advisory-Lock |
| API-Vertrag | `packages/api-contract/openapi.yaml` | OpenAPI 3.1, Quelle der Wahrheit; TS-Typen werden daraus generiert |
| Web-App | `apps/web` | React 19, TypeScript, Vite, MapLibre GL JS, eigenes i18n (DE/EN), PWA |
| Android | `apps/android` | Kotlin, Jetpack Compose, LocationManager, Room, WorkManager, OkHttp |
| Deployment | `deploy/` | Multi-Stage-Dockerfile, `compose.yaml` (Produktion), `compose.dev.yaml` |

Warum die Migrationen nicht unter `database/migrations` liegen: `go:embed` kann nur Dateien
innerhalb des Go-Moduls einbetten. Das Binary soll Migrationen ohne zusätzliche Dateien mitbringen
(Portainer: keine Konfigurationsdateien). `database/README.md` verweist auf den Ort.

## 3. Backend-Schichten

```
cmd/familycircle         Einstieg: serve | migrate | healthcheck
internal/config          Umgebungsvariablen → typisierte Konfiguration (fail fast)
internal/db              Pool, Migrator
internal/store           alle SQL-Abfragen (einzige Stelle mit SQL)
internal/auth            Argon2id, Token-Erzeugung, Hashing
internal/geo             Distanz, GPS-Sprungerkennung, Aktualitätsstufen
internal/live            WebSocket-Hub
internal/ratelimit       In-Memory Token-Bucket je Schlüssel
internal/api             HTTP-Handler, Middleware (Auth, CSRF, Rate-Limit, Security-Header)
internal/webui           eingebettete Web-App inkl. SPA-Fallback
internal/jobs            Aufbewahrung/Bereinigung
```

**Autorisierungsregel (zentral):** Ein Betrachter V darf den Standort von Besitzer O genau dann
lesen, wenn

1. O eine Freigabe an V erteilt hat (`location_shares(owner=O, viewer=V)`), **und**
2. O die Freigabe nicht global pausiert hat, **und**
3. O und V aktuell mindestens eine gemeinsame Familie haben.

Diese Bedingung steht genau einmal als SQL-Fragment in `internal/store/visibility.go` und wird
von Karte, Live-Hub und Einzelabfragen verwendet. Familienadministration (Rolle `admin`) spielt in
dieser Bedingung **keine** Rolle – Admins können Freigaben weder sehen noch erzeugen.

## 4. Authentifizierung

* Passwörter: Argon2id (m=64 MiB, t=3, p=2), PHC-String, Re-Hash bei Parameteränderung.
* Sitzungen sind **serverseitig**: Tokens sind 256-Bit-Zufallswerte, in der DB nur als SHA-256.
  Jede Anfrage prüft die Sitzung in der DB → Widerruf wirkt sofort.
* Web: `HttpOnly`, `SameSite=Lax` Cookie (`Secure` sobald `FC_PUBLIC_URL` https ist),
  CSRF-Schutz durch Pflicht-Header `X-FC-CSRF` + Origin-Prüfung bei schreibenden Anfragen.
* Android: Access-Token (1 h) + rotierendes Refresh-Token (90 Tage). Wiederverwendung eines
  bereits rotierten Refresh-Tokens widerruft die gesamte Sitzung (Diebstahlerkennung).
  Tokens liegen auf dem Gerät AES-GCM-verschlüsselt mit Schlüssel im Android Keystore.
* Ersteinrichtung: Solange kein Benutzer existiert, verlangt der Assistent einen Setup-Code, der
  beim Start ins Container-Log geschrieben wird (oder `FC_SETUP_TOKEN`). Verhindert, dass ein
  Fremder im Netz die frische Instanz übernimmt.
* Registrierung standardmässig nur mit Einladungscode.

Details: [ADR-0004](adr/0004-auth-sessions.md).

## 5. Standortdaten-Fluss

1. Android erfasst Positionen (LocationManager, GPS + Netzwerk), filtert offensichtlich unbrauchbare
   Fixes, vergibt pro Punkt eine UUID (`id`) und schreibt ihn **zuerst** in Room.
2. Ein Uploader sendet Stapel (≤ 200 Punkte) an `POST /api/v1/locations`. Die Identität ergibt sich
   ausschliesslich aus dem Token – der Body enthält keine Benutzer-ID.
3. Server validiert, erkennt GPS-Sprünge (unplausible Geschwindigkeit gegenüber letzter Position,
   Bestätigung durch Folgepunkt), schreibt idempotent (`UNIQUE(device_id, client_event_id)` bzw.
   „nur neuere Zeitstempel ersetzen den letzten Standort“).
4. Verlauf (`location_history`) wird **nur** bei aktivem Opt-in gespeichert; sonst nur
   `locations_latest`.
5. Der Hub ermittelt per zentraler Sichtbarkeitsregel die berechtigten Betrachter und pusht.
6. Bestätigte Punkte werden auf dem Gerät gelöscht; bei Fehlern bleibt die Warteschlange erhalten
   (WorkManager mit Netzwerk-Constraint als Wiederholung).

**Aktualität** wird serverseitig aus `recorded_at` abgeleitet (`live` < 2 min, `recent` < 15 min,
`stale` danach) und in jeder Antwort mitgeliefert; Clients berechnen zusätzlich lokal fortlaufend.
Eine alte Position wird nie als live dargestellt.

## 6. Live-Updates und Widerruf

WebSocket `/api/v1/ws` (Cookie oder Bearer). Der Hub hält Verbindungen pro Benutzer/Sitzung.
Bei jeder neuen Position wird die Empfängerliste **neu aus der DB** berechnet – es gibt keine
zwischengespeicherten Berechtigungen. Bei Widerruf einer Freigabe, Pause, Familienaustritt oder
Entfernung eines Mitglieds sendet der Server sofort `location_hidden` an die betroffenen Betrachter.
Bei Sitzungswiderruf/Abmeldung/Kontolöschung werden die Verbindungen der Sitzung geschlossen.

## 7. Karten

MapLibre GL JS mit konfigurierbarer Style-URL (`FC_MAP_STYLE_URL`). Standard ist
OpenFreeMap (`https://tiles.openfreemap.org/styles/liberty`, OSM-Daten, ohne API-Schlüssel).
Datenschutzhinweis: Der Kachelanbieter sieht, welchen Kartenausschnitt der Browser lädt (nicht die
Positionen der Familienmitglieder). Für maximale Privatsphäre kann ein selbst gehosteter Style
(z. B. Protomaps/PMTiles) konfiguriert werden. Die CSP erlaubt automatisch den Ursprung der
Style-URL plus `FC_MAP_EXTRA_ORIGINS`.

## 8. Konfiguration (Auszug)

| Variable | Standard | Zweck |
|---|---|---|
| `FC_DATABASE_URL` | – (Pflicht) | PostgreSQL-DSN |
| `FC_HTTP_ADDR` | `:8080` | Listen-Adresse im Container |
| `FC_PUBLIC_URL` | – | Externe URL; `https://…` aktiviert Secure-Cookies und HSTS |
| `FC_TRUSTED_PROXIES` | – | CIDRs, deren `X-Forwarded-For` vertraut wird (z. B. NPM) |
| `FC_MAP_STYLE_URL` | OpenFreeMap Liberty | MapLibre-Style |
| `FC_SETUP_TOKEN` | zufällig, im Log | Setup-Code für den Assistenten |
| `FC_LOG_LEVEL` | `info` | `debug`/`info`/`warn`/`error` |

Vollständig: `.env.example`.

## 9. Skalierungsannahmen

* Ein Upload pro Gerät alle 15 s–15 min → 10 Geräte ≈ < 1 Anfrage/s. Ein einzelner Go-Prozess
  mit 64 MB RAM und PostgreSQL mit Standardwerten reichen weit.
* `locations_latest` ist eine Zeile pro Benutzer; die Karte ist eine einzelne indizierte Abfrage.
* `location_history` ist nach `(user_id, recorded_at)` indiziert; Bereinigung täglich/stündlich.
* Mandantentrennung erfolgt auf Familien-/Benutzerebene in jeder Abfrage; mehrere Familien pro
  Instanz sind von Anfang an vorgesehen.
