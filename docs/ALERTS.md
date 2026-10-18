# Meldungen, Orte, Fernaktionen und Verlauf (Phase 3)

Stand: 2026-10-18 · API-Vertrag: `packages/api-contract/openapi.yaml` (Version 0.3.0)

Grundsatz: **Privatsphäre zuerst.** Alles Automatische (Ankunft, Akku) geht nur an Personen, die den
Standort ohnehin sehen dürfen. Fernaktionen bei anderen brauchen deren Einwilligung (Standard: aus).
Wer eine Meldung bekommt, entscheidet der Server in SQL (`store/alerts.go`, Funktion `audience`) –
nie der Client.

## Meldungsarten

| Art (`kind`) | Auslöser | Empfänger | Inhalt |
|---|---|---|---|
| `sos` | Person drückt SOS (App/Web) | alle, die mit ihr eine Familie teilen – **ohne** Standortfreigabe nötig | Position (aktuell oder letzte bekannte) |
| `sos_resolved` | „Ich bin in Sicherheit“ | wie `sos` | – |
| `check_in` | „Bin angekommen“ | wie `sos` | Position, optionale Nachricht (≤ 140 Zeichen), Ort falls innerhalb eines Ortes |
| `arrival` / `departure` | Server erkennt Betreten/Verlassen eines Ortes | Mitglieder der Familie des Ortes, die den Standort sehen dürfen und den Ort nicht stummgeschaltet haben | Ort (Name) |
| `battery_low` | Handy meldet ≤ 15 % (höchstens alle 6 h) | wer den Standort sehen darf; aus, wenn `battery_alerts = false` | Akkustand, letzte Position |
| `last_location` | Handy wird ausgeschaltet (höchstens alle 10 min) | wie `battery_low` | Akkustand, letzte Position |
| `ring` | „Handy klingeln lassen“ | die angesprochene Person (alle ihre Geräte) | gültig 2 min |
| `locate` | „Standort jetzt aktualisieren“ | die angesprochene Person | gültig 5 min |

**Beim Lesen** (`GET /alerts`) wird erneut geprüft: Ist die Freigabe widerrufen oder die gemeinsame
Familie weg, verschwinden auch ältere Meldungen. Aufbewahrung 30 Tage (Ring/Locate 1 Tag).
Löscht jemand seine Standortdaten, werden auch die Positionen in seinen Meldungen entfernt.

`active` im Alert: SOS ungelöst und jünger als 24 h · Ring/Locate noch nicht abgelaufen.
`mine`: eigene SOS/Check-ins (damit alle eigenen Geräte wissen, ob mein SOS aktiv ist).

## Fernaktionen – wer darf?

| Aktion | Eigene Geräte | Andere |
|---|---|---|
| Klingeln (`POST /users/{id}/ring`) | immer | gemeinsame Familie **und** `allow_ring` der Person (Standard aus) |
| Standort aktualisieren (`POST /users/{id}/locate`) | immer | `allow_locate_request` der Person (Standard aus) **und** ich sehe ihren Standort |

Keine gemeinsame Familie → 404 (es wird nichts über fremde Personen verraten). Nicht erlaubt → 403.
`from_device_id` nennt das auslösende Gerät: Ein Handy klingelt nicht, wenn es selbst „mein Handy
klingeln lassen“ ausgelöst hat.

## Orte (Geofences, serverseitig)

* Jedes Mitglied kann Orte anlegen (Name, Mittelpunkt, Radius 50–2000 m, höchstens 50 je Familie);
  ändern/löschen dürfen Ersteller und Verwalter.
* Der Server prüft jede neue aktuelle Position: **Betreten** bei Abstand ≤ Radius (und Genauigkeit
  ≤ max(2·Radius, 200 m)), **Verlassen** bei Abstand > Radius + max(50 m, Genauigkeit). Dazwischen
  bleibt der Zustand (kein Flackern). Ungenauer als 500 m → keine Änderung.
* Neuer Ort / verschobener Ort: der erste Fix setzt den Zustand **still** (keine Meldung).
* `present` in `GET /families/{id}/places` zeigt nur Personen, deren Standort ich sehen darf.
* Stummschalten pro Ort und Person: `PUT/DELETE /places/{id}/mute`.

## Verlauf

* Eigener Verlauf: `GET /me/locations` oder `GET /users/{eigeneId}/locations` (nur wenn „Verlauf“ an).
* Verlauf anderer: `GET /users/{id}/locations` nur wenn **alle** gelten: Standortfreigabe aktiv
  (nicht pausiert, gemeinsame Familie), Person hat „darf meinen Verlauf sehen“ für mich erteilt
  (`PATCH /shares/{meineId} {"history": true}`), Person hat Verlauf eingeschaltet. Sonst 404.
  Akkuwerte nur, wenn die Person Akku teilt. In `GET /shares` zeigt `incoming[].history`, ob ich den
  Verlauf öffnen kann.

## Zustellung an die Apps (ohne Google-Push)

1. **Live-Verbindung** (`GET /ws`): `{"type":"alert","alert":Alert}` sofort.
   * Android hält die Verbindung, solange das Standort-Teilen läuft (im Standort-Dienst), oder wenn der
     Schalter **„Alarme immer sofort empfangen“** an ist (eigener Vordergrunddienst mit dauerhafter
     Benachrichtigung).
2. **Nachholen**: Nach jedem (Wieder-)Verbinden und mindestens alle 15 min (WorkManager)
   `GET /alerts?after=<letzte gesehene id>`. Die zuletzt gesehene ID speichert das Gerät lokal.
   Abgelaufene Ring/Locate-Anfragen werden beim Nachholen ignoriert.
3. Das Web zeigt Meldungen, solange die Seite offen ist (WebSocket + Nachholen beim Laden).

## Verhalten der Android-App (Vorgabe)

| Meldung | Darstellung |
|---|---|
| `sos` (aktiv) | Kanal „SOS“ (höchste Wichtigkeit, Alarmton `USAGE_ALARM`, auch bei lautlos; Vollbild-Hinweis wenn erlaubt); Text „{Name} hat einen SOS-Alarm ausgelöst“, Tippen öffnet die Karte auf der Position; Aktion „Karte öffnen“ |
| `sos_resolved` | Kanal „SOS“ ohne Alarmton: „{Name} ist in Sicherheit“; schliesst die SOS-Benachrichtigung dieser Person |
| `check_in` | Kanal „Check-ins“: „{Name} ist angekommen{ – Ort}“ + Nachricht |
| `arrival`/`departure` | Kanal „Orte“: „{Name} ist bei {Ort} angekommen“ / „hat {Ort} verlassen“ |
| `battery_low`/`last_location` | Kanal „Akku“: „{Name}: Akku {n} %“ / „Handy von {Name} wurde ausgeschaltet – letzter Standort gespeichert“ |
| `ring` (aktiv, nicht vom eigenen Gerät) | Klingelton laut über `USAGE_ALARM` bis zu 60 s oder bis „Stopp“; Benachrichtigung „{Name} lässt dein Handy klingeln“ mit „Stopp“ |
| `locate` (aktiv) | sofort eine frische Position senden (wie „Standort jetzt senden“), keine Benachrichtigung nötig |

SOS-Knopf: gedrückt halten (3 s mit sichtbarem Countdown) – verhindert Fehlalarme; danach roter Banner
„SOS aktiv“ mit „Ich bin in Sicherheit“.
Akku: Der Standort-Dienst meldet einmal je Entladung beim Unterschreiten von 15 % (`reason: low`) und
beim Ausschalten (`ACTION_SHUTDOWN`, `reason: shutdown`, kurzer Timeout).
