package com.speedevand.inkride.core.domain.tracking

import kotlin.math.abs

/**
 * Rejects physiologically implausible heart-rate readings before they reach
 * alerts/UI: values outside a plausible human range, and single-notification
 * jumps too large to be a real heartbeat change (a corrupted BLE packet)
 * rather than genuine effort. A rejected reading is dropped — the last
 * accepted value is returned instead of clamping or interpolating a guess.
 */
class HeartRateFilter(
    private val minPlausibleBpm: Int = 30,
    private val maxPlausibleBpm: Int = 220,
    // Generous cap on how fast heart rate can genuinely change between two
    // consecutive BLE notifications (~1/s). Real hard-effort surges are well
    // under this; a corrupted single packet reading a wildly different value
    // is not.
    private val maxChangeBpmPerSecond: Double = 60.0,
) {
    private var lastAcceptedBpm: Int? = null
    private var lastAcceptedAtMs: Long? = null

    /**
     * Filters a raw BPM reading (null when no HR sensor is connected).
     * Returns the accepted value, the last accepted value if this reading is
     * rejected, or null if nothing has ever been accepted.
     */
    fun filter(
        rawBpm: Int?,
        timestampMs: Long,
    ): Int? {
        if (rawBpm == null) {
            reset()
            return null
        }
        if (rawBpm < minPlausibleBpm || rawBpm > maxPlausibleBpm) {
            return lastAcceptedBpm
        }
        val prevBpm = lastAcceptedBpm
        val prevAtMs = lastAcceptedAtMs
        if (prevBpm != null && prevAtMs != null) {
            val elapsedSeconds = (timestampMs - prevAtMs).coerceAtLeast(1L) / 1000.0
            val maxChange = maxChangeBpmPerSecond * elapsedSeconds
            if (abs(rawBpm - prevBpm) > maxChange) {
                return prevBpm
            }
        }
        lastAcceptedBpm = rawBpm
        lastAcceptedAtMs = timestampMs
        return rawBpm
    }

    /** Clears accepted-reading state, e.g. when tracking stops or the sensor disconnects. */
    fun reset() {
        lastAcceptedBpm = null
        lastAcceptedAtMs = null
    }
}
