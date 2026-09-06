package com.speedevand.inkride.core.domain.tracking

/**
 * A single raw GPS fix, in the exact field types [android.location.Location]'s
 * own getters return, so a caller building one from a real fix does no
 * conversion beyond null-checking `has*()`. Absent (`null`) entirely when the
 * caller has determined the underlying fix is stale or too inaccurate to use.
 */
data class RawGpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val fixTimeMs: Long,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val altitudeM: Double? = null,
    val satelliteCount: Int? = null,
)

/**
 * Fuses one cycle's raw GPS/barometer/heading readings into a
 * [RideSensorSample]. Wraps a [PositionKalmanFilter], feeding it at most once
 * per distinct [RawGpsFix.fixTimeMs] — [assemble] is called far more often
 * than GPS produces new fixes (barometer ~2Hz, heading on every ~2° step),
 * and a fix stays usable for a few seconds after it arrives; without this
 * dedup guard the filter would run a full predict+update cycle multiple
 * times per second against an unchanged position, dragging its velocity
 * estimate toward zero and over-shrinking its covariance between real fixes.
 *
 * Bearing prefers GPS course-over-ground when moving fast enough to trust it
 * over the smoothed magnetometer/rotation-vector heading (which is easily
 * disturbed by a bike frame and the phone's own fields at low speed), falling
 * back to the heading otherwise. Whichever source is chosen is sanitized:
 * non-finite values are dropped, finite ones normalized into `[0, 360)`.
 */
class RideSampleAssembler(
    private val gpsBearingMinSpeedMps: Float = 2.0f,
    private val positionKalmanFilter: PositionKalmanFilter = PositionKalmanFilter(),
) {
    private var lastKalmanFedFixTimeMs: Long? = null
    private var lastKalmanResult: FilteredPosition? = null

    fun assemble(
        rawFix: RawGpsFix?,
        pressureHpa: Double?,
        altitudeFromBarometerM: Double?,
        smoothedHeadingDeg: Float?,
        nowMs: Long,
        gpsTimestampMs: Long,
        pressureTimestampMs: Long,
        headingTimestampMs: Long,
    ): RideSensorSample {
        val filteredPosition = feedKalmanFilter(rawFix)

        val gpsBearing =
            rawFix
                ?.takeIf { it.speedMps != null && it.speedMps >= gpsBearingMinSpeedMps }
                ?.bearingDeg
        val bearing =
            (gpsBearing ?: smoothedHeadingDeg)
                ?.takeIf { it.isFinite() }
                ?.let { ((it % 360f) + 360f) % 360f }

        return RideSensorSample(
            timestampMs = maxOf(gpsTimestampMs, pressureTimestampMs, headingTimestampMs, nowMs),
            latitude = filteredPosition?.latitude,
            longitude = filteredPosition?.longitude,
            altitudeFromGpsM = rawFix?.altitudeM,
            altitudeFromBarometerM = altitudeFromBarometerM,
            // Deliberately sourced from the raw GPS chipset's own Doppler speed
            // (rawFix.speedMps), NOT from positionKalmanFilter's own speed
            // estimate. RideMetricsCalculator's GPS-vs-distance cross-validation
            // (see isCrossValidationFail in RideMetricsCalculator.kt) compares a
            // filtered-position-derived speed against this field specifically
            // because the two are independent signals; if this were fed from the
            // Kalman filter instead, the check would silently become a permanent
            // no-op, comparing a signal against a derivative of itself.
            speedFromGpsMps = rawFix?.speedMps?.toDouble(),
            accuracyM = rawFix?.accuracyM,
            bearingDegrees = bearing,
            satelliteCount = rawFix?.satelliteCount,
            pressureHpa = pressureHpa,
        )
    }

    /** Clears the wrapped Kalman filter and the fix-dedup state, e.g. when tracking stops. */
    fun reset() {
        positionKalmanFilter.reset()
        lastKalmanFedFixTimeMs = null
        lastKalmanResult = null
    }

    private fun feedKalmanFilter(rawFix: RawGpsFix?): FilteredPosition? {
        if (rawFix == null) return null
        return if (rawFix.fixTimeMs != lastKalmanFedFixTimeMs) {
            lastKalmanFedFixTimeMs = rawFix.fixTimeMs
            positionKalmanFilter
                .update(
                    latitude = rawFix.latitude,
                    longitude = rawFix.longitude,
                    accuracyM = rawFix.accuracyM,
                    timestampMs = rawFix.fixTimeMs,
                ).also { lastKalmanResult = it }
        } else {
            lastKalmanResult
        }
    }
}
