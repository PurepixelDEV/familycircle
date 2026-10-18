# ADR-0003: Live-Updates per WebSocket-Hub im Prozess

Status: akzeptiert · 2026-10-18

## Kontext
Neue Positionen sollen ohne Neuladen erscheinen; Widerruf muss bestehende Live-Verbindungen sofort betreffen.

## Entscheidung
* Ein WebSocket-Endpunkt `/api/v1/ws` (coder/websocket), authentifiziert wie die REST-API (Cookie oder Bearer).
  Standard-Origin-Prüfung der Bibliothek schützt vor Cross-Site-WebSocket-Hijacking.
* Der Hub speichert **keine Berechtigungen**. Für jedes Ereignis werden die Empfänger mit der zentralen
  Sichtbarkeitsregel (ADR-0005) aus der Datenbank berechnet. Ein Widerruf wirkt dadurch ohne Cache-Invalidierung.
* Bei Widerruf/Pause/Familienaustritt sendet der Server aktiv `location_hidden`; Clients entfernen den Marker.
* Alle 25 s prüft jede Verbindung ihre Sitzung in der DB (abgelaufen/widerrufen → Verbindung wird geschlossen);
  Abmeldung, Gerät entfernen und Passwortänderung schliessen betroffene Verbindungen sofort.
* Langsame Clients (voller Puffer) werden getrennt statt den Hub zu blockieren; Clients laden nach jedem
  Reconnect den vollständigen Stand neu und pollen zusätzlich (60 s verbunden, 20 s getrennt).

## Konsequenzen
* Kein Redis/Broker nötig. Einschränkung: nur **eine** Server-Instanz. Skalierungspfad: PostgreSQL
  `LISTEN/NOTIFY` als Fan-out zwischen Instanzen (kein zusätzlicher Container).
* Eine DB-Abfrage pro Positionsereignis für die Empfängerliste – bei der Zielgrösse unkritisch.
