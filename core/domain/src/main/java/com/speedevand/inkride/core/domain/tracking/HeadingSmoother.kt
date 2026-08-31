package com.speedevand.inkride.core.domain.tracking

/**
 * Result of feeding one raw orientation reading into [HeadingSmoother].
 * [smoothedHeadingDeg] is always the latest smoothed true-north heading;
 * [shouldEmit] is true only when it has moved far enough since the last
 * emitted value to be worth publishing.
 */
data class HeadingUpdate(
    val smoothedHeadingDeg: Float,
    val shouldEmit: Boolean,
)

/**
 * Smooths raw magnetometer-derived headings with a circular EMA filter,
 * corrects to true north using magnetic declination, and throttles emission
 * to [emitThresholdDeg] steps so downstream consumers (e.g. an E-Ink
 * compass) aren't flooded with micro-changes.
 */
class HeadingSmoother(
    private val smoothingAlpha: Float = 0.2f,
    private val emitThresholdDeg: Float = 2.0f,
) {
    private var smoothedHeadingDeg: Float? = null
    private var lastEmittedHeadingDeg: Float? = null

    /**
     * Feeds a new raw magnetic azimuth (degrees) and declination (degrees to
     * add to reach true north).
     */
    fun update(
        magneticAzimuthDeg: Float,
        declinationDeg: Float,
    ): HeadingUpdate {
        val magneticHeading = (magneticAzimuthDeg + 360f) % 360f
        val trueHeading = (magneticHeading + declinationDeg + 360f) % 360f

        // Circular EMA: blend along the shortest arc so the filter doesn't
        // lurch the long way around the 0/360 wrap point.
        val smoothed =
            smoothedHeadingDeg?.let { prev ->
                val delta = angularDifference(prev, trueHeading)
                (prev + smoothingAlpha * delta + 360f) % 360f
            } ?: trueHeading
        smoothedHeadingDeg = smoothed

        val emitted = lastEmittedHeadingDeg
        val shouldEmit = emitted == null || Math.abs(angularDifference(emitted, smoothed)) >= emitThresholdDeg
        if (shouldEmit) lastEmittedHeadingDeg = smoothed

        return HeadingUpdate(smoothed, shouldEmit)
    }

    /** Resets all smoothing/throttling state, e.g. when tracking stops. */
    fun reset() {
        smoothedHeadingDeg = null
        lastEmittedHeadingDeg = null
    }

    /**
     * Shortest signed angular difference from [from] to [to], in degrees,
     * within (-180, 180]. Used so circular EMA and threshold checks move
     * along the short arc across the 0/360 wrap point.
     */
    private fun angularDifference(
        from: Float,
        to: Float,
    ): Float {
        var diff = (to - from + 540f) % 360f - 180f
        if (diff == -180f) diff = 180f
        return diff
    }
}
