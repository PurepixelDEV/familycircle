# FamilyCircle – Android app

Native Android client for FamilyCircle (Kotlin, Jetpack Compose, Material 3). It shares the
user's location with the family members they choose, via the family's own self-hosted server.

No Google Play Services, no Firebase, no analytics, no proprietary dependencies (F-Droid
compatible by design). Licence: AGPL-3.0-or-later (see repository root).

> **Status: v0.2.0 – not tested on real hardware.** The app builds, the JVM unit tests pass and
> lint is clean of errors. **Nothing has been run on a physical device yet.**
>
> **Emulator run of v0.2.0 (2026-10-18, Android 16 / API 36.1 Google-Play image, real Go server):**
> pairing via a `/pair?code=` link pasted into the address field (same code path as a scanned QR
> code) incl. HTTP warning → permission walkthrough → map; CameraX scanner preview and camera
> permission dialog; family map with real data (stale/recent/live markers, accuracy circles,
> "Show everyone", tap-to-focus, bottom sheet); live WebSocket `location` (marker moved at once)
> and `location_hidden` (marker removed at once, after a share was revoked); own "You" marker from
> the recorder; landscape layout (navigation rail + side panel); German, dark mode, font scale 2.0;
> diagnostics incl. the battery-optimisation dialog; invite link → registration form with the code
> prefilled; the R8 **release** build (signed locally with the debug key only for this test) paired
> as a second account and showed the first one – two app instances seeing each other.
>
> **Emulator end-to-end run (2026-10-18, Android 16 / API 36.1 Google-Play image, real Go server):**
> onboarding with HTTP warning, registration via invite code, permission dialogs, location
> foreground service (`types=0x8`), GPS fix → server database (verified), sharing toggle →
> position on the web map, offline queue (5 fixes queued, all delivered with original timestamps
> after reconnect), history opt-in from the app, revoking a share. Details:
> [docs/PROJECT_STATUS.md](../../docs/PROJECT_STATUS.md). Everything below under
> [What has not been tested](#what-has-not-been-tested) still applies to physical devices.

## Build

Requirements:

* JDK 17+ (the JBR bundled with Android Studio, JDK 21, is fine)
* Android SDK with **platform android-36** and build-tools 36.x
* `local.properties` with `sdk.dir=…` (git-ignored; Android Studio writes it automatically)

```sh
cd apps/android
export JAVA_HOME="/path/to/Android Studio/jbr"   # Windows: C:\Program Files\Android\Android Studio\jbr
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # app/build/reports/lint-results-debug.html
./gradlew :app:assembleRelease        # R8-minified, unsigned: app/build/outputs/apk/release/
```

The Gradle wrapper (9.8.1) pins the distribution SHA-256. Release builds are signed only when the
CI provides `FC_SIGNING_*` environment variables; otherwise they stay unsigned.

### Application id and build types

| | applicationId | versionName | Label |
|---|---|---|---|
| release | `org.familycircle.android` | 0.2.0 (versionCode 2) | FamilyCircle |
| debug | `org.familycircle.android.debug` (`applicationIdSuffix`) | 0.2.0-debug | FamilyCircle Debug (`src/debug/res`) |

Debug and release builds install side by side. The Kotlin package / R-class `namespace` is
`org.familycircle.android` and is independent of the applicationId. **The final applicationId is
pending the project owner's approval**; it can never change after the first public release.

**APK size:** debug ≈ 70 MB, release ≈ 53 MB (unsigned). ≈ 49 MB of that are MapLibre's native
`libmaplibre.so` for four ABIs (arm64-v8a 13 MB, armeabi-v7a 9.6 MB, x86/x86_64 13 MB each).
Per-ABI splits (or F-Droid's per-ABI builds) would bring a phone download to ≈ 15–17 MB; not
configured yet.

### Toolchain and versions

| Component | Version | Note |
|---|---|---|
| Gradle | 9.8.1 | wrapper with checksum |
| Android Gradle Plugin | 9.4.1 | **built-in Kotlin** – `org.jetbrains.kotlin.android` is not applied |
| Kotlin (compose + serialization plugins) | 2.4.21 | |
| KSP | 2.3.12 | works with AGP 9 built-in Kotlin without opt-out |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | |
| Compose BOM | 2026.06.01 | Compose 1.11.4, Material 3 1.4.0 |
| core-ktx | 1.18.0 | |
| lifecycle | 2.10.0 | |
| navigation-compose | 2.9.8 | type-safe routes |
| activity-compose | 1.13.0 | |
| Room | 2.8.5 | schema exported to `app/schemas/` |
| WorkManager | 2.12.0 | |
| OkHttp / mockwebserver3 | 5.4.0 | |
| kotlinx-serialization-json | 1.11.0 | |
| kotlinx-coroutines | 1.11.0 | |
| MapLibre Native Android (`org.maplibre.gl:android-sdk`) | 13.7.1 | latest; works with compileSdk 36; BSD-2; pulls OkHttp 4.12 (resolved to our 5.4.0), Timber, MapLibre geojson/turf/gestures – **no Google Play Services** (checked in `:app:dependencies`) |
| CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`) | 1.6.2 | latest stable (1.7.0 is alpha); works with compileSdk 36; pulls Guava/Dagger (Apache-2.0), no ML Kit |
| ZXing core (`com.google.zxing:core`) | 3.5.4 | latest; QR decoding only, no zxing-android-embedded |

**Why not the very latest releases:** core 1.19, Compose 1.12 (BOM ≥ 2026.08.00), lifecycle 2.11,
navigation 2.10 and okhttp-android 5.5 declare `minCompileSdk=37` in their AAR metadata. Only
platforms 35/36 are installed and compileSdk 36 is a fixed project decision, so the newest
versions that still support compileSdk 36 are used. Upgrade them together with compileSdk 37
(compileSdk 37 does **not** require targetSdk 37). The remaining lint warnings are exactly these
"newer version available" notices.

## Architecture

```
org.familycircle.android
├── FamilyCircleApp / AppContainer   manual DI, one instance per process
├── MainActivity                     Compose host
├── api/          ApiClient (OkHttp), AuthSession (serialised token refresh), DTOs, ServerUrl, ApiException,
│                 QrLink (QR URL parser, pure)
├── live/         LiveEvent (WS message parser), LiveMapReducer, ReconnectBackoff (pure), LiveSocket (OkHttp WS)
├── map/          MarkerModel, MapGeoJson, CameraFit, MapStyle (pure); MarkerRenderer (Canvas bitmaps),
│                 AvatarRepository (memory + disk cache)
├── data/
│   ├── prefs/    AppPreferences (SharedPreferences + StateFlow, non-secret state)
│   ├── security/ KeystoreTokenStore (AES-256-GCM, key in AndroidKeyStore)
│   ├── queue/    Room: PendingLocation, DAO, AppDatabase, LocationQueue
│   ├── OnboardingHandoff   scanned invite code → registration form (memory only)
│   └── AccountRepository   server selection, sign-in/registration/pairing, sign-out
├── tracking/
│   ├── TrackingMode, TrackingPolicy, MotionClassifier, FixFilter, FixProcessor/RecordGate,
│   │   ProviderSelector                       ← pure Kotlin, unit-tested
│   ├── LocationRecorder    fix → filter → Room (always before upload)
│   ├── LocationService     foreground service (type location), uploads itself
│   ├── TrackingController  start/stop/mode/"send now" for the UI
│   ├── BootReceiver        optional resume after reboot
│   └── Notifications
├── sync/         Uploader (pure, unit-tested), SyncManager, UploadWorker + UploadScheduler
├── ui/           AppRoot (stage machine + bottom bar / navigation rail), map (MapScreen, MapViewModel,
│                 FamilyMapView), onboarding (ServerScreen, QrScanner, QrDecoder), auth, permissions,
│                 home, family, settings (+ Diagnostics), theme, components
└── util/         Logger (no coordinates/tokens, debug stripped in release), RFC 3339, geo
```

### Location data flow

1. `LocationService` requests updates from `LocationManager` via `LocationManagerCompat` /
   `LocationRequestCompat` with the parameters from `TrackingPolicy`. Provider: `fused` if the
   device has it (`hasProvider`), otherwise `gps` + `network` (low-power: `network` + `passive`).
2. Each fix goes through `FixFilter` (no/zero accuracy, accuracy > 2000 m, invalid, not newer than
   the last accepted fix → dropped), is classified by `MotionClassifier` and thinned by
   `RecordGate` (distance **or** heartbeat).
3. Accepted fixes are written to Room **first** with a device-generated UUID (idempotency key) and
   the fix's own time (`Location.time`) as `recorded_at`.
4. The service's own coroutine uploads batches of ≤ 200 (`POST /api/v1/locations`). Accepted *and*
   rejected ids are deleted locally; network errors, 5xx and 429 keep the queue (backoff in the
   service, plus WorkManager catch-up).
5. WorkManager (`UploadWorker`, `NetworkType.CONNECTED`, exponential backoff, unique work `KEEP`)
   is only the catch-up path when the service is not running – on Android 16 job quotas apply
   even while a foreground service runs.

### Authentication

* `POST /auth/token` (password grant, or password grant + `invite_code` + `display_name` for
  registration). Every request sends `X-FC-CSRF: 1` (required by the server for non-GET requests
  without a bearer token, harmless otherwise).
* Access token (1 h) + rotating refresh token. On 401 the client refreshes **once** and retries.
  Refreshes are serialised with a `Mutex`; a caller whose rejected token is no longer current
  reuses the token another caller already obtained, so concurrent 401s cause exactly one refresh
  (the server revokes the session when a rotated refresh token is reused).
* `token_reused` / `invalid_grant` (any 401/400 from the refresh) → tokens cleared, tracking stopped,
  the UI returns to sign-in with an explanation.
* Tokens are encrypted with AES-256-GCM; the key lives in the AndroidKeyStore (not
  user-auth-bound, because the service uploads while the screen is locked). The deprecated
  `androidx.security:security-crypto` is not used.
* Signing in as a *different* account clears the local queue, so points are never uploaded under
  another user.

### Network security

* HTTPS uses the system trust store only.
* `base-config cleartextTrafficPermitted="true"` because self-hosted servers are often addressed by
  LAN IP literals and it is undocumented whether `domain-config` matches IP literals. The app only
  accepts an `http://` server after an explicit warning dialog, and shows a persistent
  "unencrypted connection" warning on the status and settings screens.
* Redirects are not followed (a redirect surfaces as an error instead of silently switching
  scheme/host).
* No backups: `allowBackup="false"` plus `dataExtractionRules` excluding everything.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | talk to the server; WorkManager network constraint |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | the actual location sharing (precise is recommended; approximate works but the family only sees an area of a few km) |
| `ACCESS_BACKGROUND_LOCATION` | **optional**, requested separately with its own explanation; only used to resume sharing automatically after a reboot |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | location foreground service while sharing is on |
| `POST_NOTIFICATIONS` | the visible "sharing is on" notification with the Pause action (the service also works without it) |
| `RECEIVE_BOOT_COMPLETED` | resume after reboot (or post "open the app to resume") |
| `CAMERA` | only for scanning the connection QR code on the server screen; requested with an explanation, optional (manual entry works without it). `android.hardware.camera.any` is declared `required="false"` |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | optional button in Settings → Diagnostics that opens the system dialog to exempt the app from battery optimisation (continuous location sharing is the app's core function; the app is not distributed via Google Play) |

WorkManager additionally merges `WAKE_LOCK` into the manifest.
`ACCESS_LOCAL_NETWORK` is **not** declared yet: it becomes mandatory with targetSdk 37 (Android 17)
for LAN servers and needs a runtime flow – see the TODOs in `app/build.gradle.kts` and the manifest.

Sharing is opt-in: tracking only starts when the user presses "Start sharing" (or picks a mode),
and it can be paused from the notification at any time. Without "Allow all the time" the user has
to open the app once after a reboot; a notification says so.

## Tracking modes

Targets, not guarantees – Doze, OEM battery savers and the location provider may deliver less often.

| Mode | Interval | Distance | Heartbeat | Quality |
|---|---|---|---|---|
| Battery saver | 15 min | 250 m | 14 min | low power (network/passive preferred) |
| Balanced (default) | 2 min (5 min while stationary) | 50 m | 5 min | balanced |
| Live | 15 s | 10 m | 1 min | high accuracy (GPS) |

* A fix is recorded when it moved at least *Distance* from the last recorded fix **or** the
  *Heartbeat* elapsed. The heartbeat keeps a person who is not moving from appearing "stale"
  (server freshness: live < 2 min, recent < 15 min). Distance thinning is done in the app, not
  via `setMinUpdateDistanceMeters`, because a platform distance filter would suppress the heartbeat.
* **Live** falls back to **Balanced** after 30 minutes (the selection is changed visibly).
* **Low battery** (≤ 15 %, not charging): one level down (Live → Balanced → Battery saver), never
  below Battery saver.
* Motion (`stationary`/`walking`/`driving`) is a **heuristic** from provider speed or displacement
  (< 0.6 m/s, < 2.8 m/s, above); fast running/cycling is reported as "driving".

## UI

Navigation after onboarding: **Map** (start) · **My sharing** · **Family** · **Settings** – a bottom
bar on phones, a `NavigationRail` from 600 dp window width (tablets, landscape). At font scales
above 1.3 only the selected tab shows its label (all keep their accessible names).

1. **Server** – primary action **"Scan QR code"**, manual address entry below. Pasting a
   `/pair?code=` or `/register?code=` link into the address field works like scanning it.
   "Verify connection" (`GET /instance`, shows the version, stores `map_style_url`; tells the user
   to finish the setup in the web UI if `setup_required`; warning dialog for `http://`, also for
   scanned links).
2. **Sign in** or **"I have an invite code"** (registration). Device name defaults to
   `Build.MANUFACTURER + " " + Build.MODEL` and is editable.
3. **Permissions** – plain-language walkthrough: location, notifications, optional "all the time".
4. **Map** – see [Family map](#family-map).
5. **My sharing** – status card (on/paused/off, mode, last fix with relative age, queue size, last
   upload result, effective mode if reduced), start/stop/resume, mode selector, "Send my location now".
6. **Family** – member list incl. share/unshare switch per member (`PUT`/`DELETE /shares/{userId}`),
   freshness badge, "Show in maps app"; auto-refresh every 30 s while visible.
7. **Settings** – account info, `sharing_paused` / `share_battery` / `history_enabled`, permissions,
   **"Connect another phone"** (explains that pairing QR codes are created in the web app under
   Privacy → "Connect a phone"), change server, sign out, **Diagnostics**.

### QR onboarding

Formats per [docs/QR_FORMAT.md](../../docs/QR_FORMAT.md), parsed by `QrLink` (pure, unit-tested):
only `http`/`https`, no user info, no fragment, server = origin; `/pair` and `/register` need exactly
one well-formed `code`; any other path = "connect to this server". Scanner: CameraX
(`Preview` + `ImageAnalysis`, KEEP_ONLY_LATEST, back camera, bound to the lifecycle, unbound on
dispose) + ZXing core `QRCodeReader` on the Y plane (`QrDecoder`, handles row stride and
light-on-dark codes). No ML Kit, no zxing-android-embedded. CAMERA is requested with an
explanation; on denial the user enters the address manually (or opens app settings when the
denial is permanent). Non-FamilyCircle QR codes show a hint and scanning continues.

* `/pair?code=` → verify server (HTTP warning) → confirmation card ("Connect this phone?", server
  shown, device name editable) → `POST /auth/token` `grant_type=pairing_code` (+ `device_name`,
  `app_version`) against the scanned origin; the server is saved only after success → permission
  walkthrough. Invalid/expired/used code (401) → "create a new one in the web app" + "Scan again";
  400 (server without pairing support) → "sign in with e-mail and password instead".
* `/register?code=` → verify → "Continue to registration" → registration form with the code
  prefilled (handed over in memory by `OnboardingHandoff`).
* plain origin → verify → sign-in form.

Codes are never persisted or logged: `QrLink` and `TokenRequest` redact them in `toString()`,
the scanned text is not logged.

### Family map

* **MapLibre Native** 13.7.1 in an `AndroidView`; style = `map_style_url` from `/instance` (a
  server-relative path is resolved against the server; nothing/invalid → "map could not be loaded",
  the list still works). Every `MapView` lifecycle callback (onStart/onResume/onPause/onStop/
  onDestroy) is forwarded exactly once and in order from the destination's `LifecycleOwner`;
  `onLowMemory` via `ComponentCallbacks2`; the view is destroyed when the composable leaves the
  composition (e.g. tab switch).
* Markers are a **GeoJSON source + SymbolLayer** with one bitmap per marker state
  (`style.addImage`, obsolete images removed) – no annotation API. `MarkerRenderer` draws the web
  app's marker: profile picture (or initials on the member colour) in a member-colour ring with a
  pointer tip; **stale** = greyscale picture, dashed slate ring, slate tip and an age pill;
  **recent** = colour ring + age pill; **live** = colour ring + a subtle pulsing halo (separate
  CircleLayer, ~15 fps, only while resumed; static when system animations are off). The own
  marker carries a "You"/"You · 5 min" pill. Accuracy circles (fill + line layer, 48-step
  polygons) when accuracy ≥ 15 m. Rotation/tilt gestures are off; attribution stays visible top-end.
* **Profile pictures**: `GET /users/{id}/avatar?v={avatar_version}` with the bearer token,
  `AvatarRepository` caches decoded bitmaps in memory (8 MB LRU) and the files in
  `cacheDir/avatars` (keyed by version, old versions deleted, 404 remembered, wiped on sign-out).
* **Own location**: the `is_self` member from the server, replaced by the latest fix recorded on
  this phone (`LocationRecorder.latest`, seeded from the newest queued point) when that is newer;
  marked "position from this phone" in the list.
* **Freshness**: `Freshness.effective` (stricter of server value and local recomputation from
  `recorded_at`), re-evaluated every 15 s; stale is never shown as live.
* **List** (bottom sheet on phones, side panel ≥ 600 dp): avatar, name, freshness badge (text +
  colour, stale with dashed outline), relative age, ± accuracy, battery (only if the server sends
  it), motion (not when stale); tap → camera animates to the member (sheet collapses);
  "Show everyone" fits all markers with padding for the sheet/panel, the status chip and the marker
  height (`CameraFit`). Family picker (dropdown) when the user is in several families; the choice is
  remembered. A status chip on the map (sharing on/paused/off + waiting positions) opens My sharing.
* **Updates**: `LiveSocket` connects to `GET /api/v1/ws` (`Authorization: Bearer`, OkHttp, ping
  25 s) **only while the map is visible** (`repeatOnLifecycle(STARTED)`), reconnects with
  exponential backoff + equal jitter (1 s … 60 s), refetches `GET /families/{id}/map` on every
  (re)connect, applies `location` (only newer `recorded_at`), `location_hidden` (marker removed
  at once), `family_changed` (refetch). A 401 on the handshake refreshes the token once through the
  serialised `AuthSession`, then reconnects; a rejected refresh ends live updates (sign-in screen).
  Events arriving during a fetch are replayed on top of its result. Safety-net poll every 60 s.
* The app shows only what the server returns – no client-side sharing assumptions.

### Diagnostics (Settings)

App version/build type/applicationId, Android version, device model, location providers in use and
available, location on/off, permissions (fine/coarse/background/notifications/camera), battery
optimisation exemption (`PowerManager.isIgnoringBatteryOptimizations`), service running, queue
size, last fix time + accuracy, last upload result, WebSocket state. **"Copy diagnostics"** copies an
English text report without coordinates, tokens, e-mail address or server address (only http/https).
An optional button opens `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (falls back to the
battery-optimisation list) with a plain-language explanation.

English (`values/`) and German (`values-de/`, Swiss orthography). Light and dark theme; touch
targets ≥ 48 dp, whole-row click targets, text-based status (colour is never the only signal),
headings and live regions for TalkBack, layouts scroll at large font scales.

## Tests

`./gradlew :app:testDebugUnitTest` runs JVM unit tests for `TrackingPolicy`, `MotionClassifier`,
`FixFilter`/`FixProcessor`, `ProviderSelector`, point sanitising, `Uploader` (batching, deleting
accepted + rejected ids, keeping the queue on network/5xx errors, stopping on a failed refresh,
idempotent re-send), `AuthSession` (concurrent 401s → exactly one refresh, `token_reused`
handling), `ApiClient` against MockWebServer (CSRF header, refresh + retry, concurrent refresh,
contract bodies, error mapping, no redirects), server URL normalisation, JSON contract names /
RFC 3339 formatting, freshness and relative-age logic.

New in 0.2.0: `QrLink` (all formats; rejects other schemes, user info, fragments, missing /
duplicate / malformed codes, garbage; codes redacted), `QrDecoder` (generated QR codes as camera
frames incl. padded row stride, short last row, inverted codes), `LiveEvent` parsing,
`LiveMapReducer` (newer/older/out-of-order/equal, offsets, hidden, family_changed, unknown user,
replay), `ReconnectBackoff` schedule, `MarkerModel` (freshness boundaries, stale never live,
stricter server value, own local fix newer/older), `MapGeoJson`, `CameraFit` (fit/focus/padding
clamping), `MapStyle`, pairing grant JSON vs. contract, `ApiClient.pair` / avatar / WS request
against MockWebServer, `LiveSocket` against a MockWebServer WebSocket (401 → refresh → reconnect →
events; rejected refresh stops; reconnect after a server close), diagnostics report (no
coordinates/e-mail/tokens). **170 tests.**

## Known limitations / TODO

* **The final applicationId is pending the project owner's approval** (currently
  `org.familycircle.android`).
* APK size (MapLibre native libraries for four ABIs) – see above; no ABI splits yet.
* The map style comes from the server's `map_style_url` (default OpenFreeMap): tiles are loaded
  from that third-party host; no offline tiles.
* Map markers do not scale above font scale 1.3 (pill text); the list does.
* Live updates run only while the map is visible; the Family tab still polls every 30 s.
* No invite management, session/device management, account deletion or data export in the app
  (available in the web app). Pairing codes for another phone are created in the web app.
* targetSdk 37: `ACCESS_LOCAL_NETWORK` runtime permission needed for LAN servers.
* `START_STICKY` restarts after the process was killed: without "Allow all the time" Android may
  refuse to restart a location FGS from the background; the app then records the problem and the
  user has to resume manually.
* A permanent 400 for a whole upload batch (should not happen with valid points) would stop the
  queue at that batch; it is reported on the status screen but not automatically resolved.
* F-Droid: whether the F-Droid build server supports AGP 9 is unverified; the release APK contains
  a baseline profile from dependencies, a known reproducible-build pitfall.
* `fused` provider availability on de-Googled ROMs is device-dependent (falls back to GPS/network).
  On the Google emulator image GMS-`fused` delivered no GPS fixes in Balanced mode (emulator
  `geo fix` only reached the app in Live mode).
* Cold start on the swiftshader emulator took ≈ 7 s to the map (MapLibre init); not measured on
  hardware.
* One ANR was seen once on the emulator (render thread stuck in `eglSwapBuffers`, main thread idle)
  right after leaving the camera scanner; it did not reproduce afterwards (same steps repeated) and
  looks like a headless-swiftshader compositor stall – watch for it on hardware.

## What has not been tested

**Nothing has been run on a physical device.** On the emulator (see the status box at the top) the
main flows were exercised against the real server. Still untested:

* everything on real hardware: GPS/`fused`/network delivery, Doze and screen-off behaviour, OEM
  power management, the effect of the battery-optimisation exemption, KeystoreTokenStore on real
  Keystore hardware, real camera autofocus and **decoding a real QR code from the camera**
  (decoding is unit-tested with generated frames; the emulator camera cannot show a QR code);
* profile pictures on the map/list (the test accounts have none; only the API call is unit-tested,
  bitmap decoding/rendering is untested);
* the family picker with several families (the test accounts are in one family);
* WebSocket reconnect after a real network change / server restart on a device (backoff and
  reconnect are unit-tested against MockWebServer);
* tablets (only phone landscape >= 600 dp was checked), small 360x640 dp screens, TalkBack;
* notification Pause action, LIVE timeout, battery step-down, boot receiver after a real reboot,
  Room migrations, WorkManager catch-up;
* a release build signed with the real release key.
