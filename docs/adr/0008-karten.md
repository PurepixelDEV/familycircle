# ADR-0008: Karten – MapLibre und konfigurierbarer Anbieter

Status: akzeptiert · 2026-10-18

## Entscheidung
* **MapLibre GL JS 6** (BSD-3) im Web; keine Abhängigkeit von Google Maps oder Mapbox-Lizenzen.
* Style-URL konfigurierbar (`FC_MAP_STYLE_URL`). Standard: **OpenFreeMap „liberty“**
  (`https://tiles.openfreemap.org/styles/liberty`, OpenStreetMap-Daten, ohne Schlüssel, Attribution im Style).
* Die CSP erlaubt automatisch den Ursprung der Style-URL; weitere Kachel-/Font-Hosts über `FC_MAP_EXTRA_ORIGINS`.
* MapLibre-Worker wird als gehashtes Asset ausgeliefert (MapLibre 6 sucht ihn sonst relativ zur Moduldatei –
  im Browser-Test gefunden und behoben).
* App-Schriften (Bricolage Grotesque, Atkinson Hyperlegible Next, OFL) werden **mitgeliefert**, nicht von Google Fonts
  geladen (keine IP-Weitergabe).

## Datenschutzhinweis
Der Kachelanbieter sieht IP-Adresse und angezeigten Kartenausschnitt des Browsers – nicht die Positionsdaten der
Familie. Wer das vermeiden will, hostet Kacheln selbst (z. B. Protomaps/PMTiles) und setzt `FC_MAP_STYLE_URL`.

## Offen
* Dunkler Kartenstil (aktuell ein Style für hell und dunkel; `FC_MAP_STYLE_URL_DARK` geplant).
* Karte in der Android-App (MapLibre Native) – siehe Projektstatus.
