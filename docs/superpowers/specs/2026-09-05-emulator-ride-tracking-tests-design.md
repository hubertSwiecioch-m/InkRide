# Emulator-run ride tracking instrumented tests

Date: 2026-09-05

## Goal

Add instrumented tests that run on an Android emulator and exercise the full
ride-tracking flow end to end — real Compose UI, real Koin DI graph, real Room
persistence, real `TrackingService` foreground-service lifecycle — covering
every in-ride measurement (speed, distance, elevation/altitude, grade,
calories, power, heart rate, cadence, compass bearing, GPS quality) plus the
surrounding ride lifecycle (start/pause/resume/stop, auto-pause, laps, goals,
alerts, sensor loss).

`RideTracker`'s own business logic (metric math, auto-pause thresholds, lap
baselines, goal/alert triggering) is already covered by fast, deterministic
JVM unit tests in `:core:domain` (`RideTrackerTest`, `RideMetricsCalculatorTest`,
etc. — see recent commits). The instrumented suite must **not** re-derive that
logic; its job is to prove the pieces only a real Android runtime can prove:
Koin wiring of the real `AndroidRideSensorDataSource`/`AndroidBleSensorDataSource`
graph, live Compose recomposition of the Dashboard screen, the runtime
permission flow, Room read/write through the real repositories, and the
foreground service lifecycle.

## Why the emulator (not Robolectric)

The user asked specifically for tests that run on an emulator. Robolectric
could exercise ViewModel + Compose logic on the JVM, but cannot validate the
real foreground-service lifecycle, real permission dialogs, or real Room/
DataStore I/O with the production `AndroidManifest.xml`/Gradle merged config.
This suite is additive to (not a replacement for) the existing JVM unit tests.

## Location & how to run

- Tests live in `app/src/androidTest/java/com/speedevand/inkride/tracking/`,
  since `:app` is the only module that assembles the full Koin graph, nav
  graph, and `MainActivity`.
- Run via `./gradlew :app:connectedDebugAndroidTest` against a running
  emulator (or `./gradlew :app:connectedCheck`). Not wired into any existing
  CI job in this change — CI emulator provisioning is a separate concern the
  user can request later.

## Faking sensors: Koin override, not a custom Application

Two new test-only fakes, `app/src/androidTest/java/com/speedevand/inkride/tracking/fakes/`:

- `FakeRideSensorDataSource : RideSensorDataSource` — `start()`/`stop()` are
  no-ops; exposes `fun emit(sample: RideSensorSample)` for tests to push GPS/
  barometer/heading samples on demand (no internal timers — the test fully
  controls pacing via `emit` calls interleaved with `Thread.sleep`/`waitUntil`).
- `FakeBleSensorDataSource : BleSensorDataSource` — same shape, `fun emit(sample: BleSample)`.

In each test's `@Before`, after `InkRideApp.onCreate()` has already started
Koin (instrumentation launches the real `Application`), call:

```kotlin
loadKoinModules(
    module {
        single<RideSensorDataSource>(override = true) { fakeSensorSource }
        single<BleSensorDataSource>(override = true) { fakeBleSource }
    },
)
```

`RideTracker` is a lazy Koin `single`; as long as the override happens before
anything resolves it — i.e. before the Dashboard route composes — it is built
against the fakes. `@After` calls `unloadKoinModules` on the same module to
avoid state leaking into the next test class (instrumentation may reuse the
process across test classes in the same run).

Real `UserSettingsRepository`/Room DB stay wired as-is (no fake). Each test's
`@Before` seeds `UserSettings` (weight, units, every metric-visibility toggle
on, BLE HR/cadence display on) directly through the repository via
`runBlocking`, so DataStore state is deterministic without an extra fake layer.
`RideHistoryRepository`/`RideTrackPointRepository`/`RideLapRepository` also
stay real, so the happy-path test can assert persistence.

A shared base, `support/RideTrackingTestRule.kt`, bundles: `GrantPermissionRule`
for `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`/`POST_NOTIFICATIONS`, the
Koin override/unload pair, and settings seeding, so each test class stays
focused on its scenario.

`support/RideSamples.kt` provides a small builder for a synthetic straight-line
ride path (start lat/lon, speed/altitude/bearing profile over N steps) so
tests read as "ride at 20 km/h for 30s, climb 5m" rather than raw sample
literals.

## Production code change: test tags

No `testTag`s exist in the codebase today. Matching on formatted/localized
text (`"12.3"`, `"km/h"` vs `"mph"`) would make assertions fragile. Add
`DashboardTestTags` (in `:feature:dashboard:presentation`) and apply
`Modifier.testTag(...)` to:

- `SpeedHero`'s value text
- `MetricItem`'s value text, parameterized per metric (`metric_value_distance`,
  `metric_value_calories`, `metric_value_power`, `metric_value_elevation_gain`,
  `metric_value_altitude`, `metric_value_grade`, `metric_value_avg_speed`,
  `metric_value_max_speed`, `metric_value_moving_time`, `metric_value_gps_quality`)
- `Compass`'s bearing indicator
- `InfoBar`'s heart-rate/cadence values
- `DashboardActions`' start/pause/resume/stop/reset buttons and `StatusIndicator`
- `LapGoalControls`' lap list items and goal-progress text

This is a small, non-behavioral addition purely for testability, same
category as an accessibility `contentDescription`.

## Gradle changes

`app/build.gradle.kts` `androidTestImplementation` gains
`androidx.test:rules` (for `GrantPermissionRule`) — add a
`androidx-test-rules` entry to `gradle/libs.versions.toml` alongside the
existing `androidx-test-core` (same `androidxTestCore` version). No new Koin
test artifact is needed — `loadKoinModules`/`unloadKoinModules` live in
`koin-core`, already pulled in transitively by `koin-android`.

## Test scenarios (8 classes)

1. **`RideTrackingHappyPathTest`** — start a ride, feed a full synthetic ride
   (varying speed/altitude/bearing) plus BLE HR/cadence samples; assert every
   metric tag on both pager pages and the compass page (swiping via
   `performTouchInput`/`scrollToPage`); stop the ride, assert the dashboard
   resets to idle, and assert a matching `RideRecord` was persisted (read back
   through the real `RideHistoryRepository` from Koin, or by navigating to the
   History screen and asserting the entry is visible).
2. **`RideTrackingManualPauseResumeAndMultiRideTest`** — tap pause/resume
   buttons and assert `StatusIndicator`/metrics behavior (elapsed time stops
   advancing while paused); stop, then immediately start a second ride, and
   assert metrics reset cleanly (no leakage from the first ride).
3. **`RideTrackingAutoPauseTest`** — feed near-zero-speed samples for >3s,
   assert `StatusIndicator` shows `AUTO_PAUSED`; resume movement, assert it
   returns to `TRACKING`.
4. **`RideTrackingBleSensorLossTest`** — start with HR/cadence connected
   (values shown), stop feeding BLE samples, assert HR/cadence fall back to
   the placeholder display after the existing timeout while GPS-based metrics
   keep updating.
5. **`RideTrackingGpsQualityTest`** — feed stale/inaccurate fixes; assert the
   GPS-quality tag shows FAIR/POOR and distance doesn't advance on rejected
   fixes, while barometer-only samples keep the altitude tag updating through
   the GPS gap.
6. **`RideTrackingLapRecordingTest`** — drive some distance, tap "record lap"
   in `LapGoalControls`, assert the new lap appears in the UI list with the
   expected segment distance/time.
7. **`RideTrackingGoalAndAlertTest`** — set a distance/duration goal via UI,
   assert goal progress is shown; separately, feed an over-speed sample and
   collect `RideTracker.alerts` (obtained via Koin `get()`, using Turbine) to
   assert `OverSpeed` fires once — this checks sensor-to-alert wiring end to
   end, not the threshold math itself (already covered on the JVM).
8. **`RideTrackingServiceLifecycleTest`** — starting a ride starts
   `TrackingService` as a foreground service with its notification (checked
   via `NotificationManager.activeNotifications` or `ActivityManager`
   running-services introspection); stopping the ride tears the service down.
   This is real-device-only behavior no JVM test can exercise.

Each test paces itself against a real wall clock (`Thread.sleep`/
`composeTestRule.waitUntil` with generous timeouts), since `RideTracker`'s
auto-pause delay and cadence timeout are wall-clock-based — no fake clock
injection, to keep this a true black-box run.

## Definition of done

All 8 classes pass reliably (no flake on 3 consecutive local runs) via
`./gradlew :app:connectedDebugAndroidTest` on a standard API 34+ emulator
(no Google Play image required, in line with the project's de-googled
constraint). Existing JVM unit tests and lint remain green.

## Out of scope

- Wiring these into CI (no emulator runner configured yet in this project).
- Testing BLE pairing/scanning UI (`:feature:ble:presentation`) — separate
  concern, not part of "in-ride measurements."
- Screenshot/visual regression testing.
