# Full-Ride Simulation Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic JVM test that drives `RideMetricsCalculator` through one continuous, multi-phase simulated bicycle ride (~25.6 km, varying speed and terrain) verifying every metric it computes, and fix any real calculation bugs the test uncovers.

**Architecture:** A reusable test-support builder (`RideSimulationBuilder`) turns a list of `SimPhase` descriptions into a `List<RideSensorSample>` plus an independently-computed `PhaseGroundTruth` per phase (using the project's own `haversineMeters` geodesy primitive, never `RideMetricsCalculator`'s own logic). A single test method feeds the whole sample stream through one `RideMetricsCalculator` instance and asserts per-phase and cumulative metrics, plus per-sample invariants (monotonicity, no NaN/Infinity/negative values).

**Tech Stack:** Kotlin, JUnit5, AssertK 0.28.1 — all already used by `core/domain/src/test/kotlin/.../tracking/RideMetricsCalculatorTest.kt`.

**Spec:** `docs/superpowers/specs/2026-09-06-full-ride-simulation-test-design.md`

## Global Constraints

- Module: `:core:domain`, package `com.speedevand.inkride.core.domain.tracking`, test source set `src/test/kotlin` (not `src/test/java` — confirmed by existing files in that package).
- No new dependencies — reuse JUnit5 (`org.junit.jupiter.api.Test`) and AssertK (`assertk.assertThat`, `assertk.assertions.*`) exactly as `RideMetricsCalculatorTest.kt` does.
- Ground truth must never re-derive `RideMetricsCalculator`'s defensive logic (warm-up, outlier rejection, bounce reversal, stationary-drift, hysteresis). It may call genuinely independent, reusable primitives that already exist in production code, such as `haversineMeters(lat1, lon1, lat2, lon2): Double` in `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/GeoDistance.kt` (package-visible, no import needed from the same package).
- Tolerances (from the spec): geometry metrics (distance, avg/max speed) ±2–3% per clean phase, ±5% for the whole-ride cumulative total (small suppression effects from warm-up/stop-and-go/urban-canyon/bounce phases accumulate); elevation gain ±10%; power/calories checked by ordering plus a documented ±30–60% (`PowerEstimator.ACCURACY_NOTE`) physics-bound sanity check, not exact-value assertions.
- `RideSensorSample` fields used: `timestampMs`, `latitude`, `longitude`, `altitudeFromBarometerM`, `speedFromGpsMps`, `accuracyM`, `satelliteCount`, `bearingDegrees` (see `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideSensorSample.kt`). `altitudeFromGpsM` and `pressureHpa` are not used by this simulation.
- `UserSettings` construction: `UserSettings(weightKg: Int, age: Int, bikeWeightKg: Double = 10.0, bikeType: BikeType = BikeType.ROAD, ...)` (`core/domain/src/main/java/com/speedevand/inkride/core/domain/settings/UserSettings.kt`).

---

## Task 1: Ride simulation builder (test support)

**Files:**
- Create: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSimulationBuilder.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSimulationBuilderTest.kt`

**Interfaces:**
- Consumes: `RideSensorSample` (production, `core/domain/.../tracking/RideSensorSample.kt`), `haversineMeters(lat1, lon1, lat2, lon2): Double` (production, `GeoDistance.kt`, same package).
- Produces (used by Task 2):
  - `enum class SimTerrain { FLAT, CLIMB, DESCENT, STOP }`
  - `data class BounceSpec(val afterStepIndex: Int, val jumpMeters: Double = 33.0, val jumpDurationMs: Long = 3000L)`
  - `data class SimPhase(val name: String, val terrain: SimTerrain, val speedKmh: Double = 0.0, val gradePercent: Double = 0.0, val durationMs: Long, val sampleIntervalMs: Long = 1000L, val accuracyM: Float? = 5.0f, val satelliteCount: Int? = 8, val gpsDropout: Boolean = false, val bounce: BounceSpec? = null)`
  - `data class PhaseGroundTruth(val name: String, val distanceM: Double, val altitudeChangeM: Double, val startSampleIndex: Int, val endSampleIndex: Int)`
  - `data class SimulatedRide(val samples: List<RideSensorSample>, val phases: List<PhaseGroundTruth>)`
  - `object RideSimulationBuilder { fun build(phases: List<SimPhase>, startTimestampMs: Long = 0L, startLatitude: Double = 52.0, startAltitudeM: Double = 100.0): SimulatedRide }`

- [ ] **Step 1: Write the failing tests**

Create `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSimulationBuilderTest.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import kotlin.math.PI
import org.junit.jupiter.api.Test

class RideSimulationBuilderTest {
    @Test
    fun `flat phase produces correct sample count and ground truth distance`() {
        val ride =
            RideSimulationBuilder.build(
                listOf(SimPhase(name = "cruise", terrain = SimTerrain.FLAT, speedKmh = 18.0, durationMs = 10_000L)),
            )
        assertThat(ride.samples).hasSize(10)
        assertThat(ride.phases).hasSize(1)
        // 18 km/h = 5.0 m/s over 10s = 50m
        assertThat(ride.phases.single().distanceM).isCloseTo(50.0, 0.01)
    }

    @Test
    fun `climb phase produces correct altitude gain ground truth`() {
        val ride =
            RideSimulationBuilder.build(
                phases =
                    listOf(
                        SimPhase(name = "climb", terrain = SimTerrain.CLIMB, speedKmh = 12.0, gradePercent = 6.0, durationMs = 10_000L),
                    ),
                startAltitudeM = 100.0,
            )
        // 12 km/h = 3.3333 m/s over 10s = 33.333m distance; altitude change = 33.333 * 0.06 = 2.0m
        assertThat(ride.phases.single().altitudeChangeM).isCloseTo(2.0, 0.01)
        assertThat(ride.samples.last().altitudeFromBarometerM).isCloseTo(102.0, 0.01)
    }

    @Test
    fun `descent phase produces negative altitude change`() {
        val ride =
            RideSimulationBuilder.build(
                listOf(SimPhase(name = "descent", terrain = SimTerrain.DESCENT, speedKmh = 30.0, gradePercent = -6.0, durationMs = 10_000L)),
            )
        assertThat(ride.phases.single().altitudeChangeM).isLessThan(0.0)
    }

    @Test
    fun `stop phase produces zero distance and zero GPS speed samples`() {
        val ride =
            RideSimulationBuilder.build(
                listOf(SimPhase(name = "stop", terrain = SimTerrain.STOP, durationMs = 5000L)),
            )
        assertThat(ride.phases.single().distanceM).isEqualTo(0.0)
        ride.samples.forEach { sample -> assertThat(sample.speedFromGpsMps).isEqualTo(0.0) }
    }

    @Test
    fun `gps dropout phase omits location but ground truth still advances`() {
        val ride =
            RideSimulationBuilder.build(
                listOf(SimPhase(name = "tunnel", terrain = SimTerrain.FLAT, speedKmh = 20.0, durationMs = 5000L, gpsDropout = true)),
            )
        ride.samples.forEach { sample ->
            assertThat(sample.latitude).isNull()
            assertThat(sample.longitude).isNull()
        }
        assertThat(ride.phases.single().distanceM).isGreaterThan(0.0)
    }

    @Test
    fun `bounce injection adds two samples without affecting ground truth distance`() {
        val plainRide =
            RideSimulationBuilder.build(
                listOf(SimPhase(name = "cruise", terrain = SimTerrain.FLAT, speedKmh = 15.0, durationMs = 10_000L)),
            )
        val bouncedRide =
            RideSimulationBuilder.build(
                listOf(
                    SimPhase(
                        name = "cruise",
                        terrain = SimTerrain.FLAT,
                        speedKmh = 15.0,
                        durationMs = 10_000L,
                        bounce = BounceSpec(afterStepIndex = 4, jumpMeters = 33.0),
                    ),
                ),
            )
        assertThat(bouncedRide.samples).hasSize(plainRide.samples.size + 2)
        assertThat(bouncedRide.phases.single().distanceM).isCloseTo(plainRide.phases.single().distanceM, 0.01)

        val stepBeforeJump = bouncedRide.samples[4]
        val jumpSample = bouncedRide.samples[5]
        val jumpOffsetM = (jumpSample.latitude!! - stepBeforeJump.latitude!!) * PI / 180.0 * 6_371_000.0
        assertThat(jumpOffsetM).isCloseTo(33.0, 0.5)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideSimulationBuilderTest"`
Expected: FAIL to compile — `SimTerrain`, `SimPhase`, `BounceSpec`, `RideSimulationBuilder` don't exist yet.

- [ ] **Step 3: Write the builder implementation**

Create `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSimulationBuilder.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import kotlin.math.PI

private const val EARTH_RADIUS_M = 6_371_000.0

/** Terrain shape of one [SimPhase] of a simulated ride. */
enum class SimTerrain { FLAT, CLIMB, DESCENT, STOP }

/**
 * Injects one GPS bounce artifact (a spurious jump away from the true path
 * followed by a "continue from the wrong position" sample) after step
 * [afterStepIndex] of a phase. The very next normal step in the phase acts
 * as the "return" leg, so a bounce needs no separate return parameter.
 */
data class BounceSpec(
    val afterStepIndex: Int,
    val jumpMeters: Double = 33.0,
    val jumpDurationMs: Long = 3000L,
)

/** One labeled segment of a simulated ride. */
data class SimPhase(
    val name: String,
    val terrain: SimTerrain,
    val speedKmh: Double = 0.0,
    val gradePercent: Double = 0.0,
    val durationMs: Long,
    val sampleIntervalMs: Long = 1000L,
    val accuracyM: Float? = 5.0f,
    val satelliteCount: Int? = 8,
    val gpsDropout: Boolean = false,
    val bounce: BounceSpec? = null,
)

/** Ground truth for one phase, computed independently of [RideMetricsCalculator]. */
data class PhaseGroundTruth(
    val name: String,
    val distanceM: Double,
    val altitudeChangeM: Double,
    val startSampleIndex: Int,
    val endSampleIndex: Int,
)

data class SimulatedRide(
    val samples: List<RideSensorSample>,
    val phases: List<PhaseGroundTruth>,
)

/**
 * Builds a synthetic multi-phase ride: a [RideSensorSample] stream plus a
 * ground-truth distance/altitude change per phase. All movement is due
 * north (constant longitude), which makes [haversineMeters] reduce exactly
 * to `Δlatitude_rad × earthRadius` — the same formula this builder uses for
 * ground truth — so geometry assertions can use tight tolerances.
 */
object RideSimulationBuilder {
    fun build(
        phases: List<SimPhase>,
        startTimestampMs: Long = 0L,
        startLatitude: Double = 52.0,
        startAltitudeM: Double = 100.0,
    ): SimulatedRide {
        val samples = mutableListOf<RideSensorSample>()
        val phaseGroundTruth = mutableListOf<PhaseGroundTruth>()

        var timestampMs = startTimestampMs
        var latitude = startLatitude
        var altitudeM = startAltitudeM

        for (phase in phases) {
            val phaseStartLatitude = latitude
            val phaseStartAltitudeM = altitudeM
            val phaseStartSampleIndex = samples.size

            val stepCount = (phase.durationMs / phase.sampleIntervalMs).toInt().coerceAtLeast(1)
            val speedMps = phase.speedKmh / 3.6
            val stepDistanceM = if (phase.terrain == SimTerrain.STOP) 0.0 else speedMps * (phase.sampleIntervalMs / 1000.0)
            val stepAltitudeM =
                when (phase.terrain) {
                    SimTerrain.CLIMB, SimTerrain.DESCENT -> stepDistanceM * (phase.gradePercent / 100.0)
                    SimTerrain.FLAT, SimTerrain.STOP -> 0.0
                }

            for (step in 0 until stepCount) {
                timestampMs += phase.sampleIntervalMs
                latitude += metersNorthToLatitudeDelta(stepDistanceM)
                altitudeM += stepAltitudeM
                samples += buildSample(phase, timestampMs, latitude, altitudeM, speedMps)

                if (phase.bounce != null && step == phase.bounce.afterStepIndex) {
                    val jumpLatitude = latitude + metersNorthToLatitudeDelta(phase.bounce.jumpMeters)
                    timestampMs += phase.bounce.jumpDurationMs
                    samples += buildSample(phase, timestampMs, jumpLatitude, altitudeM, speedMps)

                    timestampMs += phase.sampleIntervalMs
                    val continueLatitude = jumpLatitude + metersNorthToLatitudeDelta(stepDistanceM)
                    samples += buildSample(phase, timestampMs, continueLatitude, altitudeM, speedMps)
                }
            }

            phaseGroundTruth +=
                PhaseGroundTruth(
                    name = phase.name,
                    distanceM = haversineMeters(phaseStartLatitude, 0.0, latitude, 0.0),
                    altitudeChangeM = altitudeM - phaseStartAltitudeM,
                    startSampleIndex = phaseStartSampleIndex,
                    endSampleIndex = samples.size - 1,
                )
        }

        return SimulatedRide(samples, phaseGroundTruth)
    }

    private fun buildSample(
        phase: SimPhase,
        timestampMs: Long,
        latitude: Double,
        altitudeM: Double,
        speedMps: Double,
    ): RideSensorSample =
        if (phase.gpsDropout) {
            RideSensorSample(timestampMs = timestampMs, altitudeFromBarometerM = altitudeM)
        } else {
            RideSensorSample(
                timestampMs = timestampMs,
                latitude = latitude,
                longitude = 0.0,
                altitudeFromBarometerM = altitudeM,
                speedFromGpsMps = if (phase.terrain == SimTerrain.STOP) 0.0 else speedMps,
                accuracyM = phase.accuracyM,
                satelliteCount = phase.satelliteCount,
                bearingDegrees = 0f,
            )
        }

    private fun metersNorthToLatitudeDelta(meters: Double): Double = (meters / EARTH_RADIUS_M) * (180.0 / PI)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideSimulationBuilderTest"`
Expected: PASS (5 tests green).

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSimulationBuilder.kt \
        core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSimulationBuilderTest.kt
git commit -m "$(cat <<'EOF'
test: add multi-phase ride simulation builder

Reusable test-support generator that turns a list of terrain/speed
phases into a RideSensorSample stream plus an independently-computed
ground truth per phase, for the upcoming full-ride simulation test.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YEEF8A5EcGGHPTeSkBpBHW
EOF
)"
```

---

## Task 2: Full-ride simulation test

**Files:**
- Create: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorFullRideSimulationTest.kt`

**Interfaces:**
- Consumes: everything produced in Task 1 (`SimPhase`, `SimTerrain`, `BounceSpec`, `RideSimulationBuilder`, `SimulatedRide`, `PhaseGroundTruth`), plus production `RideMetricsCalculator`, `RideMetrics`, `GpsQuality`, `CaloriesEstimator`, `UserSettings`, `BikeType`.
- Produces: nothing consumed by later tasks — this is the terminal deliverable of the feature. Task 3 may edit production files this test exercises.

- [ ] **Step 1: Write the full-ride script and the simulation test**

Create `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorFullRideSimulationTest.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.UserSettings
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import org.junit.jupiter.api.Test

/**
 * Drives [RideMetricsCalculator] through one continuous ~25.6 km ride made
 * of 20 phases spanning warm-up, flat cruising at two speeds, a sustained
 * climb and descent, rolling hills, stop-and-go city riding, a sprint, a
 * GPS dropout with a clean resume, a poor-accuracy/urban-canyon stretch, a
 * GPS bounce artifact, and a cooldown — to verify every metric the
 * calculator computes together with its defensive logic, in one realistic
 * session.
 */
class RideMetricsCalculatorFullRideSimulationTest {
    private val settings = UserSettings(weightKg = 75, age = 32, bikeWeightKg = 10.0, bikeType = BikeType.ROAD)

    @Test
    fun `full ride simulation across varied terrain and speeds produces correct metrics`() {
        val ride = buildFullRideSimulation()
        val calculator = RideMetricsCalculator()

        val metricsBySampleIndex = ride.samples.map { sample -> calculator.process(sample, settings) }

        assertInvariantsHoldThroughout(metricsBySampleIndex)

        fun phase(name: String) = ride.phases.first { it.name == name }
        fun metricsAt(name: String) = metricsBySampleIndex[phase(name).endSampleIndex]
        fun metricsBefore(name: String) = metricsBySampleIndex[phase(name).startSampleIndex - 1]

        // GPS cold-start warm-up: the first 3 reliable fixes (this ride's very
        // first samples) must not accumulate distance; the streak completes on
        // the 4th sample (index 3), after which movement is trusted. Checked by
        // absolute sample index rather than at the "warmup" phase's own end,
        // since the phase runs long enough (12 samples) for the streak to
        // complete and real distance to start accumulating well before it ends.
        assertThat(metricsBySampleIndex[2].distanceKm).isEqualTo(0.0)
        assertThat(metricsBySampleIndex[3].distanceKm).isGreaterThan(0.0)

        // Phases: flat cruises at two speeds — tight geometry tolerance, MET-bracket ordering.
        val cruise15DistanceM = (metricsAt("cruise-15").distanceKm - metricsBefore("cruise-15").distanceKm) * 1000.0
        assertThat(cruise15DistanceM).isCloseTo(phase("cruise-15").distanceM, phase("cruise-15").distanceM * 0.02)
        assertThat(metricsAt("cruise-15").gpsQuality).isEqualTo(GpsQuality.GOOD)

        val cruise25DistanceM = (metricsAt("cruise-25").distanceKm - metricsBefore("cruise-25").distanceKm) * 1000.0
        assertThat(cruise25DistanceM).isCloseTo(phase("cruise-25").distanceM, phase("cruise-25").distanceM * 0.02)
        assertThat(metricsAt("cruise-25").caloriesKcal).isGreaterThan(metricsAt("cruise-15").caloriesKcal)
        assertThat(metricsAt("cruise-25").averagePowerWatts).isGreaterThan(metricsAt("cruise-15").averagePowerWatts)

        // Phase: sustained climb — positive grade, elevation gain tracks climbed height, power/calories rise.
        val climbMetrics = metricsAt("climb")
        assertThat(climbMetrics.gradePercent).isGreaterThan(0.0)
        val climbElevationGainM = climbMetrics.elevationGainM - metricsBefore("climb").elevationGainM
        assertThat(climbElevationGainM).isCloseTo(phase("climb").altitudeChangeM, phase("climb").altitudeChangeM * 0.10)
        val climbReferenceWatts = referencePowerWatts(speedKmh = 12.0, gradePercent = 6.0)
        assertThat(climbMetrics.averagePowerWatts.toDouble()).isGreaterThan(climbReferenceWatts * 0.4)
        assertThat(climbMetrics.averagePowerWatts.toDouble()).isLessThan(climbReferenceWatts * 1.6)

        // Phase: sustained descent — negative grade, no new elevation gain, power lower than the climb.
        val descentMetrics = metricsAt("descent")
        assertThat(descentMetrics.gradePercent).isLessThan(0.0)
        assertThat(descentMetrics.elevationGainM).isCloseTo(climbMetrics.elevationGainM, 1.0)
        assertThat(descentMetrics.averagePowerWatts).isLessThan(climbMetrics.averagePowerWatts)

        // Phase: rolling hills — elevation gain increases only on the uphill legs.
        val rolling1 = metricsAt("rolling-1")
        val rolling2 = metricsAt("rolling-2")
        val rolling3 = metricsAt("rolling-3")
        assertThat(rolling1.elevationGainM).isGreaterThan(descentMetrics.elevationGainM)
        assertThat(rolling2.elevationGainM).isCloseTo(rolling1.elevationGainM, 1.0)
        assertThat(rolling3.elevationGainM).isGreaterThan(rolling2.elevationGainM)

        // Phase: stop-and-go — distance frozen during a stop, resumes and grows on the next cruise leg.
        val distanceBeforeStop1 = metricsBefore("stopgo-stop-1").distanceKm
        val distanceAfterStop1 = metricsAt("stopgo-stop-1").distanceKm
        assertThat(distanceAfterStop1).isEqualTo(distanceBeforeStop1)
        val distanceAfterCruise2 = metricsAt("stopgo-cruise-2").distanceKm
        assertThat(distanceAfterCruise2).isGreaterThan(distanceAfterStop1 + 0.3)
        assertThat(metricsAt("stopgo-cruise-2").movingTimeSeconds).isGreaterThan(metricsAt("stopgo-stop-1").movingTimeSeconds)

        // Phase: sprint — new ride max speed, highest power of the ride.
        val maxSpeedBeforeSprint = metricsBefore("sprint").maxSpeedKmh
        val sprintMetrics = metricsAt("sprint")
        assertThat(sprintMetrics.maxSpeedKmh).isGreaterThan(maxSpeedBeforeSprint)
        assertThat(sprintMetrics.maxSpeedKmh).isCloseTo(42.0, 2.0)
        assertThat(sprintMetrics.averagePowerWatts).isGreaterThan(climbMetrics.averagePowerWatts)

        // Phase: GPS dropout ("tunnel") — no location fix arrives for ~22s, only
        // barometer samples. Altitude keeps updating through the gap. Distance
        // and calories are only credited once a real fix arrives again, so this
        // is checked across the tunnel PLUS "post-tunnel-resume" span (the first
        // phase with a real fix again), not at the tunnel's own last sample.
        val tunnelStart = metricsBefore("tunnel")
        assertThat(metricsAt("tunnel").altitudeM).isNotNull()
        val resumeMetrics = metricsAt("post-tunnel-resume")
        val tunnelSpanDistanceM = (resumeMetrics.distanceKm - tunnelStart.distanceKm) * 1000.0
        val tunnelSpanGroundTruthM = phase("tunnel").distanceM + phase("post-tunnel-resume").distanceM
        assertThat(tunnelSpanDistanceM).isCloseTo(tunnelSpanGroundTruthM, tunnelSpanGroundTruthM * 0.05)
        // Energy for the resuming fix is capped to 10s (maxIntegrationGapMs)
        // instead of the full ~23s gap since the last real fix, so total calories
        // across the span stay well under what a naive model crediting the full
        // elapsed time (~32s) as continuous riding would produce.
        val tunnelSpanCaloriesKcal = resumeMetrics.caloriesKcal - tunnelStart.caloriesKcal
        val naiveFullSpanKcal = CaloriesEstimator().estimateKcal(speedKmh = 20.0, intervalMs = 32_000L, userSettings = settings)
        assertThat(tunnelSpanCaloriesKcal).isGreaterThan(0.0)
        assertThat(tunnelSpanCaloriesKcal).isLessThan(naiveFullSpanKcal * 0.8)

        // Phase: poor accuracy / urban canyon — every fix's ~5.6 m/s-equivalent
        // displacement stays below the combinedAccuracy(30m)×0.5 = 15m
        // significant-movement threshold and its Doppler speed is discarded as
        // unreliable (accuracy 30m > maxReliableAccuracyM), so distance stays
        // frozen for the whole phase while GPS quality reports POOR throughout.
        assertThat(metricsAt("urban-canyon").distanceKm).isEqualTo(metricsBefore("urban-canyon").distanceKm)
        assertThat(metricsAt("urban-canyon").gpsQuality).isEqualTo(GpsQuality.POOR)

        // Phase: GPS bounce artifact — the 33m jump-and-return must not inflate distance beyond
        // a small, bounded leftover (the "continue" sample routed via the jump position).
        val distanceBeforeBounce = metricsBefore("bounce").distanceKm
        val distanceAfterBounce = metricsAt("bounce").distanceKm
        val bounceLegDistanceM = (distanceAfterBounce - distanceBeforeBounce) * 1000.0
        assertThat(bounceLegDistanceM).isLessThan(phase("bounce").distanceM + 20.0)
        assertThat(bounceLegDistanceM).isGreaterThan(phase("bounce").distanceM * 0.8)

        // Phase: cooldown — steady state, tight geometry tolerance again.
        val cooldownDistanceM = (metricsAt("cooldown").distanceKm - metricsBefore("cooldown").distanceKm) * 1000.0
        assertThat(cooldownDistanceM).isCloseTo(phase("cooldown").distanceM, phase("cooldown").distanceM * 0.02)

        // Cumulative, whole-ride checks. "urban-canyon" is excluded from the
        // expected total: as established above, that phase is deliberately
        // built so every fix's displacement stays under its own
        // significant-movement threshold, so the calculator is correctly
        // expected to credit ~0m for it, not its ground-truth distance.
        val finalMetrics = metricsBySampleIndex.last()
        val totalGroundTruthDistanceM =
            ride.phases.filterNot { it.name == "urban-canyon" }.sumOf { it.distanceM }
        assertThat(finalMetrics.distanceKm * 1000.0).isCloseTo(totalGroundTruthDistanceM, totalGroundTruthDistanceM * 0.05)
        assertThat(finalMetrics.maxSpeedKmh).isCloseTo(42.0, 2.0)
        assertThat(finalMetrics.caloriesKcal).isGreaterThan(0.0)
        assertThat(finalMetrics.averagePowerWatts).isGreaterThan(0)
    }

    /**
     * Independent physics reference (no acceleration term, no drivetrain-loss
     * factor beyond the documented 1.05x) using the same public Crr/CdA
     * constants [PowerEstimator] documents for [BikeType.ROAD], to bound
     * average power without calling [PowerEstimator] itself.
     */
    private fun referencePowerWatts(
        speedKmh: Double,
        gradePercent: Double,
    ): Double {
        val speedMps = speedKmh / 3.6
        val totalMassKg = settings.weightKg + settings.bikeWeightKg
        val gravity = 9.81
        val airDensity = 1.225
        val crr = 0.005 // ROAD, per PowerEstimator's documented Crr table
        val cda = 0.32 // ROAD, per PowerEstimator's documented CdA table
        val slopeAngleRad = atan(gradePercent / 100.0)
        val pRolling = crr * totalMassKg * gravity * speedMps * cos(slopeAngleRad)
        val pAir = 0.5 * cda * airDensity * speedMps.pow(3)
        val pGravity = totalMassKg * gravity * speedMps * sin(slopeAngleRad)
        return (pRolling + pAir + pGravity) * 1.05
    }

    private fun assertInvariantsHoldThroughout(metrics: List<RideMetrics>) {
        var previous: RideMetrics? = null
        metrics.forEach { current ->
            assertThat(current.distanceKm.isFinite()).isTrue()
            assertThat(current.distanceKm < 0.0).isFalse()
            assertThat(current.caloriesKcal.isFinite()).isTrue()
            assertThat(current.caloriesKcal < 0.0).isFalse()
            assertThat(current.elevationGainM.isFinite()).isTrue()
            assertThat(current.elevationGainM < 0.0).isFalse()
            assertThat(current.powerWatts).isGreaterThanOrEqualTo(0)
            assertThat(current.averagePowerWatts).isGreaterThanOrEqualTo(0)

            previous?.let { prior ->
                assertThat(current.distanceKm).isGreaterThanOrEqualTo(prior.distanceKm)
                assertThat(current.elapsedTimeSeconds).isGreaterThanOrEqualTo(prior.elapsedTimeSeconds)
                assertThat(current.movingTimeSeconds).isGreaterThanOrEqualTo(prior.movingTimeSeconds)
                assertThat(current.elevationGainM).isGreaterThanOrEqualTo(prior.elevationGainM)
                assertThat(current.caloriesKcal).isGreaterThanOrEqualTo(prior.caloriesKcal)
            }
            previous = current
        }
    }

    private fun buildFullRideSimulation(): SimulatedRide =
        RideSimulationBuilder.build(
            listOf(
                SimPhase(name = "warmup", terrain = SimTerrain.FLAT, speedKmh = 15.0, durationMs = 12_000L),
                SimPhase(name = "cruise-15", terrain = SimTerrain.FLAT, speedKmh = 15.0, durationMs = 480_000L),
                SimPhase(name = "cruise-25", terrain = SimTerrain.FLAT, speedKmh = 25.0, durationMs = 432_000L),
                SimPhase(name = "climb", terrain = SimTerrain.CLIMB, speedKmh = 12.0, gradePercent = 6.0, durationMs = 1_500_000L),
                SimPhase(name = "descent", terrain = SimTerrain.DESCENT, speedKmh = 30.0, gradePercent = -6.0, durationMs = 600_000L),
                SimPhase(name = "rolling-1", terrain = SimTerrain.CLIMB, speedKmh = 18.0, gradePercent = 3.0, durationMs = 200_000L),
                SimPhase(name = "rolling-2", terrain = SimTerrain.DESCENT, speedKmh = 18.0, gradePercent = -3.0, durationMs = 200_000L),
                SimPhase(name = "rolling-3", terrain = SimTerrain.CLIMB, speedKmh = 18.0, gradePercent = 3.0, durationMs = 200_000L),
                SimPhase(name = "rolling-4", terrain = SimTerrain.DESCENT, speedKmh = 18.0, gradePercent = -3.0, durationMs = 200_000L),
                SimPhase(name = "stopgo-cruise-1", terrain = SimTerrain.FLAT, speedKmh = 15.0, durationMs = 160_000L),
                SimPhase(name = "stopgo-stop-1", terrain = SimTerrain.STOP, durationMs = 8_000L),
                SimPhase(name = "stopgo-cruise-2", terrain = SimTerrain.FLAT, speedKmh = 15.0, durationMs = 160_000L),
                SimPhase(name = "stopgo-stop-2", terrain = SimTerrain.STOP, durationMs = 8_000L),
                SimPhase(name = "stopgo-cruise-3", terrain = SimTerrain.FLAT, speedKmh = 15.0, durationMs = 160_000L),
                SimPhase(name = "stopgo-stop-3", terrain = SimTerrain.STOP, durationMs = 8_000L),
                SimPhase(name = "sprint", terrain = SimTerrain.FLAT, speedKmh = 42.0, durationMs = 90_000L),
                SimPhase(name = "tunnel", terrain = SimTerrain.FLAT, speedKmh = 20.0, durationMs = 22_000L, gpsDropout = true),
                SimPhase(name = "post-tunnel-resume", terrain = SimTerrain.FLAT, speedKmh = 20.0, durationMs = 10_000L),
                SimPhase(
                    name = "urban-canyon",
                    terrain = SimTerrain.FLAT,
                    speedKmh = 20.0,
                    durationMs = 180_000L,
                    accuracyM = 30.0f,
                    satelliteCount = 3,
                ),
                SimPhase(
                    name = "bounce",
                    terrain = SimTerrain.FLAT,
                    speedKmh = 15.0,
                    durationMs = 60_000L,
                    bounce = BounceSpec(afterStepIndex = 10, jumpMeters = 33.0),
                ),
                SimPhase(name = "cooldown", terrain = SimTerrain.FLAT, speedKmh = 18.0, durationMs = 400_000L),
            ),
        )
}
```

- [ ] **Step 2: Run the test and record the result**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorFullRideSimulationTest"`

This test's whole purpose is to discover whether current behavior matches the intended contract — do not assume it passes or fails ahead of time. Record the actual output (open
`core/domain/build/test-results/test/TEST-com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorFullRideSimulationTest.xml`
or rerun with `--info` if a failure message is truncated). If every assertion passes, skip to Step 3. If any assertion fails, stop here and move to Task 3 before touching anything else.

- [ ] **Step 3 (only if Step 2 passed cleanly): Commit**

```bash
git add core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorFullRideSimulationTest.kt
git commit -m "$(cat <<'EOF'
test: add full-ride simulation test for RideMetricsCalculator

Drives the calculator through one continuous ~25.6 km, 20-phase ride
(warm-up, flat cruising, climb/descent, rolling hills, stop-and-go,
sprint, GPS dropout with a clean resume, poor-accuracy stretch, bounce
artifact, cooldown) verifying every metric it computes plus its
defensive logic, against independently-computed ground truth.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YEEF8A5EcGGHPTeSkBpBHW
EOF
)"
```

If Step 2 failed, do **not** commit yet — proceed to Task 3.

---

## Task 3: Triage and fix any failures (conditional — only if Task 2 found failures)

Skip this entire task if Task 2's test passed cleanly on the first run.

**Files:**
- Modify (only as needed, pick whichever the triage implicates): `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt`, `CaloriesEstimator.kt`, or `PowerEstimator.kt`
- Modify (if the test itself was wrong): `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorFullRideSimulationTest.kt` and/or `RideSimulationBuilder.kt`

**Interfaces:** No new public interfaces — this task only corrects behavior or expectations inside files already introduced/touched above.

- [ ] **Step 1: For each failing assertion, classify it**

For every failure reported by the test run, read the assertion's context (which phase, which metric) and the relevant production code path (cite exact file:line from `RideMetricsCalculator.kt`, `CaloriesEstimator.kt`, or `PowerEstimator.kt` — all fully read and summarized in the spec's "Approach" section and this plan's Global Constraints). Decide:

- **Wrong expectation** — the test/builder misunderstood a documented threshold, hysteresis rule, or rounding behavior (e.g. picked a tolerance too tight for a legitimately noisy metric, or miscalculated an expected ground-truth number). Fix the test or `RideSimulationBuilder.kt`.
- **Genuine defect** — the calculator/estimator produces a value that contradicts its own documented contract (e.g. distance is fabricated across a warm-up window, energy isn't capped by `maxIntegrationGapMs`, grade sign is wrong for a climb). Fix the production file.

Do not fix by loosening an assertion that was correctly derived from documented behavior just to make it pass — that defeats the test's purpose. If genuinely unsure which category a failure falls into, use the `superpowers:systematic-debugging` skill to trace the exact code path with the failing sample sequence before deciding.

- [ ] **Step 2: Apply the smallest targeted fix**

Make the change (test-side or production-side, per Step 1's classification). If production code changes, keep the change scoped to the specific defect — do not refactor unrelated logic.

- [ ] **Step 3: Re-run the full-ride simulation test**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorFullRideSimulationTest"`
Expected: PASS. If still failing, repeat from Step 1 for the remaining failures.

- [ ] **Step 4: Re-run the pre-existing calculator/estimator unit tests to confirm no regression**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorTest" --tests "com.speedevand.inkride.core.domain.tracking.CaloriesEstimatorTest" --tests "com.speedevand.inkride.core.domain.tracking.PowerEstimatorTest"`
Expected: PASS (all still green — a fix for the new test must not break the existing, already-approved behavior these lock in).

- [ ] **Step 5: Commit**

If production code changed:

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/ \
        core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorFullRideSimulationTest.kt
git commit -m "$(cat <<'EOF'
fix: <describe the specific defect found by the full-ride simulation>

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YEEF8A5EcGGHPTeSkBpBHW
EOF
)"
```

If only the test/builder was wrong:

```bash
git add core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/
git commit -m "$(cat <<'EOF'
test: correct full-ride simulation expectations

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YEEF8A5EcGGHPTeSkBpBHW
EOF
)"
```

Repeat Task 3 (as a new, separately-committed cycle) for each independent failure rather than batching unrelated fixes into one commit.

---

## Task 4: Final full verification

**Files:** None (verification only).

- [ ] **Step 1: Run the full `:core:domain` test suite**

Run: `./gradlew :core:domain:test`
Expected: BUILD SUCCESSFUL, all tests green (including `RideMetricsCalculatorFullRideSimulationTest`, `RideSimulationBuilderTest`, and every pre-existing test in the module).

- [ ] **Step 2: Run lint**

Run: `./gradlew lintDebug`
Expected: BUILD SUCCESSFUL, no new lint findings attributable to the new files.

- [ ] **Step 3: Confirm working tree is clean**

Run: `git status`
Expected: nothing to commit — every change from Tasks 1–3 was already committed at the end of its own task.
