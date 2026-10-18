# Projektstatus

Stand: **2026-10-18** · Version **0.2.0 (unveröffentlicht)** · Phase 1.5 (benutzbare Familien-App, Hardware-Testbereitschaft)

Legende: ✅ automatisiert getestet · 🧪 Ende-zu-Ende getestet (Emulator/Browser/Docker) · 📱 **Hardwaretest offen** · ⏳ offen

## Ziel dieser Phase: zwei echte Android-Handys sehen sich gegenseitig
* 🧪 **Im Emulator erreicht:** zwei gleichzeitig laufende Android-16-Emulatoren, gekoppelt als Anna und Bob, teilen
  im Modus „Ausgewogen“ und sehen sich gegenseitig auf der Karte in der App; Bewegungen erscheinen ohne Interaktion.
* 📱 **Auf echten Handys noch nicht geprüft.** Debug-APK und Testprotokoll liegen bereit
  ([HARDWARE_TESTS.md](HARDWARE_TESTS.md), [TESTING_ANDROID.md](TESTING_ANDROID.md)).

## Lieferumfang 0.2.0
| Ergebnis | Stand | Nachweis |
|---|---|---|
| Android-Debug-APK | ✅ | `dist/familycircle-0.2.0-debug.apk` (70 MB, SHA-256 in `.sha256`), `org.familycircle.android.debug`, Android 8–16 |
| Familienkarte in der App | ✅ 🧪 | MapLibre Native, Profilbild-Marker, Genauigkeitskreise, eigener Standort, Akkustand (falls freigegeben), veraltet = grau/gestrichelt/Alter, WebSocket-Live-Updates, Bottom-Sheet bzw. Seitenleiste ab 600 dp |
| QR-Verbindungsassistent | ✅ 🧪 | Kopplungscode (10 min, einmalig, API + Migration 0002), Einladungs-QR, Scanner mit CameraX + ZXing; Kopplung im Emulator per Link getestet, **Kamera-Scan nur mit generierten Bildern unit-getestet** |
| Portainer-Deployment | ✅ 🧪 | Zwei Container, `compose.yaml` + `.env.example`, Image-Archiv-Import (ohne Build) getestet: Smoke 15/15 |
| GitHub Actions | ✅ | CI, CodeQL, Release (GHCR `ghcr.io/purepixeldev/familycircle`, APK); actionlint sauber; **noch nie auf GitHub gelaufen** |
| Dokumentation | ✅ | README, INSTALL_PORTAINER, BACKUP_RESTORE, SECURITY, RELEASE, QR_FORMAT, HARDWARE_TESTS, TESTING_ANDROID, RISKS |

## Testergebnisse (letzter Lauf 2026-10-18)
| Bereich | Ergebnis |
|---|---|
| Go-Backend | **65/65** Tests grün mit `-race` gegen PostGIS 17-3.5; golangci-lint 0, govulncheck ohne erreichbare Schwachstelle |
| Web | **22/22** Tests, ESLint/TypeScript sauber, API-Typen = Vertrag |
| Android | **176/176** JVM-Unit-Tests, Lint 0 Fehler; Debug- und R8-Release-Build |
| Docker-Smoke-Test | **15/15** gegen Produktions-`compose.yaml` mit importiertem Image-Archiv |
| HTTPS über Reverse Proxy | ✅ nginx wie NPM: TLS, Secure-Cookie, HSTS, `wss` durch Proxy, fremder Origin abgelehnt, gefälschtes `X-Forwarded-For` wirkungslos (Gegenprobe mit zu breitem `FC_TRUSTED_PROXIES` schlägt erwartungsgemäss fehl) |
| Datenbank-Upgrade | ✅ 0.1.0 → 0.2.0 auf gleichem Volume: Daten identisch, Migration 0002 angewandt, Login ok, Downgrade verweigert |
| Secret-Scan | ✅ gitleaks: 7 Commits + Arbeitsverzeichnis ohne Funde |

## Schwerpunktprüfungen (Aufgabe D)
| Thema | Automatisiert | Ende-zu-Ende |
|---|---|---|
| Standortfreigaben / Zugriff ohne Berechtigung | ✅ IDOR, Admin-Umgehung, fremde Familie, Pairing-/Einladungs-Trennung | 🧪 Smoke-Test, zwei Emulatoren |
| Widerruf bei aktiver WebSocket-Verbindung | ✅ `TestLiveUpdatesRespectRevocation` | 🧪 zwei Emulatoren: Marker weg nach 3–10 s (Software-Rendering); 📱 H-05 |
| Mehrere Android-Geräte | – | 🧪 zwei Emulatoren gleichzeitig; 📱 H-02 |
| Token-Rotation | ✅ Server + App (MockWebServer) | 🧪 abgelaufenes Token → automatische Erneuerung, Uploads + Live laufen weiter; 📱 H-22 |
| Offline-Synchronisierung | ✅ | 🧪 6 Punkte offline gepuffert, nach Reconnect mit Original-Zeitstempel geliefert; 📱 H-04 |
| Datenbank-Upgrades | ✅ Migrator-Tests | 🧪 `scripts/upgrade-test.sh` |
| HTTPS über Reverse Proxy | – | 🧪 `scripts/https-proxy-test.sh` |

## Im Zwei-Geräte-Test gefundene und behobene Fehler
1. **„Ausgewogen“ lieferte keine Positionen**, wenn die Netzwerk-Ortung fehlte (nur `fused` angefragt) → GPS zusätzlich.
2. **Falsche GPS-Sprünge** durch wechselnde Provider (`fused` lieferte alte Position mit neuem Zeitstempel) → GPS-Vorrang.
3. **Bewegung als „ruhend“ erkannt**, weil die gemeldete Geschwindigkeit 0 war → tatsächliche Verschiebung zählt.

Jeweils mit Regressionstest; erneuter Emulator-Lauf bestätigt: Updates alle ~2 min in Bewegung, keine Fehlsprünge.

## Offen – physische Hardwaretests 📱
Alle 24 Fälle in [HARDWARE_TESTS.md](HARDWARE_TESTS.md) sind offen, insbesondere: zwei echte Handys gegenseitig
sichtbar (H-02), Bildschirm aus / App weggewischt (H-03), Neustart (H-07), Akkuverbrauch (H-09), echter
QR-Kamera-Scan (H-13/H-14), HTTPS mit echtem Zertifikat (H-12), Hersteller-Energiesparmodi.

## Weitere offene Punkte
* GitHub: erster Push (wartet auf Freigabe/Authentifizierung), erster CI-Lauf, erster Release `v0.2.0`.
* Bekannte Einschränkungen: R-18 bis R-21 in [RISKS.md](RISKS.md) (Sprungbestätigung, 5-min-Ruheintervall,
  kein Auto-Resume nach App-Update, APK-Grösse).
* Phase 3 (Geofences, Benachrichtigungen, Check-ins/SOS, Kinderkonten) **bewusst nicht begonnen**.

## Entscheidungen
| Thema | Stand |
|---|---|
| Repository / Image | entschieden: `PurepixelDEV/familycircle`, `ghcr.io/purepixeldev/familycircle` |
| Android applicationId | **unverändert `org.familycircle.android`** – Änderung nur mit Freigabe (`ch.putepixel.familycircle` vorgeschlagen, nicht umgesetzt) |
| Kontaktadresse | `hello@putepixel.ch` festgelegt, **öffentliche Verwendung nicht freigegeben** (nicht in Doku eingetragen) |
| Git-Autor | neue Commits `PutePixel <hello@putepixel.ch>`; alte Commits unverändert |
| targetSdk 37 | offen (R-03) |
