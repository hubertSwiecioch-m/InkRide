# Sensor-Adapter Fusion Test Coverage — Design (Phase 1)

Date: 2026-09-05

## Context

Part of a broader initiative to maximize test coverage of measurement, calculation, statistics,
and sensor/GPS data-processing code across the app (distance, elevation, speed, calories, power,
etc.). The initiative was decomposed into four independently-landable phases:

1. **This phase** — sensor-adapter fusion logic (zero coverage today).
2. GPX build/parse (`GpxBuilder`, `GpxRouteLoader`).
3. Audit existing calculator tests for edge-case gaps, and review whether the existing E2E suite
   (ride-tracking, `TrackingService` lifecycle, lap recording, ride-goal/over-speed alerts) actually
   asserts correct outcomes rather than just "it ran."
4. Lifetime-stats SQL aggregation (seeded Room DAO test).

`2026-07-08-gps-sensor-precision-design.md` already audited and refined this exact fusion logic
(rotation-vector heading, cadence dropout, GPS bounce handling, etc.) against industry practice —
that audit is not being re-litigated here. This phase is purely about giving the *existing,
already-vetted* logic automated test coverage, since none exists today.

`BleGattTest.kt` already establishes the project's precedent for this exact problem: pure
functions (`parseHeartRate`, `CscCadenceTracker`) were extracted out of an otherwise
Android-entangled BLE data source and unit tested directly with JUnit5 + assertk, no Robolectric.
This design applies the same pattern to `AndroidRideSensorDataSource`.

## Goal

Add direct, fast, deterministic unit-test coverage for the GPS/barometer/heading fusion and
gating logic in `AndroidRideSensorDataSource.emitSample()`, and fix any correctness bugs the new
tests reveal.

## Scope

1. Extract `emitSample()`'s pure decision logic into a new, framework-free, stateful class
   `RideSampleAssembler` in `core:domain:tracking` (alongside `HeadingSmoother` and
   `PositionKalmanFilter`, which it wraps).
2. Unit test `RideSampleAssembler` exhaustively via `:core:domain:test` (JUnit5 + assertk, no
   Robolectric needed).
3. Add Robolectric-based smoke tests in `feature:tracking:data` for `start()`'s permission/
   hardware-gating branches (`LOCATION_DENIED`, `GPS_MISSING`, happy path).
4. Fix any bugs surfaced during test-writing (e.g. an incorrect gating threshold, a bearing
   wraparound edge case, a timestamp-arbitration mistake).

## Non-goals

- `AndroidBleSensorDataSource` connection orchestration (multi-address connect/disconnect,
  notification-queue advancement) and `AndroidBleScanner`'s scan flow — these are state-machine
  plumbing, not measurement/calculation. HR/CSC *parsing* is already well covered by
  `BleGattTest`. Deferred to a later phase if wanted.
- Reworking the fusion algorithm itself — no behavior change beyond bugs the new tests
  legitimately catch.
- Changing `RideSensorDataSource`'s public interface or DI wiring. `AndroidRideSensorDataSource`'s
  public API (constructor, `start()`/`stop()`/`observeSamples()`) is unchanged.

## Design: `RideSampleAssembler`

A new small stateful class in `core:domain/src/main/java/.../tracking/RideSampleAssembler.kt`,
following the existing `PositionKalmanFilter`/`HeadingSmoother` shape: owns a `PositionKalmanFilter`
instance plus the Kalman-feed-dedup state (`lastKalmanFedLocationTimeMs`, `lastKalmanResult`)
internally, exposes `assemble(...)` and `reset()`.

**Inputs** (all plain primitives/nullable value types — no `Location`/`Sensor` types):

- `rawFix: RawGpsFix?` — small data class: `latitude`, `longitude`, `accuracyM`, `speedMps?`,
  `bearingDeg?`, `altitudeM?`, `fixTimeMs`, `satelliteCount?`. Null when no fix has ever been
  received, or the caller determines GPS data isn't usable (see below).
- `pressureHpa: Double?`
- `altitudeFromBarometerM: Double?` — pre-computed by the caller (via
  `SensorManager.getAltitude`, an Android static call) since the assembler stays framework-free.
- `smoothedHeadingDeg: Float?`
- `nowMs: Long`, `gpsTimestampMs: Long`, `pressureTimestampMs: Long`, `headingTimestampMs: Long`

**Behavior** (moved verbatim from `emitSample()`, not redesigned):

- GPS usability gate: fresh (`now - fixTimeMs < maxGpsFixAgeMs`) AND accurate
  (`accuracyM <= maxSourceAccuracyM`). The caller passes `rawFix = null` when the raw `Location`
  fails this gate, so the assembler doesn't need to know about `maxGpsFixAgeMs`/`maxSourceAccuracyM`
  — simpler split of responsibility, and keeps those two Android-flavored constants where they
  already live.

  The freshness/accuracy gate and the Kalman-feed-dedup gate are logically separate (a fix can be
  usable but already fed to the Kalman filter this cycle). The assembler owns only the dedup gate
  (by `fixTimeMs`); the caller (`AndroidRideSensorDataSource`) owns the freshness/accuracy gate
  before constructing `RawGpsFix`.
- Bearing source: GPS course-over-ground when `rawFix != null && bearingDeg != null &&
  speedMps != null && speedMps >= gpsBearingMinSpeedMps`; otherwise `smoothedHeadingDeg`.
- Bearing sanitization: drop non-finite values, normalize into `[0, 360)`.
- Sample timestamp: `maxOf(gpsTimestampMs, pressureTimestampMs, headingTimestampMs, nowMs)`.

**Output:** `RideSensorSample` (unchanged type).

`reset()` clears the wrapped `PositionKalmanFilter` and the dedup state — called from
`AndroidRideSensorDataSource.stop()`.

### `AndroidRideSensorDataSource` after the refactor

- Replaces its own `positionKalmanFilter` field and the two Kalman-dedup fields with a single
  `RideSampleAssembler` instance.
- `emitSample()` shrinks to: apply the freshness/accuracy gate to `lastLocation` to decide
  `rawFix`, compute `altitudeFromBarometerM` from `lastPressureHpa` (unchanged, stays here since
  it's an Android static call), and delegate to `assembler.assemble(...)`, then
  `samplesFlow.tryEmit(result)`.
- `stop()` calls `assembler.reset()` instead of resetting `positionKalmanFilter` directly.
- `gpsBearingMinSpeedMps`, `maxGpsFixAgeMs`, `maxSourceAccuracyM` stay as fields on
  `AndroidRideSensorDataSource` (they gate what becomes `rawFix`, not the assembler's own logic).

## Test matrix (`RideSampleAssemblerTest`, in `core:domain`)

Each case maps to a real branch in the current `emitSample()`:

1. Fresh + accurate fix → GPS-derived fields populated, Kalman filter fed.
2. No fix this cycle (caller passed `rawFix = null` for staleness/inaccuracy) → GPS fields null,
   barometer/heading still flow through.
3. Same `fixTimeMs` passed twice in a row → Kalman filter fed only once; second call returns the
   cached filtered position (guards the over-shrinking-covariance bug described in the source
   comment).
4. A new, different `fixTimeMs` → Kalman filter fed again.
5. Bearing: fast (`speedMps >= gpsBearingMinSpeedMps`) + `bearingDeg` present → GPS bearing used.
6. Bearing: slow (`speedMps < gpsBearingMinSpeedMps`) → falls back to `smoothedHeadingDeg`.
7. Bearing: `rawFix == null` (GPS stale/inaccurate) → falls back to `smoothedHeadingDeg`
   regardless of any stale speed value.
8. Bearing: both GPS bearing and smoothed heading absent → `null`.
9. Bearing: `smoothedHeadingDeg` is `NaN`/`Infinity` → dropped to `null`, not propagated.
10. Bearing: raw value outside `[0, 360)` (e.g. `-10`, `370`) → normalized into range.
11. Sample timestamp equals `maxOf` the three per-sensor timestamps and the `now` fallback, for
    several orderings (GPS newest, pressure newest, heading newest, all equal).
12. No fix ever received (`rawFix = null` from the start) → no crash, GPS-derived fields all null.
13. `altitudeFromBarometerM` passthrough: assembler doesn't recompute it, just forwards the
    caller-supplied value unchanged (including `null`).

## Robolectric smoke tests (`AndroidRideSensorDataSourceStartTest`, in `feature:tracking:data`)

Following `core:database`'s `MigrationTest` pattern (`@RunWith(RobolectricTestRunner::class)`,
`ApplicationProvider.getApplicationContext()`):

1. `start()` → `Result.Error(SensorError.Permission.LOCATION_DENIED)` when neither
   `ACCESS_FINE_LOCATION` nor `ACCESS_COARSE_LOCATION` is granted.
2. `start()` → `Result.Error(SensorError.Hardware.GPS_MISSING)` when the shadow
   `LocationManager` reports no GPS provider.
3. `start()` → `Result.Success` when permission is granted and the GPS provider exists; a second
   call is idempotent (returns `Success` again without re-registering listeners).

Requires adding to `feature/tracking/data/build.gradle.kts`: `testImplementation(libs.junit)`,
`testImplementation(libs.robolectric)`, `testImplementation(libs.androidx.test.core)`,
`testRuntimeOnly(libs.junit.vintage.engine)` — identical deps to `core:database`'s existing setup.

## Risks

- The refactor touches production code (`AndroidRideSensorDataSource`) that the existing
  instrumented ride-tracking E2E suite exercises end-to-end. After refactoring, that suite must
  still pass unmodified — it's the regression backstop for this phase, not something this phase
  adds to.
- Robolectric's exact `ShadowLocationManager` API for "no GPS provider" needs confirming during
  implementation (may already be the default un-configured state) — an implementation detail, not
  a design blocker.

## Testing plan

- `./gradlew :core:domain:test` — new `RideSampleAssemblerTest`, plus full existing domain suite
  (regression check on `HeadingSmoother`/`PositionKalmanFilter`, which are reused, not changed).
- `./gradlew :feature:tracking:data:testDebugUnitTest` — new Robolectric smoke tests.
- Existing instrumented ride-tracking E2E suite (`androidTest`) re-run once, after the refactor,
  to confirm no behavioral regression in the real `AndroidRideSensorDataSource`.
