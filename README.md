# Suprah Driver Tracker (Android)

A tracking-only companion app for Suprah drivers. While a driver is on duty with an accepted load, it shares their location with Dispatch, including with the screen locked or another app open. Everything else stays in the Driver Portal.

**What it must do** is in [docs/behaviour-spec.md](docs/behaviour-spec.md). Every behaviour has an ID (for example T-10); tests reference them.

**Where the work stands, what's next, and how to set up on a new PC** is in [docs/development-plan.md](docs/development-plan.md).

## Architecture

```
ui/            Compose screens + ViewModels (state in, events out; no business logic)
  pair/          PairScreen, PairViewModel
  home/          HomeScreen, HomeComponents, HomeViewModel
tracking/      Tracking while on duty
  TrackingEngine     status checks, GPS on/off, refusals, going off duty (Android-free, unit tested)
  PositionUploader   instant sends, newest-first after a backlog, adaptive batches (unit tested)
  TrackingService    foreground service hosting the engine + the notification
  TrackingLauncher   on/off duty, resume; RestartReceiver resumes after reboot/update
  LocationSource     fused location as a Flow
data/
  settings/      DataStore settings; device key encrypted with an Android Keystore AES-GCM key
  positions/     Room database of readings waiting to be sent (5,000 cap)
  remote/        Retrofit + kotlinx.serialization client for /api/tracking-app/*
domain/        Models, ApiResult, TrackerApi interface
permissions/   Phone-settings checks (incl. Xiaomi Autostart) and the screens behind the "Fix" buttons
di/            Hilt modules
```

Dependencies point inwards: `ui` and `tracking` use `domain` and `data` through interfaces, so each layer is tested with fakes (see `app/src/test`).

## Build variants

| Variant | App ID | Server | Use |
|---|---|---|---|
| `devDebug` | `com.suprah.drivertracker.debug` | `http://localhost:5000` by default, editable when pairing; http allowed | Development against a local backend |
| `prodDebug` | `com.suprah.drivertracker.debug` | `https://api.suprah-app.com`, fixed | Checking the live configuration |
| `prodRelease` | `com.suprah.drivertracker` | `https://api.suprah-app.com`, fixed; https only; shrunk with R8 | Drivers |

## Requirements

- Android Studio (or JDK 21 for the command line; Gradle 8.11 does not run on newer Java)
- Android SDK 35
- `local.properties` with `sdk.dir` (Android Studio creates it; never committed)

## Run against your local backend

1. Backend: on the `feature/driver-tracking-app` branch (the app's server routes live there), run `npm run dev` with `TRACKING_APP_ENABLED=true`. See [docs/development-plan.md](docs/development-plan.md) for the full set of settings.
2. Phone: USB debugging on, then `adb reverse tcp:5000 tcp:5000`.
3. Install `devDebug`, get a pairing code from the Driver Portal (Settings → Phone tracking), pair, and approve the phone in the Driver Review Center.

## Checks

```
./gradlew ktlintCheck lintDevDebug lintProdRelease testDevDebugUnitTest
./gradlew assembleDevDebug assembleProdRelease
```

`./gradlew ktlintFormat` fixes formatting. On Windows use `gradlew.bat`, with `JAVA_HOME` pointing to a JDK 21.

The same checks run on GitHub (`.github/workflows/android.yml`) once the project is in a repository.

## Release signing

Create `keystore.properties` in the project root (never committed):

```
storeFile=path/to/suprah-tracker.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it, `assembleProdRelease` produces an unsigned APK. Keep the key somewhere safe: an app update must be signed with the same key, or drivers have to uninstall and reinstall.

## Not in this version

The items marked **NEW** in the behaviour spec (for example GPS after a restart without signal, sending leftovers in the background, the fake-GPS flag and the diagnostics screen) come in the next fix round. That round and the real-phone checks still to run are listed in [docs/development-plan.md](docs/development-plan.md).
