# Critical Stability & CI Fixes — Design

Date: 2026-07-08

## Context

A full-project audit (see conversation history / no separate audit doc) found that InkRide's
feature set is complete per `FEATURE_PLAN.md` (all Priority 1–4 items done), but surfaced five
small, independent, low-risk issues worth fixing before further feature work:

1. `versionCode` is hardcoded and never increments across releases.
2. BLE sensors (HR/cadence) don't signal disconnection — stale readings persist indefinitely.
3. The tracking foreground-service notification is hardcoded in English despite the app
   supporting Polish (`values-pl`).
4. Caught exceptions in repository/export code are silently swallowed with no diagnostic trail.
5. CI runs tests and `assembleDebug` but never `ktlintCheck` or Android Lint, so style/lint
   regressions can land on `main` unnoticed.

These are bundled into one round because each is small, independent, and low-risk — not because
they share implementation. Each item below can be implemented and reviewed as its own unit.

## Out of scope

- Empty-module cleanup (`:core:data`, `:feature:dashboard:domain`, `:feature:dashboard:data`) —
  tracked separately as a "tech debt" round.
- Test-coverage gaps (`RideTracker`, `AndroidRideSensorDataSource`, DB migrations) — separate round.
- BLE auto-reconnect — bigger UX topic (retry/backoff, re-pairing flow), deferred.
- New features (elevation chart, BLE power meter, wheel-speed distance, segments, etc.) — separate
  design(s) if/when prioritized.

## 1. `versionCode` auto-increment

**Problem:** `app/build.gradle.kts` hardcodes `versionCode = 1`. `release.yml` already tags releases
as `v${versionName}-build.${{ github.run_number }}`, but the APK itself always installs as
`versionCode` 1, so Android may refuse to treat a newer sideloaded APK as an update over an
existing install.

**Change:**
- `app/build.gradle.kts`: read `versionCode` from a Gradle project property, falling back to `1`
  for local builds:
  ```kotlin
  versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
  ```
- `.github/workflows/release.yml`: pass `-PversionCode=${{ github.run_number }}` to the
  `assembleRelease` invocation.

**Testing:** `./gradlew assembleDebug` (no property → versionCode 1) and
`./gradlew assembleRelease -PversionCode=42` locally; confirm the merged manifest reports
`versionCode="42"` via `aapt dump badging` or the build output.

## 2. BLE sensor disconnection handling

**Problem:** `AndroidBleSensorDataSource.onConnectionStateChange` only reacts to
`STATE_CONNECTED`. On `STATE_DISCONNECTED`/`STATE_DISCONNECTING`, `latestHeartRate` /
`latestCadence` are left at their last values forever — the rider keeps seeing a stale HR/cadence
reading with no indication the sensor is gone.

**Change:**
- `AndroidBleSensorDataSource.kt`: on `STATE_DISCONNECTED`, clear `latestHeartRate`/
  `latestCadence` to `null` and emit a connection-lost signal (e.g. a `connected: Boolean` field
  alongside the existing `BleSample` flow, or a separate status flow — match whatever shape
  `AndroidBleSensorDataSource` already exposes for connection state).
- Propagate this through `RideTracker` into `DashboardContract`'s state as e.g.
  `bleSensorConnected: Boolean`.
- `DashboardScreen`: when a sensor was paired but is now disconnected, show a static
  E-Ink-friendly label (no animation) — e.g. "HR sensor disconnected" — instead of silently
  showing nothing or a stale number.
- No auto-reconnect in this round.

**Testing:** unit test around the data source's connection-state handling if it can be isolated
from the real Android `BluetoothGatt` callback machinery (may need a seam/interface already used
by `BleGattTest.kt`); otherwise, a test at the `RideTracker`/`DashboardViewModel` level asserting
that a simulated disconnect clears the state and flips `bleSensorConnected` to `false`.

## 3. Localize `TrackingService` notification

**Problem:** `TrackingService.kt` builds its foreground-service notification with hardcoded
English literals ("InkRide", "Tracking ride…"), bypassing the existing `values` / `values-pl`
localization the rest of the app uses.

**Change:**
- Add string resources (e.g. `notification_tracking_title`, `notification_tracking_text`) to
  `strings.xml` and `values-pl/strings.xml`.
- `TrackingService.kt`: replace the literals with `context.getString(R.string....)`.

**Testing:** manual check — switch device/app language to Polish, start a ride, confirm the
notification text is translated. No automated test needed for this scope.

## 4. Local error logging

**Problem:** Caught exceptions in `RoomBikeProfileRepository`, `RoomUserSettingsRepository`,
`RoomRideHistoryRepository`, and `GpxExporter` are discarded (`catch (e: Exception)` with `e`
unused), mapped straight to a generic `DataError`/`GpxExportError` with no diagnostic trail. There
is currently no logging anywhere in the codebase.

**Change:**
- In each of the above (`:data`-layer, Android-available) classes, add
  `android.util.Log.e(TAG, "<context message>", e)` in the catch block before mapping to the
  error type. Add a `private const val TAG = "..."` per class following standard Android
  convention.
- Logcat only — no crash reporting service, no telemetry, no network calls. Consistent with the
  project's privacy-first constraint.

**Testing:** no new automated tests needed (logging is a side effect); confirm existing repository
tests still pass unchanged.

## 5. `ktlintCheck` + Android Lint in CI

**Problem:** `ci.yml` only runs `testDebugUnitTest` and `assembleDebug`. `ktlint` is configured
project-wide (`ignoreFailures.set(false)`) but never invoked in CI. Android Lint isn't run in CI
at all.

**Prerequisite fix:** `./gradlew lintDebug` currently fails today with a pre-existing false
positive:
```
feature/tracking/data/.../TrackingService.kt:122: Error: Missing permissions required by
Vibrator.vibrate: android.permission.VIBRATE [MissingPermission]
```
`VIBRATE` **is** declared in `app/src/main/AndroidManifest.xml`, but AGP Lint running at the
`:feature:tracking:data` library-module level can't see the merged app manifest, so it flags a
false positive. Fix: add `@Suppress("MissingPermission")` directly on the `vibrate()` call site
with a one-line comment explaining why (declared in app manifest; library-module lint can't see
the merged manifest). This must land before lint is added as a CI gate, or CI goes red
immediately.

**Change:**
- `ci.yml`: add `./gradlew ktlintCheck --no-daemon` and `./gradlew lintDebug --no-daemon` steps
  in the `build-and-test` job (alongside the existing test/assemble steps).

**Testing:** confirm `./gradlew ktlintCheck` and `./gradlew lintDebug` both pass locally after the
suppress fix, then confirm the CI workflow (on the PR that lands this change) goes green with the
new steps present.

## Rollout

All five items are independent and can land as separate commits within one branch/PR, or as
separate small PRs — implementer's choice at plan time. No migrations, no user-facing data
changes, no new permissions.
