package com.speedevand.inkride.core.domain.tracking

import kotlin.collections.ArrayDeque

/**
 * Classifies the short-term local weather trend from a rolling window of
 * barometric pressure readings.
 *
 * The barometer is already sampled for altitude; reusing the raw pressure gives
 * a free, offline weather hint. A least-squares slope (hPa/hour) over the last
 * [windowMs] is compared against [thresholdHpaPerHour]: a sustained rise means
 * improving weather, a sustained fall means worsening weather, and anything in
 * between (or too little history) reads as stable / unknown.
 *
 * Not thread-safe: [add] and [trend] are called from the single sample-
 * processing path in [RideMetricsCalculator].
 */
class WeatherTrendCalculator(
    private val windowMs: Long = 60 * 60 * 1000L,
    // Below this slope the pressure is treated as steady. ~0.6 hPa/h is around
    // the meteorological "steady" boundary and filters out sensor wobble.
    private val thresholdHpaPerHour: Double = 0.6,
    // Need at least this much spread before a trend is meaningful, otherwise a
    // couple of early noisy readings would swing the classification.
    private val minSpanMs: Long = 15 * 60 * 1000L,
    // Pressure arrives on every sensor sample (several Hz); for an hourly trend
    // one reading every [minSampleIntervalMs] is plenty. Downsampling keeps the
    // window to a few hundred entries instead of thousands, so the per-sample
    // least-squares stays cheap.
    private val minSampleIntervalMs: Long = 10_000L,
) {
    // (timestampMs, pressureHpa), oldest first.
    private val readings = ArrayDeque<Pair<Long, Double>>()
    private var lastAddedMs: Long = Long.MIN_VALUE

    fun reset() {
        readings.clear()
        lastAddedMs = Long.MIN_VALUE
    }

    /**
     * Adds a pressure reading (downsampled to one per [minSampleIntervalMs]) and
     * drops anything older than [windowMs].
     */
    fun add(
        timestampMs: Long,
        pressureHpa: Double,
    ) {
        // Guard the sentinel explicitly so the first reading is always taken
        // (timestampMs - Long.MIN_VALUE would overflow).
        if (lastAddedMs != Long.MIN_VALUE && timestampMs - lastAddedMs < minSampleIntervalMs) return
        lastAddedMs = timestampMs
        readings.addLast(timestampMs to pressureHpa)
        val cutoff = timestampMs - windowMs
        while (readings.isNotEmpty() && readings.first().first < cutoff) {
            readings.removeFirst()
        }
    }

    /** Current trend, or [WeatherTrend.UNKNOWN] until enough history accrues. */
    fun trend(): WeatherTrend {
        if (readings.size < 2) return WeatherTrend.UNKNOWN
        val span = readings.last().first - readings.first().first
        if (span < minSpanMs) return WeatherTrend.UNKNOWN

        val slopeHpaPerHour = leastSquaresSlopePerHour() ?: return WeatherTrend.UNKNOWN
        return when {
            slopeHpaPerHour > thresholdHpaPerHour -> WeatherTrend.RISING
            slopeHpaPerHour < -thresholdHpaPerHour -> WeatherTrend.FALLING
            else -> WeatherTrend.STABLE
        }
    }

    /**
     * Ordinary least-squares slope of pressure vs. time, expressed in hPa/hour.
     * Time is measured in hours from the first reading to keep the numbers well
     * conditioned. Returns null if the timestamps carry no variance.
     */
    private fun leastSquaresSlopePerHour(): Double? {
        val t0 = readings.first().first
        val n = readings.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        for ((timestamp, pressure) in readings) {
            val x = (timestamp - t0) / 3_600_000.0 // hours
            sumX += x
            sumY += pressure
            sumXY += x * pressure
            sumXX += x * x
        }
        val denominator = n * sumXX - sumX * sumX
        if (denominator == 0.0) return null
        return (n * sumXY - sumX * sumY) / denominator
    }
}
