# ADR-0007: Android-Ortung ohne Google Play Services

Status: akzeptiert · 2026-10-18 · Grundlage: [docs/research/android-platform-2026-10.md](../research/android-platform-2026-10.md)

## Entscheidung
* **Eigenständiger GPS-Client** in der App – keine Abhängigkeit von OwnTracks, GPSLogger, Traccar Client.
* `LocationManager` / `LocationManagerCompat` (F-Droid-kompatibel). `FUSED_PROVIDER` nur, wenn das Gerät ihn
  anbietet (`hasProvider`), sonst GPS + Netzwerk.
* **Foreground Service vom Typ `location`**, nur auf ausdrückliche Aktion des Nutzers gestartet, mit dauerhafter
  Benachrichtigung inkl. „Pausieren“. Damit gilt die Ortung als Vordergrundnutzung und läuft auch bei
  ausgeschaltetem Bildschirm, ohne zwingend „Immer erlauben“.
* „Immer erlauben“ (`ACCESS_BACKGROUND_LOCATION`) ist **optional** und nur für den automatischen Neustart nach
  einem Geräteneustart nötig (Android 14+ erlaubt den Location-FGS aus `BOOT_COMPLETED` sonst nicht).
* **Room-Warteschlange zuerst**, dann Upload. Uploads laufen im Service selbst (Android 16 begrenzt Jobs, die
  parallel zu einem FGS laufen). WorkManager dient nur als Nachzügler mit Netzwerk-Constraint.
* **Vier Modi** (Aus, Energiesparend, Ausgewogen, Live) mit Zielintervallen, keine Garantien; Live fällt nach
  30 Minuten automatisch auf Ausgewogen zurück; bei niedrigem Akku eine Stufe sparsamer.
* **targetSdk 36** für den ersten Prototyp (lokal verfügbares SDK). Für targetSdk 37 ist die neue Laufzeitberechtigung
  `ACCESS_LOCAL_NETWORK` für LAN-Server zu implementieren (RISKS R-03).
* **Klartext-HTTP**: technisch per `base-config` erlaubt (IP-Literale im LAN lassen sich nicht zuverlässig per
  `domain-config` freigeben), in der App aber nur nach ausdrücklicher Warnung und mit dauerhaftem Hinweis.
* Tokens AES-GCM-verschlüsselt mit Schlüssel im Android Keystore; `allowBackup=false`.

## Konsequenzen
* Ohne Google-Fused-Provider sind Genauigkeit und Akkuverbrauch geräteabhängig – nur durch Hardwaretests bewertbar.
* Hersteller-spezifische Energiesparfunktionen (z. B. aggressive Akkuoptimierung) können den Dienst beenden;
  die App muss das sichtbar machen (Status „letzter Fix vor …“) statt es zu verschleiern.
