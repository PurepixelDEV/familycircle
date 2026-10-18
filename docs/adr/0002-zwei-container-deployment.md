# ADR-0002: Zwei-Container-Deployment, Web-App im Go-Binary

Status: akzeptiert · 2026-10-18

## Kontext
Installation über Portainer: Compose-Datei einfügen, Variablen setzen, Deploy – ohne Git-Checkout, ohne Build,
ohne zusätzliche Konfigurationsdateien (keine Caddyfile, keine nginx.conf).

## Entscheidung
* Container 1: `familycircle` – Go-Binary mit eingebetteter Web-App (`go:embed`), distroless `static-debian13:nonroot`,
  Healthcheck über `familycircle healthcheck` (prüft `/readyz` inkl. Datenbank).
* Container 2: `postgis/postgis:17-3.5`, ohne veröffentlichten Port.
* TLS terminiert ein vorhandener Reverse-Proxy (Nginx Proxy Manager); der Container spricht HTTP.
* Konfiguration ausschliesslich über Umgebungsvariablen; `FC_DB_PASSWORD` wird vom Server selbst zu einer
  korrekt URL-kodierten DSN zusammengesetzt (Sonderzeichen im Passwort sind unkritisch – getestet).
* Images nur mit festen Versionstags (`0.1.0`, `0.1`), kein `latest`.
* Migrationen laufen beim Start automatisch, transaktional pro Datei; bei Fehler startet der Server nicht (Restart-Loop
  sichtbar in Portainer), die Datenbank bleibt auf dem letzten konsistenten Stand.

## Konsequenzen
* Image ~26 MB, kein Shell im Container (geringere Angriffsfläche, aber `docker exec` ins App-Image ist nicht möglich;
  Diagnose über Logs und `/readyz`).
* `read_only: true`, `cap_drop: [ALL]`, `no-new-privileges` im Compose.
* Multi-Arch (amd64, arm64) per Cross-Compile im Build. `postgis/postgis:17-3.5` ist nur für amd64 verfügbar
  (per `docker manifest inspect` geprüft); auf ARM-Hosts wird über `FC_DB_IMAGE` das Multi-Arch-Image
  `imresamu/postgis:17-3.5` verwendet (siehe RISKS R-04).
