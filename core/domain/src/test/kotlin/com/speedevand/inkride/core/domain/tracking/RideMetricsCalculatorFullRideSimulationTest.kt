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

        assertInvariantsHoldThroughout(metricsBySampleIndex, ride)

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

        // Phase: sprint — new ride max speed, highest power of the ride. Compared
        // via the instantaneous powerWatts, not averagePowerWatts: the latter is
        // a whole-ride, time-weighted cumulative average (see
        // RideMetricsCalculator's powerWeightedSumWattMs/powerDurationMs), so by
        // the time the short 90s sprint ends, its own high power has barely
        // moved an average diluted by everything ridden before it (climb,
        // descent, rolling hills, stop-and-go) — while at the end of the much
        // longer climb phase, the cumulative average is still dominated by the
        // climb itself. Instantaneous power is the correct, phase-local measure
        // of "highest power of the ride."
        val maxSpeedBeforeSprint = metricsBefore("sprint").maxSpeedKmh
        val sprintMetrics = metricsAt("sprint")
        assertThat(sprintMetrics.maxSpeedKmh).isGreaterThan(maxSpeedBeforeSprint)
        assertThat(sprintMetrics.maxSpeedKmh).isCloseTo(42.0, 2.0)
        assertThat(sprintMetrics.powerWatts).isGreaterThan(climbMetrics.powerWatts)

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

    private fun assertInvariantsHoldThroughout(
        metrics: List<RideMetrics>,
        ride: SimulatedRide,
    ) {
        // The "bounce" phase deliberately triggers RideMetricsCalculator's
        // GPS bounce-reversal mechanism (see its `isBounce` handling, which
        // retroactively subtracts the outbound jump leg's distance once the
        // return is confirmed — also covered by
        // `bounce detection reverses the outbound jump distance, not just the
        // return leg` in RideMetricsCalculatorTest). That is a single,
        // intentional, bounded backward correction, not a defect, so
        // distanceKm is allowed one bounded dip within this phase's sample
        // range while staying strictly non-decreasing everywhere else in the
        // ride.
        val bouncePhase = ride.phases.first { it.name == "bounce" }
        val bounceSampleRange = bouncePhase.startSampleIndex..bouncePhase.endSampleIndex
        val maxBounceDipKm = 0.034 // configured bounce jumpMeters (33m) + floating-point slack

        var previous: RideMetrics? = null
        metrics.forEachIndexed { index, current ->
            assertThat(current.distanceKm.isFinite()).isTrue()
            assertThat(current.distanceKm < 0.0).isFalse()
            assertThat(current.caloriesKcal.isFinite()).isTrue()
            assertThat(current.caloriesKcal < 0.0).isFalse()
            assertThat(current.elevationGainM.isFinite()).isTrue()
            assertThat(current.elevationGainM < 0.0).isFalse()
            assertThat(current.powerWatts).isGreaterThanOrEqualTo(0)
            assertThat(current.averagePowerWatts).isGreaterThanOrEqualTo(0)

            previous?.let { prior ->
                if (index in bounceSampleRange) {
                    assertThat(current.distanceKm).isGreaterThanOrEqualTo(prior.distanceKm - maxBounceDipKm)
                } else {
                    assertThat(current.distanceKm).isGreaterThanOrEqualTo(prior.distanceKm)
                }
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
