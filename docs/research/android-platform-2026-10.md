# Android-Plattformvorgaben für Standort-Apps (geprüft 2026-10-18)

Quelle: Primärdokumentation developer.android.com / f-droid.org. Jede Aussage mit Link.

## Versionen
* Android 17 (API 37) ist seit 16.06.2026 stabil. https://developer.android.com/blog/posts/android-17-is-here
* Lokal installiert sind SDK-Plattformen 35 und 36. **Entscheidung:** compileSdk/targetSdk **36** für den
  ersten Prototyp. Wechsel auf 37 ist geplant (siehe `ACCESS_LOCAL_NETWORK` unten) → `docs/RISKS.md` R-03.

## Foreground Service vom Typ `location`
* Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `android:foregroundServiceType="location"`,
  `startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)`.
  https://developer.android.com/develop/background-work/services/fgs/service-types
* Zur Startzeit müssen Standortdienste aktiv sein und COARSE oder FINE gewährt sein, sonst `SecurityException`.
  https://developer.android.com/about/versions/14/changes/fgs-types-required
* Android 15/16/17 ändern den Typ `location` nicht. Die 6-h-Timeouts aus Android 15 betreffen nur
  `dataSync`/`mediaProcessing`. https://developer.android.com/about/versions/15/behavior-changes-15

## Start aus dem Hintergrund / nach Neustart
* Ab targetSdk 31 ist ein FGS-Start aus dem Hintergrund verboten, ausser bei Ausnahmen wie
  `BOOT_COMPLETED`. https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
* Ein Location-FGS darf aus dem Hintergrund (auch nach dem Boot) nur gestartet werden, wenn
  `ACCESS_BACKGROUND_LOCATION` gewährt ist. `checkSelfPermission` meldet trotzdem GRANTED, deshalb
  ist eine eigene Prüfung erforderlich.
* `location` steht nicht auf der Android-15-Sperrliste für `BOOT_COMPLETED`.

**Folge für FamilyCircle:** Ohne „Immer erlauben“ läuft die Ortung, solange der FGS läuft (auch mit
ausgeschaltetem Bildschirm). Nach einem Neustart muss der Nutzer die App einmal öffnen. Mit „Immer
erlauben“ (optional, separat erklärt) startet die App nach dem Boot automatisch neu.

## Berechtigungsablauf
* Vordergrund- und Hintergrundstandort müssen nacheinander angefragt werden. Ab Android 11 erteilt
  der Nutzer die Hintergrundberechtigung auf der Einstellungsseite.
  https://developer.android.com/develop/sensors-and-location/location/permissions/background
* Ein laufender FGS gilt als Vordergrundnutzung und behält den Standortzugriff im Hintergrund.
  https://developer.android.com/develop/sensors-and-location/location/permissions
* Ohne FGS bekommen Hintergrund-Apps den Standort nur „ein paar Mal pro Stunde“.
  https://developer.android.com/about/versions/oreo/background-location-limits
* `POST_NOTIFICATIONS` ist für den FGS nicht nötig. Wird sie verweigert, erscheint der Hinweis nur im
  Task-Manager. Wir fragen sie trotzdem an, damit der Tracking-Status sichtbar ist.
  https://developer.android.com/develop/ui/views/notifications/notification-permission

## WorkManager/JobScheduler (Android 16)
* Laufzeitquoten gelten auch für Jobs, die gleichzeitig mit einem FGS laufen.
  https://developer.android.com/about/versions/16/behavior-changes-all

**Folge:** Bei aktivem FGS lädt der Service selbst hoch (Coroutine im Service). WorkManager dient nur
als Nachzügler mit Netzwerk-Constraint, wenn der Service nicht läuft (z. B. Tracking pausiert, aber
die Warteschlange ist noch nicht leer).

## Netzwerk
* Ab targetSdk 28 ist Klartext-HTTP standardmässig verboten. Erlaubt wird es über die Network Security
  Config (`base-config` oder `domain-config`). https://developer.android.com/privacy-and-security/security-config
  * Ob `domain-config` IP-Literale abdeckt, ist nicht dokumentiert. Daher nutzen wir `base-config`
    mit `cleartextTrafficPermitted="true"`. In der App ist HTTP nur nach ausdrücklicher Bestätigung
    möglich, mit dauerhaftem Warnhinweis.
* **`ACCESS_LOCAL_NETWORK`** (Gruppe NEARBY_DEVICES) ist ab targetSdk 37 Pflicht für Verbindungen
  ins LAN (10/8, 172.16/12, 192.168/16 …), für HTTP und HTTPS gleichermassen. Unter 37 gilt sie implizit
  als gewährt. VPN und Mobilfunk sind nicht betroffen.
  https://developer.android.com/privacy-and-security/local-network-permission

## LocationManager ohne Google Play Services
* API 31+: `requestLocationUpdates(provider, LocationRequest, Executor, LocationListener)`.
  Für ältere Geräte `LocationManagerCompat` + `LocationRequestCompat`.
  https://developer.android.com/reference/androidx/core/location/LocationManagerCompat
* `FUSED_PROVIDER` gibt es nur „falls vorhanden“ (`hasProvider`), sonst wird auf GPS/NETWORK
  zurückgegriffen. `PASSIVE_PROVIDER` ist immer vorhanden.

## F-Droid
* Ausschliesslich FLOSS: kein GMS, kein Firebase. Reproduzierbare Builds sind optional (Fallstricke:
  `baseline.prof`, VCS-Info). https://f-droid.org/docs/Inclusion_Policy/ · https://f-droid.org/docs/Reproducible_Builds/
* zxing-android-embedded 4.3.0 steht unter Apache-2.0 und hat keine GMS-Abhängigkeit, wird aber seit
  2021 nicht mehr gepflegt → für den QR-Scan später neu bewerten.

## Nicht verifiziert
* Ob der F-Droid-Buildserver AGP 9 / compileSdk 37 unterstützt.
* Ob `FUSED_PROVIDER` auf ROMs ohne Google-Dienste vorhanden ist (geräteabhängig, microG).
