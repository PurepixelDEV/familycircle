# FamilyCircle – Android app

Native Android client for FamilyCircle (Kotlin, Jetpack Compose, Material 3). It shares the
user's location with the family members they choose, via the family's own self-hosted server.

No Google Play Services, no Firebase, no analytics, no proprietary dependencies (F-Droid
compatible by design). Licence: AGPL-3.0-or-later (see repository root).

> **Status: first iteration, not tested on real hardware.** The app builds, the JVM unit tests
> pass and lint is clean of errors. **Nothing has been run on a physical device yet.**
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

The Gradle wrapper (9.8.1) pins the distribution SHA-256. Release signing is not configured.

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
├── api/          ApiClient (OkHttp), AuthSession (serialised token refresh), DTOs, ServerUrl, ApiException
├── data/
│   ├── prefs/    AppPreferences (SharedPreferences + StateFlow, non-secret state)
│   ├── security/ KeystoreTokenStore (AES-256-GCM, key in AndroidKeyStore)
│   ├── queue/    Room: PendingLocation, DAO, AppDatabase, LocationQueue
│   └── AccountRepository   server selection, sign-in/registration, sign-out
├── tracking/
│   ├── TrackingMode, TrackingPolicy, MotionClassifier, FixFilter, FixProcessor/RecordGate,
│   │   ProviderSelector                       ← pure Kotlin, unit-tested
│   ├── LocationRecorder    fix → filter → Room (always before upload)
│   ├── LocationService     foreground service (type location), uploads itself
│   ├── TrackingController  start/stop/mode/"send now" for the UI
│   ├── BootReceiver        optional resume after reboot
│   └── Notifications
├── sync/         Uploader (pure, unit-tested), SyncManager, UploadWorker + UploadScheduler
├── ui/           AppRoot (stage machine + bottom navigation), onboarding, auth, permissions,
│                 home, family, settings, theme, components
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

1. **Server** – enter the URL, "Verify connection" (`GET /instance`, shows the version; tells the
   user to finish the setup in the web UI if `setup_required`; warning dialog for `http://`).
2. **Sign in** or **"I have an invite code"** (registration). Device name defaults to
   `Build.MANUFACTURER + " " + Build.MODEL` and is editable.
3. **Permissions** – plain-language walkthrough: location, notifications, optional "all the time".
4. **My sharing** – status card (on/paused/off, mode, last fix with relative age, queue size, last
   upload result, effective mode if reduced), start/stop/resume, mode selector, "Send my location now".
5. **Family** – members with colour dot, name, location age, freshness (worse of server value and
   local recomputation; stale is marked "Outdated" and never shown as live), accuracy, battery,
   motion, "Show in maps app" (`geo:` intent), share/unshare switch per member
   (`PUT`/`DELETE /shares/{userId}`); auto-refresh every 30 s while visible; family picker if
   the user is in several families.
6. **Settings** – account info, `sharing_paused` / `share_battery` / `history_enabled`
   (disabling history asks for confirmation because the server deletes it), permissions,
   change server (= sign out + wipe local data), sign out (confirms and states how many unsent
   positions would be lost).

English (`values/`) and German (`values-de/`, Swiss orthography like the project docs). Light and
dark theme; dynamic colour is supported but off by default. Touch targets ≥ 48 dp via Material 3
defaults, whole-row toggles/radio buttons, text-based status (colour is never the only signal),
headings and live regions for TalkBack, layouts scroll at large font scales.

## Tests

`./gradlew :app:testDebugUnitTest` runs JVM unit tests for `TrackingPolicy`, `MotionClassifier`,
`FixFilter`/`FixProcessor`, `ProviderSelector`, point sanitising, `Uploader` (batching, deleting
accepted + rejected ids, keeping the queue on network/5xx errors, stopping on a failed refresh,
idempotent re-send), `AuthSession` (concurrent 401s → exactly one refresh, `token_reused`
handling), `ApiClient` against MockWebServer (CSRF header, refresh + retry, concurrent refresh,
contract bodies, error mapping, no redirects), server URL normalisation, JSON contract names /
RFC 3339 formatting, freshness and relative-age logic.

## Known limitations / TODO

* **applicationId `org.familycircle.android` must be confirmed before the first public release.**
* QR-code scanning of server URL / invite code: not implemented (zxing-android-embedded is
  unmaintained since 2021 – re-evaluate, see the research doc).
* No map yet – the family screen is a list with a "Show in maps app" button. Next step: MapLibre
  Native with `map_style_url` from `/instance` (check its minCompileSdk first).
* No WebSocket live updates (`/ws`); the family screen polls every 30 s.
* No avatars, no invite management, no session/device management, no account deletion or data
  export in the app (available in the web app).
* targetSdk 37: `ACCESS_LOCAL_NETWORK` runtime permission needed for LAN servers.
* No request to ignore battery optimisations; aggressive OEM power management may still kill or
  throttle the service.
* `START_STICKY` restarts after the process was killed: without "Allow all the time" Android may
  refuse to restart a location FGS from the background; the app then records the problem and the
  user has to resume manually.
* A permanent 400 for a whole upload batch (should not happen with valid points) would stop the
  queue at that batch; it is reported on the status screen but not automatically resolved.
* F-Droid: whether the F-Droid build server supports AGP 9 is unverified; the release APK contains
  a baseline profile from dependencies, a known reproducible-build pitfall.
* `fused` provider availability on de-Googled ROMs is device-dependent (falls back to GPS/network).

## What has not been tested

Nothing below has been run on a physical device or emulator:

* the whole UI flow (onboarding, sign-in/registration, permission dialogs, all screens, dark mode,
  German locale, TalkBack, large font scales);
* the foreground service: start/stop/pause, notification and its Pause action, location delivery
  from `fused`/`gps`/`network`, mode switching, LIVE timeout, battery step-down, behaviour in Doze
  and with the screen off;
* `KeystoreTokenStore` encryption/decryption on real Keystore hardware;
* Room migrations/queries on device, WorkManager catch-up, boot receiver after a real reboot;
* "Send my location now" (`getCurrentLocation`);
* communication with the real Go server (only MockWebServer emulations of its behaviour);
* the R8 release build at runtime (it builds; serializers are kept per the mapping file).
