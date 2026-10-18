# Offene technische Risiken

Stand: 2026-10-18. Bewertung: Eintritt × Auswirkung (H/M/N). Erledigte Risiken bleiben mit Vermerk stehen.

| ID | Risiko | Bewertung | Massnahme / Stand |
|---|---|---|---|
| R-01 | **Android-Hintergrundortung auf echten Geräten unzuverlässig** (Herstellerspezifische Akkuoptimierung, Doze, beendete Dienste) | H × H | Foreground Service, sichtbarer Status „letzter Fix vor …“, Offline-Puffer. **Noch kein Hardwaretest.** ⏳ Hardwaretests H-01…H-12, optionaler Dialog zur Ausnahme von der Akkuoptimierung, Hinweise je Hersteller |
| R-02 | Genauigkeit und Akkuverbrauch ohne Google-Fused-Provider unbekannt | M × M | Provider-Auswahl mit Fallback; Messung in H-09 |
| R-03 | **targetSdk 37** macht `ACCESS_LOCAL_NETWORK` für LAN-Server zur Pflicht | H × M | Aktuell targetSdk 36 (implizit erlaubt). Vor Erhöhung: Berechtigung + Erklärung + Laufzeitabfrage; SDK-Plattform 37 lokal installieren. Neueste Compose/Lifecycle/Navigation-Versionen setzen compileSdk 37 voraus |
| R-04 | `postgis/postgis` nur für amd64 | M × M | `FC_DB_IMAGE=imresamu/postgis:17-3.5` für ARM dokumentiert; Community-Image – Pflege beobachten |
| R-05 | Ein einziger Server-Prozess: Live-Hub, Rate-Limits und Sperren im Speicher | N × M | Für die Zielgrösse ausreichend (ADR-0003). Skalierung: PostgreSQL `LISTEN/NOTIFY` |
| R-06 | Klartext-HTTP im LAN-Test erlaubt (Passwort/Standorte im WLAN lesbar) | M × H | Warnungen in Log, Web und App; Produktion nur über HTTPS (INSTALL_PORTAINER Schritt 6) |
| R-07 | Kein 2FA, kein Passwort-Reset per E-Mail, kein Audit-Log | M × M | ⏳ TOTP/WebAuthn, Admin-gestützter Reset, Audit-Log (THREAT_MODEL) |
| R-08 | Kartenkachel-Anbieter (OpenFreeMap) sieht IP und Ausschnitt; Verfügbarkeit nicht garantiert | M × N | Konfigurierbar; Self-Hosting (PMTiles) dokumentieren ⏳ |
| R-09 | GitHub-Workflows nie auf GitHub ausgeführt | M × M | actionlint-geprüft, SHAs verifiziert; erster Lauf nach Repository-Erstellung |
| R-10 | Refresh-Token-Kulanzfenster (2 min) ermöglicht bei gestohlenem Token eine Abmeldung des echten Geräts | N × N | Bewusst akzeptiert (ADR-0004), Diebstahl wird spätestens beim nächsten Refresh erkannt |
| R-11 | F-Droid: AGP 9/compileSdk 36 auf dem Buildserver und reproduzierbare Builds (Baseline-Profile) ungeklärt | M × N | Vor Einreichung prüfen; Abhängigkeiten sind vollständig FLOSS (docs/LICENSES.md) |
| R-18 | Nach einem physikalisch unmöglichen Sprung (z. B. falsche/zwischengespeicherte Position) bestätigt der Server die neue Position erst mit dem nächsten Fix – im Ruhezustand „Ausgewogen“ bis ~10 min | N × M | Alte Position bleibt mit echtem Alter markiert (nie „live“). Verbesserung: App sendet bei grossem Positionswechsel sofort einen zweiten Fix |
| R-19 | „Ausgewogen“ prüft in Ruhe nur alle 5 min auf Bewegung → Abfahrt wird bis zu 5 min spät erkannt | M × N | Bewusster Akku-Kompromiss; auf Hardware messen (H-02/H-09); ggf. Bewegungssensor (Significant Motion) ergänzen |
| R-20 | Nach einem App-Update setzt das Teilen nicht automatisch fort | M × M | App zeigt „pausiert“ korrekt; Verbesserung: `MY_PACKAGE_REPLACED`-Receiver analog zum Neustart |
| R-21 | APK ~70 MB (MapLibre für 4 Prozessorarchitekturen) | N × N | Für Releases ABI-Splits oder Beschränkung auf arm64-v8a/armeabi-v7a prüfen |
| R-12 | Upload-Stapel, den der Server dauerhaft mit 400 ablehnt, blockiert die Android-Warteschlange | N × M | Status zeigt den Fehler; ⏳ Stapel teilen / einzelne Punkte verwerfen |
| R-14 | Bewegungsstatus („steht“/„zu Fuss“) heuristisch; Emulator liefert keine Geschwindigkeit | M × N | Als Heuristik gekennzeichnet; auf Geräten validieren |
| R-15 | Stalking-Restrisiko: Wer kurz das entsperrte Telefon hat, kann eine Freigabe erteilen | M × H | ⏳ Erinnerungen an aktive Freigaben, Benachrichtigung bei neuen Freigaben (THREAT_MODEL) |
| R-16 | Rechtliche Anforderungen für Kinderkonten (DSGVO Art. 8) | M × H | Nicht implementiert; ADR-0006 ist ein Entwurf |
| R-17 | Dokumentation überwiegend Deutsch | M × N | Englische Übersetzung vor öffentlichem Release |

## Erledigt
| ID | Risiko | Erledigt durch |
|---|---|---|
| E-01 | Marker auf der Webkarte verschoben (MapLibre-Klassen überschrieben) | Fix + Browser-Gegenprobe 2026-10-18 |
| E-02 | MapLibre-Worker fehlte im Produktionsbuild | Worker als Asset ausgeliefert |
| E-03 | Aufbewahrungs-Job SQL-Fehler | explizite Casts + `TestRetentionPurge` |
| E-04 | „Nur Einladung“ umgehbar durch eigene Familien | Familien nur durch Instanz-Admin, Test |
| E-05 | Gezielte Kontosperre / IPv6-Umgehung der Rate-Limits | Drosselung pro (Konto, IP), /64-Schlüssel, Tests |
| E-06 | Keine Positionen im Modus „Ausgewogen“, wenn Netzwerk-Ortung fehlt (fused allein) | GPS zusätzlich zu fused (2026-10-18, Emulator) |
| E-07 | Wechselnde Provider erzeugten falsche GPS-Sprünge (fused lieferte alte Position mit neuem Zeitstempel) | GPS-Vorrang im FixFilter, Regressionstest |
| E-08 | Gemeldete Geschwindigkeit 0 trotz Bewegung → als „ruhend“ eingestuft | Verschiebung schlägt Provider-Geschwindigkeit, Regressionstest |
| E-09 | R-13 Android ohne Live-Verbindung | WebSocket in der App (0.2.0) |
