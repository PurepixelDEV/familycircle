# ADR-0009: Aktualität, Idempotenz und GPS-Sprünge

Status: akzeptiert · 2026-10-18

## Entscheidung
* **Aktualität** wird immer aus `recorded_at` (Zeitpunkt des GPS-Fixes auf dem Gerät) abgeleitet, nie aus dem
  Empfangszeitpunkt: `live` < 2 min, `recent` < 15 min, sonst `stale`. Der Server liefert den Wert mit; Web-App
  und Android-App berechnen ihn fortlaufend neu und verwenden den strengeren Wert. Ein aus der Offline-Warteschlange
  nachgelieferter Fix erscheint dadurch nie als live.
* **Idempotenz**: Jeder Fix trägt eine Client-UUID. Verlauf: `UNIQUE(user_id, client_event_id)` + `ON CONFLICT DO NOTHING`.
  Letzter Standort: wird nur durch einen *neueren* `recorded_at` ersetzt. Ungeordnete Stapel werden sortiert.
* **Identität** kommt ausschliesslich aus der Gerätesitzung; der Upload-Body hat kein Benutzerfeld.
* **Validierung**: Koordinaten-/Wertebereiche, NaN/Inf, `recorded_at` höchstens 5 min in der Zukunft und
  höchstens 7 Tage alt. Abgelehnte Punkte werden einzeln mit Grund gemeldet, damit das Gerät sie verwirft.
* **GPS-Sprünge**: Ein Fix ist verdächtig, wenn die implizite Geschwindigkeit zum letzten akzeptierten Fix
  (abzüglich beider Genauigkeitsradien) > 300 m/s ist. Er wird im Verlauf markiert und verschiebt den letzten
  Standort nicht. Bestätigt ein **folgender** Fix die neue Position (plausibel relativ zum Kandidaten), gilt der
  Sprung als echt (z. B. nach einem Flug).
