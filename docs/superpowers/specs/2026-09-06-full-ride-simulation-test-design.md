# Full-ride simulation test for RideMetricsCalculator

Date: 2026-09-06

## Goal

Add one comprehensive JVM test that drives `RideMetricsCalculator` through a
single, continuous, multi-phase synthetic bicycle ride — tens of kilometers,
varying speeds, and varying terrain — to exercise every measurement/calculation
the calculator produces (current/avg/max speed, distance, moving/elapsed time,
GPS+barometer altitude fusion, elevation gain, grade, calories, power, GPS
quality) together with its defensive logic (cold-start warm-up, outlier
rejection, bounce detection, stationary-drift confirmation, GPS-dropout energy
capping). Where the test surfaces a genuine calculation bug (not a wrong
expectation in the test itself), fix it in `RideMetricsCalculator`,
`CaloriesEstimator`, or `PowerEstimator`, keeping the existing unit and E2E
suites green.

## Scope: RideMetricsCalculator only

`RideMetricsCalculator` is the single component responsible for every
in-ride number computed from sensor samples. BLE heart-rate/cadence, the
`RideTracker`-level auto-pause state machine, laps, goals/alerts, GPX export,
and Room persistence are separate components already covered by the existing
instrumented E2E suite (`app/src/androidTest/.../tracking/`:
`RideTrackingAutoPauseTest`, `RideTrackingLapRecordingTest`,
`RideTrackingBleSensorLossTest`, `RideTrackingGoalAndAlertTest`,
`RideTrackingGpsQualityTest`, etc.). This test does not touch them, unless a
bug found here turns out to originate in shared code.

## Why a JVM test, not another instrumented E2E test

`RideMetricsCalculator.process()` is pure computation over `RideSensorSample`
— no Android runtime, no real clock dependency (timestamps are just `Long`
fields). A multi-kilometer, multi-phase ride can therefore be simulated as a
plain sequence of `process()` calls with synthetic timestamps, running in
well under a second, instead of paced against a real wall clock like the
instrumented suite (which must, since `RideTracker`'s auto-pause/cadence
timeouts are wall-clock based). This keeps the "many kilometers, many
conditions" ambition cheap to run and cheap to extend.

## Approach: phase-based ride generator with exact ground truth

New test-support file:
`core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSimulationBuilder.kt`

A small builder generates the full sample stream as a sequence of **phases**
(`SimPhase`: a data class describing duration, speed, grade/altitude
trajectory, GPS accuracy/satellite count, and sample cadence). The builder
walks the phases in order, threading persistent simulated state (current
timestamp, current latitude, current altitude, cumulative "true" distance)
across phase boundaries, and returns both:

- the `List<RideSensorSample>` to feed the calculator, and
- a `RideGroundTruth` snapshot per phase boundary (expected cumulative
  distance, expected altitude, expected net elevation gained in that phase)
  computed directly from the phase parameters — **not** by re-implementing
  `RideMetricsCalculator`'s logic.

All movement is due north (constant longitude, latitude stepping by
`speed × dt`), which makes the calculator's haversine distance mathematically
identical to the ground-truth arc-length formula (`Δlat_rad × earthRadiusM`)
for well-behaved segments — so geometry-based assertions (distance,
avg/max speed) can use tight tolerances instead of loose sanity bounds, while
still being a real independent computation rather than a restatement of the
production code.

Reused/mirrored conventions from the existing suites: JUnit5 + AssertK (per
`RideMetricsCalculatorTest.kt`), a single `RideMetricsCalculator` instance
processing the whole stream sequentially like `RideTrackingHappyPathTest`
does at the E2E layer.

## Ride script (12 phases, ~35 km, single continuous session)

1. **GPS cold-start warm-up** (~12 s) — a few fixes with accuracy flipping
   reliable/unreliable, ending on a streak that satisfies
   `warmupReliableFixes = 3`. Assert: zero distance/speed until warm-up
   completes, matching `RideMetricsCalculatorTest`'s existing warm-up
   expectations but as the ride's opening act instead of an isolated case.
2. **Flat cruise @ 15 km/h**, ~2 km, good fix quality (accuracy 5 m, 8
   satellites). Pure geometry: expected distance = speed × time, **±2%**
   tolerance. Assert `GpsQuality.GOOD`, grade ≈ 0, calories/power > 0.
3. **Flat cruise @ 25 km/h**, ~3 km, same fix quality. Assert calories and
   power **higher** than phase 2 (MET-bracket ordering).
4. **Sustained climb**, ~5 km @ 12 km/h, +6% grade (altitude rising via
   barometer). Assert grade settles positive within the calculator's rolling
   window, elevation gain ≈ climbed height **±10%** (hysteresis-driven
   nonlinearity), and power/calories higher than a flat cruise at comparable
   speed.
5. **Sustained descent**, ~5 km @ 30 km/h, -6% grade. Assert grade settles
   negative, elevation gain does **not** increase during this phase
   (non-decreasing invariant holds flat), power lower than the climb phase.
6. **Rolling hills**, ~4 km alternating short +3%/-3% segments. Assert
   elevation gain increases only on net-uphill legs beyond the 1 m noise
   threshold; distance keeps accumulating correctly through grade sign
   changes.
7. **Urban stop-and-go**, ~2 km: repeated cycles of ~20 s @ 15 km/h then a
   stop of 6+ stationary samples (arming the 5-sample stationary-drift gate),
   then resume. Assert distance is suppressed during the stop and through the
   first post-stop "moving" confirmation sample, then resumes on the second
   confirmation (mirrors `RideMetricsCalculatorTest`'s stationary-drift cases,
   but chained back-to-back multiple times in one ride). Assert moving time
   excludes stopped periods while elapsed time keeps advancing throughout.
8. **Sprint**, ~1 km @ 42 km/h burst. Assert a new ride max speed is
   recorded and power/calories peak for the ride, while staying below
   `maxPlausibleSpeedMps` (no outlier rejection triggered).
9. **GPS signal loss ("tunnel")**, ~15 s with no location fixes (only
   barometer samples flowing through), then a resuming fix ~120 m ahead.
   Assert: altitude keeps updating from the barometer-only samples during the
   gap (gating design); calories/power integrated for that resuming fix are
   capped to `maxIntegrationGapMs = 10 s` of energy (not the full ~15 s gap),
   while distance/moving-time use the actual gap per the documented
   dropout-consistency fix.
10. **Poor GPS accuracy / urban canyon**, ~1 km, accuracy 25–35 m,
    satelliteCount 3–4. Assert `GpsQuality` reports `FAIR`/`POOR` per the
    documented thresholds; assert distance behavior matches the
    `combinedAccuracy × 0.5` significant-movement rule (per-fix step sizes
    chosen to straddle that rule so both the "counted" and "suppressed" sides
    are exercised).
11. **GPS bounce artifact** — inside an otherwise steady low-speed segment,
    inject one spurious jump-then-return fix pair. Assert final cumulative
    distance is **not** inflated by the bounce (the injected jump is known to
    the generator and excluded from ground truth), verifying
    `recentPositions` reversal end-to-end inside a realistic ride rather than
    the isolated case in `RideMetricsCalculatorTest`.
12. **Cooldown**, ~2 km @ 18 km/h flat, good fix quality, ending the ride.

## Assertions & tolerances

Applied both per-phase (at the sample marking each phase boundary) and
cumulatively at the end:

| Metric | Check |
|---|---|
| Distance, avg/max speed (geometry phases) | ground truth ± 2–3% |
| Elevation gain | ground truth ± 10% |
| Grade | correct sign per phase; magnitude within calculator's ±35% clamp |
| Calories | ordering across phases (climb > flat > descent at comparable speed; sprint highest); positive, finite |
| Power | same ordering as calories; bounded within the documented **±30–60%** of an independent reference computed in the test from `PowerEstimator`'s documented physics formula (`P_rolling + P_air + P_gravity`, using the same public Crr/CdA constants noted in its KDoc) — an independent recomputation, not a call into `PowerEstimator` |
| GPS quality | matches `computeGpsQuality`'s documented thresholds per phase's accuracy/satellite inputs |
| Invariants (checked every sample, not just phase boundaries) | `distanceKm`, `elapsedTimeSeconds`, `movingTimeSeconds`, `elevationGainM` are non-decreasing; no `NaN`/`Infinity`/negative value in any numeric field |

This matches the agreed strategy: tight tolerances for geometry-driven
metrics, wide documented-accuracy tolerances plus ordering/sanity checks for
the physics-model metrics (calories, power).

## Bug-fix workflow

Write the test first against current behavior. Any failure gets triaged:

- **Wrong expectation** (the test misunderstood a documented threshold or
  hysteresis rule) → fix the test.
- **Genuine defect** in `RideMetricsCalculator`/`CaloriesEstimator`/
  `PowerEstimator` → fix the source with the smallest targeted change,
  re-run this test plus the full existing suites
  (`:core:domain:test`, and the instrumented E2E suite if the change touches
  shared logic) to confirm no regression, and note the fix in the commit
  message.

## Location & how to run

- Support builder: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSimulationBuilder.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorFullRideSimulationTest.kt`
- Run via `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorFullRideSimulationTest"`

## Definition of done

- New test passes reliably (deterministic — no wall-clock dependency, no
  flake possible by construction).
- Any real bugs the test uncovers are fixed in source, with all of
  `RideMetricsCalculatorTest`, `CaloriesEstimatorTest`, `PowerEstimatorTest`,
  and the existing instrumented E2E suite still green.
- `./gradlew :core:domain:test` and `./gradlew lintDebug` stay green.

## Out of scope

- BLE heart-rate/cadence, auto-pause state machine, laps, goals/alerts, GPX
  export, Room persistence — already covered by the existing instrumented
  E2E suite.
- Wiring this test into CI (no change to CI config in this task).
- Any new instrumented (`androidTest`) test — this is a pure JVM addition.
