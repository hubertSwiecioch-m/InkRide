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
4. Add one Robolectric integration test driving a real `Location` fix through the actual
   `AndroidRideSensorDataSource` listener via `simulateLocation` (see "Correction" section below —
   this is the only end-to-end coverage of the real class, replacing a mistaken assumption that
   the existing E2E suite provided it).
5. Fix any bugs surfaced during test-writing (e.g. an incorrect gating threshold, a bearing
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

- `rawFix: RawGpsFix?` — small data class, types matching `android.location.Location`'s own
  getters exactly (so the caller does no conversion beyond null-checking `has*()`):
  `latitude: Double`, `longitude: Double`, `accuracyM: Float`, `speedMps: Float?`,
  `bearingDeg: Float?`, `altitudeM: Double?`, `fixTimeMs: Long`, `satelliteCount: Int?`. Null
  when no fix has ever been received, or the caller determines GPS data isn't usable (see below).
  `speedFromGpsMps` in the output `RideSensorSample` is `Double`, so the assembler converts
  `speedMps` at the point of building the output — the `speedMps >= gpsBearingMinSpeedMps`
  comparison in the bearing-source logic below happens on the original `Float`, matching what
  `emitSample()` does today.
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

  The dedup gate only ever *returns a cached filtered position* when `rawFix` is non-null and its
  `fixTimeMs` matches the last-fed one — mirroring `emitSample()`'s `lastKalmanResult` reuse. When
  `rawFix` is `null` (GPS currently unusable), the assembler does not feed the Kalman filter and
  does not fall back to any previously cached result: the output's `latitude`/`longitude`/
  `speedFromGpsMps` are `null` for that call, exactly as `emitSample()`'s
  `filteredPosition = if (useGpsData) {...} else null` produces today. The cached
  `lastKalmanResult` is preserved internally (not cleared) so it can still be reused the next time
  the *same* fix is seen, but it is never surfaced through a null `rawFix`.
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
- `maxGpsFixAgeMs` and `maxSourceAccuracyM` stay as fields on `AndroidRideSensorDataSource` — they
  gate what becomes `rawFix`, which is still decided here, not inside the assembler.
  `gpsBearingMinSpeedMps` moves entirely into `RideSampleAssembler` (as a constructor parameter
  with the same `2.0f` default) since bearing-source selection is now fully the assembler's
  responsibility and `AndroidRideSensorDataSource` has no other use for the constant. This also
  sidesteps a real Kotlin property-initialization-order hazard: the constant is declared later in
  the class body than where the Kalman-related fields currently sit, so keeping a duplicate copy
  there and passing it into the assembler's constructor at field-init time would read an
  unitialized `0.0f` unless the two declarations were carefully reordered.

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
9. Bearing: whichever source is selected is `NaN`/`Infinity` — both a non-finite
   `smoothedHeadingDeg` (heading fallback active) and a non-finite `bearingDeg` on a fast, GPS-
   selected `rawFix` — dropped to `null`, not propagated. Sanitization applies after source
   selection, not per-source.
10. Bearing: raw value outside `[0, 360)` (e.g. `-10`, `370`) → normalized into range, again for
    both the GPS-selected and heading-selected cases.
11. Sample timestamp equals `maxOf` the three per-sensor timestamps and the `now` fallback, for
    several orderings (GPS newest, pressure newest, heading newest, all equal).
12. No fix ever received (`rawFix = null` from the start) → no crash, GPS-derived fields all null.
13. `altitudeFromBarometerM` passthrough: assembler doesn't recompute it, just forwards the
    caller-supplied value unchanged (including `null`).
14. A fix is fed (Kalman result cached), the *next* call passes `rawFix = null` (fix aged out or
    turned inaccurate) → output `latitude`/`longitude`/`speedFromGpsMps` are `null` for that call,
    even though a cached filtered position still exists internally; a subsequent call with the
    *same* `fixTimeMs` as the originally-fed fix (GPS briefly flickered stale then reported the
    same fix again) reuses that cache rather than re-feeding the filter.

## Robolectric smoke tests (`AndroidRideSensorDataSourceStartTest`, in `feature:tracking:data`)

Following `core:database`'s `MigrationTest` pattern (`@RunWith(RobolectricTestRunner::class)`,
`ApplicationProvider.getApplicationContext()`):

1. `start()` → `Result.Error(SensorError.Permission.LOCATION_DENIED)` when neither
   `ACCESS_FINE_LOCATION` nor `ACCESS_COARSE_LOCATION` is granted — set up via
   `Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
   Manifest.permission.ACCESS_COARSE_LOCATION)` (confirmed public API on
   `ShadowContextWrapper`, Robolectric 4.16.1).
2. `start()` → `Result.Error(SensorError.Hardware.GPS_MISSING)` when the shadow
   `LocationManager` reports no GPS provider — set up via
   `Shadows.shadowOf(locationManager).removeProvider(LocationManager.GPS_PROVIDER)` (confirmed
   public API on `ShadowLocationManager`, Robolectric 4.16.1).
3. `start()` → `Result.Success` when permission is granted (`grantPermissions(...)`, same shadow)
   and the GPS provider exists (Robolectric's default `ShadowLocationManager` already registers
   it); a second call is idempotent (returns `Success` again without re-registering listeners).

Requires adding to `feature/tracking/data/build.gradle.kts`: `testImplementation(libs.junit)`,
`testImplementation(libs.robolectric)`, `testImplementation(libs.androidx.test.core)`,
`testRuntimeOnly(libs.junit.vintage.engine)` — identical deps to `core:database`'s existing setup.

## Correction: the existing E2E suite does not cover this class

The original draft of this spec assumed the instrumented ride-tracking E2E suite
(`app/src/androidTest/.../tracking/`) exercises `AndroidRideSensorDataSource` end-to-end and would
catch a refactor regression. That's wrong: those tests inject `FakeRideSensorDataSource` (see
`app/src/androidTest/.../tracking/fakes/FakeRideSensorDataSource.kt`) via Koin override, precisely
*because* an emulator has no real GPS/barometer. `AndroidRideSensorDataSource` itself has **no**
integration-level coverage today, before or after this phase would otherwise have added.

**Addition to scope:** one Robolectric integration test that drives a real `Location` fix through
the actual registered `LocationListener` inside `AndroidRideSensorDataSource`, using
`Shadows.shadowOf(locationManager).simulateLocation(location)` (confirmed public API on
`ShadowLocationManager`, Robolectric 4.16.1). This is the only test in this phase that exercises
the real class end-to-end (construction → `start()` → listener → `emitSample()` →
`RideSampleAssembler` → emitted `RideSensorSample`), and is what actually de-risks the refactor —
not the E2E suite.

Test (`AndroidRideSensorDataSourceLocationIntegrationTest`, same file or a second `@Test` in
`AndroidRideSensorDataSourceStartTest`):
- Grant location permission, `start()`, then `simulateLocation` with a `Location("gps")` built
  with `latitude`, `longitude`, `accuracy`, `speed`, `bearing`, `time = <fresh>`, all set to
  concrete values.
- Collect the first value from `observeSamples()` (a `Flow`; use `Turbine`'s `test {}`, already a
  convention-plugin test dependency) and assert `latitude`/`longitude` match the fix (Kalman
  filter passes the first fix through unchanged, per `PositionKalmanFilterTest`'s own "first fix
  passes through unchanged" case) and `speedFromGpsMps`/`accuracyM` match what was set.
- A second `simulateLocation` call with a different fix and a later `time` asserts a second sample
  is emitted with updated values — proving the listener → `emitSample()` → assembler wiring
  actually runs more than once.

**Explicitly not covered even after this addition:** barometer and rotation-vector sensor events.
Simulating those requires constructing `Sensor` instances via Robolectric's `ShadowSensorManager`
(package-private `Sensor` constructor, needs `Shadow.newInstanceOf`) and registering them before
`AndroidRideSensorDataSource` is constructed (it reads `getDefaultSensor` in its constructor) —
meaningfully more setup for comparatively simple passthrough fields already exercised by
`RideSampleAssemblerTest`'s pure-logic cases. GPS is the fusion path worth the integration-test
investment; barometer/heading integration is deferred (candidate for a later phase if a real bug
ever surfaces there).

## Risks

- No other risks remain open — both Robolectric API questions are confirmed (above), and the
  integration-coverage gap is now addressed by the new test rather than left as a caveat.

## Testing plan

- `./gradlew :core:domain:test` — new `RideSampleAssemblerTest`, plus full existing domain suite
  (regression check on `HeadingSmoother`/`PositionKalmanFilter`, which are reused, not changed).
- `./gradlew :feature:tracking:data:testDebugUnitTest` — new Robolectric smoke tests, including
  the `simulateLocation` integration test, which is what actually verifies the refactor didn't
  break the real class (see "Correction" above — the E2E suite does not, since it uses a fake).
- Existing instrumented ride-tracking E2E suite (`androidTest`) re-run once, after the refactor,
  as a sanity check that DI wiring and app startup still work — not a regression check on
  `AndroidRideSensorDataSource` itself, which it doesn't exercise.
