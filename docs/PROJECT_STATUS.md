# Projektstatus

Stand: **2026-10-18** · Version **0.1.0 (unveröffentlicht)** · Phase 0 abgeschlossen, Phase 1 weitgehend umgesetzt.

Legende: ✅ automatisiert getestet · 🧪 manuell / Ende-zu-Ende getestet (Emulator oder Browser) ·
🟡 implementiert, nicht getestet · ⏳ offen · 📱 braucht Hardwaretest

## Qualitätskriterien des ersten Prototyps

| # | Kriterium | Status | Nachweis |
|---|---|---|---|
| 1 | Zwei Konten geben Standorte gegenseitig frei | ✅ 🧪 | API-Tests `TestShareGrantPauseRevoke` u. a.; Emulator: Registrierung + Freigabe in der App |
| 2 | Zwei **Android-Geräte** übermitteln GPS-Daten | 🧪 📱 | **Ein Emulator** (Android 16, API 36) hat echte Positionen über die App übertragen (Werte in der DB geprüft). Zweites Gerät per API simuliert. **Kein physisches Gerät getestet.** |
| 3 | Webkarte zeigt zuletzt empfangene echte Positionen | 🧪 | Browser-Test: vom Emulator gesendete Position erscheint auf der Karte (Marker-Position gegengeprüft) |
| 4 | Neue Positionen erscheinen ohne Neuladen | ✅ 🧪 | `TestLiveUpdatesRespectRevocation`; Browser: Marker und Liste aktualisiert bei 1 Navigation |
| 5 | Offline aufgezeichnete Positionen werden nachgeliefert | ✅ 🧪 | Emulator: WLAN+Daten aus, 5 Punkte in Room-Warteschlange, nach Reconnect alle 5 in 4 s mit Original-Zeitstempeln in der DB |
| 6 | Deaktivierte Freigabe → kein Zugriff mehr | ✅ 🧪 | API-Tests (REST + WebSocket); Emulator: Widerruf in der App → API sofort leer, offene Webkarte entfernt Marker live |
| 7 | Docker Compose gestartet und API getestet | ✅ 🧪 | Produktions-`compose.yaml` mit lokal gebautem Image: 15/15 Smoke-Tests (`scripts/smoke-test.sh`), auch in CI |
| 8 | Alle automatisierten Tests grün | ✅ | Go 61 Testfunktionen (inkl. Race-Detector, echte PostGIS), Web 19, Android 86 – Details unten |
| 9 | Ungetestete Hardwarefunktionen markiert | ✅ | Diese Datei + [HARDWARE_TESTS.md](HARDWARE_TESTS.md) |

## Automatisierte Tests (letzter lokaler Lauf 2026-10-18)
| Bereich | Ergebnis | Inhalt |
|---|---|---|
| Go-Backend | **61/61 grün**, `go test -race` | Unit: Argon2id, Tokens/Codes, Geo/Sprungerkennung/Aktualität, Rate-Limiter, Hub, Config. Integration gegen PostGIS 17-3.5: Migrator (Rollback, Checksumme, Downgrade), 35 API-Tests inkl. Negativtests (IDOR, Admin-Umgehung, Einladungsmissbrauch inkl. Nebenläufigkeit, Widerruf live/REST, veraltete Daten, fremde Identität, CSRF, Token-Diebstahlerkennung, Drosselung pro Adresse, IPv6-/64), Vertragstest Routen ↔ OpenAPI |
| Lint/Security Go | 0 Befunde | `gofmt`, `go vet`, golangci-lint 2.15 (inkl. gosec), govulncheck 1.9: keine erreichbare Schwachstelle |
| Web | 19/19 grün | Aktualitätslogik, Live-Ereignis-Reducer, API-Client (CSRF, Fehler, Abmeldung), MemberRow, i18n-Vollständigkeit; ESLint + `tsc` sauber; API-Typen aktuell |
| Android | 86/86 grün | TrackingPolicy, MotionClassifier, FixFilter, Uploader (Batches, Idempotenz, Fehler), serialisierte Token-Erneuerung, ServerUrl, JSON-Vertrag, ApiClient gegen MockWebServer; Lint: 0 Fehler |
| Deployment | 15/15 | Smoke-Test gegen Produktions-Stack; Backup → Löschen → Restore → Login getestet |

## Ende-zu-Ende-Tests mit Emulator und Browser (2026-10-18)
Umgebung: Produktions-`compose.yaml` mit lokal gebautem Image auf Windows/Docker Desktop, Android-Emulator
„Medium Phone“ Android 16 (API 36.1, **Google-Play-Image** – die App nutzt keine Google-Dienste, das Image enthält sie
aber; ein reines AOSP-Image wurde nicht getestet), Server über `http://10.0.2.2:9092`, Chromium per Playwright.

Durchgeführt und bestanden:
1. Onboarding: Serveradresse, Verbindungsprüfung, HTTP-Warndialog, Registrierung per Einladungscode in der App.
2. Berechtigungs-Erklärung, System-Dialoge (genauer Standort „Bei Nutzung der App“, Benachrichtigungen).
3. Foreground Service Typ `location` aktiv (`dumpsys`: `isForeground=true, types=0x8`), Benachrichtigung mit Pause-Aktion.
4. GPS-Position (`geo fix`) → Upload → exakt in `locations_latest` (≈3 s Latenz).
5. Freigabe in der App → Dana erscheint live auf Annas Webkarte; Bewegung über 4 Punkte nachverfolgt.
6. Offline-Puffer und Nachlieferung (siehe Kriterium 5), Verlauf-Opt-in aus der App heraus.
7. Widerruf in der App (siehe Kriterium 6).

Dabei gefundene und behobene Fehler (alle mit Re-Test):
* MapLibre-Worker wurde im Produktionsbuild nicht gefunden → Karte leer.
* Marker-Klassen überschrieben MapLibres Positionierung → **alle Marker ausser dem ersten waren horizontal verschoben**.
* Kartenausschnitt verdeckte Marker unter Bottom-Sheet bzw. Zoom-Reglern; Rail-Beschriftung abgeschnitten;
  horizontaler Überlauf der Privatsphäre-Seite auf dem Handy.
* Aufbewahrungs-Job scheiterte an SQL-Parametertypen (nur im echten Stack sichtbar).

## Was nicht getestet ist (offen)
* 📱 **Keinerlei Test auf physischen Android-Geräten** (Akkuverbrauch, Herstellerspezifische Energiesparmodi,
  echte GPS-Qualität, Neustart/`BOOT_COMPLETED`, Doze über Stunden, Benachrichtigungs-Pause-Aktion per Fingertipp).
* 📱 Zwei gleichzeitig laufende Android-Clients (nur einer im Emulator, zweiter per API).
* HTTPS hinter Nginx Proxy Manager (Konfiguration dokumentiert, nicht aufgebaut).
* Multi-Arch-Image (arm64) und Portainer-UI selbst (Compose-Datei mit Docker Compose getestet, nicht in Portainer).
* GitHub-Workflows: mit actionlint geprüft, aber **noch nie auf GitHub gelaufen** (kein Repository vorhanden).
* Release-APK zur Laufzeit; Signatur (kein Schlüssel vorhanden).
* PWA-Installation und Offline-Shell; Barrierefreiheit mit Screenreader (TalkBack/NVDA).

## Umfang nach Phasen
| Phase | Stand |
|---|---|
| 0 Analyse & Architektur | ✅ Architektur, Datenmodell, OpenAPI, ADRs 0001–0009, Threat Model, Android-Recherche |
| 1 Vertikaler Prototyp | ✅ Backend, DB, Login, Familien, Einladungen, Web-App mit Karte, Android-App (Tracking, Offline, Freigaben), Live-Updates, Docker. ⏳ QR-Code-Onboarding, Karte in der Android-App |
| 2 Zuverlässige Ortung | 🟡 Foreground Service, Offline-Speicher, Modi, Geräteverwaltung umgesetzt; ⏳ Hardwaretests, Akku-Optimierung, Batterieoptimierungs-Ausnahme |
| 3 Erweiterte Funktionen | ⏳ Geofences, Benachrichtigungen (UnifiedPush/ntfy prüfen), Timeline-Ansicht, Check-ins/SOS, Kinderkonten (ADR-0006 Entwurf) |
| 4 Veröffentlichung | 🟡 CI/CD, Release-Workflow, Doku, Lizenzprüfung; ⏳ GitHub-Repo, erster Release, F-Droid |

## Entscheidungen, die der Projektinhaber treffen muss
1. **GitHub-Owner/Repository-Name** – bestimmt `ghcr.io/<owner>/familycircle` (aktuell Platzhalter `OWNER`).
2. **Android applicationId** (aktuell `org.familycircle.android`) – nach dem ersten Release unveränderbar.
3. **Kontaktadresse** für Code of Conduct / Sicherheitsmeldungen.
4. compileSdk/targetSdk 37 (erfordert SDK-Plattform 37 und die `ACCESS_LOCAL_NETWORK`-Berechtigung, RISKS R-03).

## Nächste Schritte (Vorschlag)
1. Hardwaretests H-01…H-12 mit zwei Geräten (Testprotokoll vorhanden).
2. GitHub-Repository anlegen, CI laufen lassen, ersten Release `v0.1.0` erzeugen.
3. Karte in der Android-App (MapLibre Native), QR-Onboarding, WebSocket-Live in der App.
4. Phase 3: serverseitige Geofence-Ereignisse mit Entprellung und In-App-Benachrichtigungen.
