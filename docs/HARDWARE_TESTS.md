# Android-Hardwaretests

Getrennt von den automatisierten Tests. Ein Fall gilt nur als **bestanden**, wenn er auf einem **physischen Gerät**
durchgeführt und unten eingetragen wurde (Datum, Gerät, Android-Version, Ergebnis). Emulator-Läufe stehen separat
und ersetzen keinen Hardwaretest. Kurzanleitung für Testerinnen und Tester: [TESTING_ANDROID.md](TESTING_ANDROID.md).

**Status: alle Fälle offen – noch kein physisches Gerät getestet.**

## Aufbau
* Server mit Version ≥ 0.2.0, erreichbar vom Handy (LAN `http://<ip>:9092` oder HTTPS über Proxy).
* Zwei Konten in derselben Familie (z. B. per Einladung), zwei physische Android-Handys **A** und **B**
  (möglichst unterschiedliche Hersteller, z. B. Samsung + Pixel/Xiaomi), Akku-Optimierung im Werkszustand.
* Debug-APK `familycircle-0.2.0-debug.apk` (Prüfsumme in `.sha256`), App „FamilyCircle Debug“.
* Web-App im Browser parallel offen (für Beobachtung und QR-Codes).
* Nach jedem Fall in der App **Einstellungen → Diagnose → „Diagnose kopieren“** und dem Ergebnis beilegen
  (enthält keine Koordinaten, Tokens oder E-Mail-Adressen).

## Testfälle

### Einrichtung
| ID | Ablauf | Erwartung |
|---|---|---|
| H-01 | Installation der APK (Installation aus unbekannter Quelle erlauben), App öffnen | Start ohne Absturz in < 10 s, Startbildschirm mit „QR-Code scannen“ |
| H-13 | **QR-Kopplung**: Web-App → Privatsphäre → „Handy verbinden“ → in der App „QR-Code scannen“ | Kamera-Erlaubnis verständlich erklärt; Code wird erkannt; Bestätigung zeigt Server; Anmeldung ohne Passwort |
| H-14 | **QR-Einladung**: Web-App → Familie → „Jemanden einladen“ → mit Handy B scannen | Registrierungsformular mit vorausgefülltem Code; Konto entsteht, Familie beigetreten |
| H-15 | Kamera-Erlaubnis verweigern | Manuelle Adresseingabe funktioniert weiter, klare Meldung |
| H-02a | Berechtigungen: „Genauer Standort – bei Nutzung der App“, Benachrichtigungen erlauben | Erklärungen verständlich, Häkchen erscheinen, Weiter zur Karte |

### Ortung und Übertragung
| ID | Ablauf | Erwartung |
|---|---|---|
| H-02 | Teilen „Ausgewogen“ auf A und B, beide 30 min mitnehmen (gehen, fahren) | Beide sehen sich **gegenseitig auf der Karte in der App** und im Web; Abstand ≈ 2 min in Bewegung, ≤ 5 min in Ruhe (Ziel, keine Garantie) |
| H-03 | Bildschirm aus, App aus der Übersicht wischen, 30 min – je einmal mit und ohne Akku-Optimierung | Benachrichtigung bleibt; Positionen kommen weiter. Beendet der Hersteller die App trotzdem: Neustart nach ≤ 15 min (mit „Immer erlauben“) oder Hinweis „Android hat FamilyCircle beendet“. Teilen endet nur über „Beenden“/„Pausieren“ |
| H-04 | Flugmodus 15 min während Bewegung, danach wieder online | Status „N Positionen warten“; danach alle nachgeliefert (Web: Verlauf, falls aktiviert); Karte beim Gegenüber zeigt währenddessen „veraltet“ |
| H-06 | Modus „Live“ 30 min | Häufigere Updates (≈ 15 s); nach 30 min automatisch „Ausgewogen“ |
| H-08 | Akku < 15 % | Modus wird eine Stufe sparsamer; Status zeigt das an |
| H-09 | 8 h „Ausgewogen“ im Alltag | Akkuverbrauch der App laut Systemstatistik notieren (Ziel: < 5 %/Tag, Richtwert) |
| H-11 | GPS in Gebäuden / Tunnel / Tiefgarage | Grössere Genauigkeitsradien, keine Sprünge quer durch die Stadt; nach Verlassen wieder genau |
| H-16 | Standortdienste in den Android-Schnelleinstellungen ausschalten | App zeigt das klar an, kein Absturz; nach Einschalten geht es weiter |
| H-17 | Genaue Ortung auf „ungefähr“ reduzieren (Android-Einstellungen) | App erklärt die Folge (Familie sieht nur grob) |
| H-18 | Akku-Optimierung: Einstellungen → Diagnose → „Ohne Einschränkungen laufen lassen (optional)“ | Systemdialog erscheint; danach Status „ausgenommen“ |

### Karte und Live-Verhalten
| ID | Ablauf | Erwartung |
|---|---|---|
| H-05 | B widerruft in der App „Meinen Standort zeigen“ für A, während A die Karte offen hat | Bs Marker verschwindet bei A **innerhalb weniger Sekunden** ohne Neuladen |
| H-19 | Profilbild im Web hochladen | Bild erscheint als Marker in beiden Apps |
| H-20 | Veraltete Position: B ausschalten, 20 min warten | Bei A erscheint B grau, gestrichelt, mit Altersangabe – nie „Live“ |
| H-21 | Darstellung: kleines Handy, Querformat, Tablet (falls vorhanden), Schriftgrösse maximal, Dunkelmodus | Alles lesbar, nichts abgeschnitten; auf breiten Bildschirmen Seitenleiste |

### Sitzungen, Sicherheit, Lebenszyklus
| ID | Ablauf | Erwartung |
|---|---|---|
| H-07 | Handy neu starten – einmal **mit**, einmal **ohne** „Immer erlauben“ | Mit: Teilen startet von selbst. Ohne: Hinweis-Benachrichtigung, Start nach App-Öffnung |
| H-10 | Gerät im Web unter Privatsphäre → Anmeldungen und Geräte entfernen | App wird abgemeldet, keine Uploads mehr |
| H-12 | Server über HTTPS mit gültigem Zertifikat (Nginx Proxy Manager) | Verbindung ohne Warnung, keine Klartext-Warnung in der App, Live-Updates funktionieren |
| H-22 | Mehr als 1 h angemeldet lassen (Access-Token läuft ab) | Keine erneute Anmeldung nötig, Uploads gehen weiter (Token-Rotation) |
| H-23 | App-Update: neuere APK über die alte installieren | Konto bleibt angemeldet. Mit „Immer erlauben“: Teilen läuft von selbst weiter. Ohne: Hinweis „FamilyCircle wurde aktualisiert“, App zeigt „pausiert“ |
| H-24 | Server-Wechsel (Einstellungen → anderer Server) | Lokale Daten werden nach Rückfrage gelöscht, Neuanmeldung möglich |
| H-25 | Einladung per WhatsApp: Verwalter teilt den Link aus der App, Empfänger (Android, App noch nicht installiert) tippt ihn in WhatsApp an | Registrierungsseite mit „App herunterladen“; nach Installation öffnet „In der App öffnen“ die Registrierung mit Code, Server ist vorausgefüllt |

## Ergebnisse (physische Geräte)
| Datum | Test | Gerät / Android | Ergebnis | Notizen / Diagnose |
|---|---|---|---|---|
| – | – | – | **noch kein Hardwaretest durchgeführt** | – |

## Emulator-Läufe (kein Ersatz für Hardwaretests)
2026-10-18, zwei gleichzeitige Emulatoren (Android 16, API 36.1, Google-Play-Image), echter Server über HTTP:

* Kopplung beider Geräte per Kopplungslink (gleicher Code-Pfad wie QR-Scan), Berechtigungsdialoge.
* Ortung „Ausgewogen“ auf beiden Geräten → beide sehen sich gegenseitig in der App-Karte (H-02-Kernziel, emuliert).
* Bewegung B → Marker bei A aktualisiert ohne Interaktion; Token-Ablauf auf A → automatische Erneuerung,
  Uploads laufen weiter (H-22, emuliert); Widerruf bei offener Live-Verbindung → Marker weg nach 3–10 s
  (Software-Rendering des Emulators, H-05 auf Hardware offen); Offline 90 s → 6 Positionen nachgeliefert (H-04, emuliert).
* Dabei gefundene und behobene Fehler: keine Positionen im Modus „Ausgewogen“ bei fehlender Netzwerk-Ortung;
  wechselnde Provider erzeugten falsche GPS-Sprünge; gemeldete Geschwindigkeit 0 trotz Bewegung verzögerte Updates.
* Nicht im Emulator testbar: echtes Abfotografieren eines QR-Codes, Akkuverbrauch, Hersteller-Energiesparmodi,
  Neustart, echte GPS-Qualität.
