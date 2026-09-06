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
