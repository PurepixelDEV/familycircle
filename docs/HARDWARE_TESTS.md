# Android-Hardwaretests (manuell)

Dieses Protokoll ist **getrennt von den automatisierten Tests**. Ein Test gilt nur als bestanden, wenn er auf einem
**physischen Gerät** durchgeführt und unten mit Datum, Gerät, Android-Version und Ergebnis eingetragen wurde.
Emulator-Läufe werden separat gekennzeichnet und ersetzen keinen Hardwaretest.

## Vorbereitung
* Server erreichbar (LAN-Test: `http://<ip>:9092`; Produktivtest: HTTPS über Proxy).
* Zwei Konten in derselben Familie, gegenseitige Freigabe.
* Zwei physische Android-Geräte mit installierter APK, Akku-Optimierung im Ausgangszustand des Herstellers.

## Testfälle
| ID | Ablauf | Erwartung |
|---|---|---|
| H-01 | Einrichtung: URL eingeben, verbinden, anmelden, Berechtigungen erteilen | Verständliche Erklärungen, kein Absturz, Tracking-Status sichtbar |
| H-02 | Tracking „Ausgewogen“ starten, Gerät 30 min mitnehmen (gehen, fahren) | Positionen erscheinen auf der Webkarte ohne Neuladen; Abstand ≈ Zielintervall |
| H-03 | Bildschirm aus, App aus der Übersicht wischen | Benachrichtigung bleibt, Positionen kommen weiter |
| H-04 | Flugmodus 15 min während Bewegung, dann wieder online | Offline-Punkte werden nachgeliefert; Karte zeigt währenddessen „veraltet“, danach aktuelle Position |
| H-05 | Freigabe in der Web-App bzw. App widerrufen | Marker verschwindet beim Gegenüber sofort |
| H-06 | „Live“-Modus 30 min | Häufigere Updates, nach 30 min automatisch zurück auf „Ausgewogen“ |
| H-07 | Gerät neu starten (mit/ohne „Immer erlauben“) | Mit: Tracking startet selbst. Ohne: Hinweis-Benachrichtigung, Start nach App-Öffnung |
| H-08 | Akku < 15 % | Modus wird sparsamer, Status zeigt das an |
| H-09 | 8 h Ausgewogen im Alltag | Akkuverbrauch der App laut Systemstatistik notieren |
| H-10 | Gerät in der Web-App entfernen | App wird abgemeldet, keine Uploads mehr |
| H-11 | GPS in Gebäuden / Tunnel | Ungenaue Fixes zeigen grosse Genauigkeitsradien, keine Sprünge auf der Karte |
| H-12 | Server per HTTPS mit Let's-Encrypt-Zertifikat | Verbindung ohne Warnung, keine Klartext-Warnung in der App |

## Ergebnisse
| Datum | Test | Gerät / Android | Ergebnis | Notizen |
|---|---|---|---|---|
| – | – | – | **noch kein Hardwaretest durchgeführt** | – |

## Emulator-Läufe (kein Ersatz für Hardwaretests)
Siehe [PROJECT_STATUS.md](PROJECT_STATUS.md), Abschnitt „Emulator-Ende-zu-Ende-Test“.
