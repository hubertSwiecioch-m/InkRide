package com.speedevand.inkride.core.domain.settings

/**
 * Per-rider alert thresholds. Each field is null when that alert is disabled.
 *
 * - [maxSpeedKmh]  — fire when the live speed rises above this (km/h).
 * - [hrZoneMinBpm] — fire when heart rate drops below the target zone (requires
 *   a paired HRM).
 * - [hrZoneMaxBpm] — fire when heart rate rises above the target zone.
 */
data class AlertConfig(
    val maxSpeedKmh: Double? = null,
    val hrZoneMinBpm: Int? = null,
    val hrZoneMaxBpm: Int? = null,
) {
    val hasAny: Boolean
        get() = maxSpeedKmh != null || hrZoneMinBpm != null || hrZoneMaxBpm != null
}
