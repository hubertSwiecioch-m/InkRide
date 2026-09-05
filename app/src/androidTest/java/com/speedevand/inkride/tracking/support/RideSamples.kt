package com.speedevand.inkride.tracking.support

import com.speedevand.inkride.core.domain.tracking.RideSensorSample

/**
 * Builds [RideSensorSample]s for a synthetic straight-line ride heading due
 * north (bearing 0°), one step per real second. [stepIndex] starts at 1;
 * latitude advances by exactly `speedKmh` worth of distance per step so the
 * fed lat/lon path and [RideSensorSample.speedFromGpsMps] agree —
 * `RideMetricsCalculator` cross-validates the two and would otherwise reject
 * the fix as an implausible jump.
 *
 * Callers pass `nowMs` (real `System.currentTimeMillis()` at the moment of
 * emission) rather than a precomputed timestamp, so wall-clock-gated logic in
 * `RideTracker`/`RideMetricsCalculator` (auto-pause delay, cadence timeout,
 * GPS cold-start warm-up) sees consistent, real elapsed time between samples.
 */
object RideSamples {
    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    const val START_LATITUDE = 52.2297
    const val START_LONGITUDE = 21.0122

    /**
     * A moving fix `stepIndex` seconds into the ride at `speedKmh`.
     * [includeGpsFix] = false simulates a GPS dropout: lat/lon/speed/accuracy
     * are null but [altitudeM] still flows through as
     * [RideSensorSample.altitudeFromBarometerM], matching how
     * `AndroidRideSensorDataSource` keeps emitting barometer-only samples
     * when GPS is unavailable.
     */
    fun movingSample(
        stepIndex: Int,
        nowMs: Long,
        speedKmh: Double = 20.0,
        accuracyM: Float = 5f,
        satelliteCount: Int = 8,
        bearingDegrees: Float = 0f,
        altitudeM: Double = 100.0,
        includeGpsFix: Boolean = true,
    ): RideSensorSample {
        val speedMps = speedKmh / 3.6
        val deltaLatDeg = (speedMps * stepIndex) / METERS_PER_DEGREE_LATITUDE
        return RideSensorSample(
            timestampMs = nowMs,
            latitude = if (includeGpsFix) START_LATITUDE + deltaLatDeg else null,
            longitude = if (includeGpsFix) START_LONGITUDE else null,
            altitudeFromGpsM = if (includeGpsFix) altitudeM else null,
            altitudeFromBarometerM = altitudeM,
            speedFromGpsMps = if (includeGpsFix) speedMps else null,
            accuracyM = if (includeGpsFix) accuracyM else null,
            bearingDegrees = if (includeGpsFix) bearingDegrees else null,
            satelliteCount = if (includeGpsFix) satelliteCount else null,
        )
    }

    /** A stationary GPS fix (0 speed) at the ride's start position. */
    fun stationarySample(
        nowMs: Long,
        accuracyM: Float = 5f,
        satelliteCount: Int = 8,
    ): RideSensorSample =
        RideSensorSample(
            timestampMs = nowMs,
            latitude = START_LATITUDE,
            longitude = START_LONGITUDE,
            altitudeFromGpsM = 100.0,
            altitudeFromBarometerM = 100.0,
            speedFromGpsMps = 0.0,
            accuracyM = accuracyM,
            bearingDegrees = 0f,
            satelliteCount = satelliteCount,
        )
}
