package com.speedevand.inkride.core.domain.tracking

/**
 * One-shot, edge-triggered alert raised by [RideTracker] when a live metric
 * crosses a configured threshold. Consumed by the foreground tracking service
 * to vibrate the device (no sound — E-Ink devices are typically silent).
 *
 * Edge-triggered means one event per *crossing*: the rider isn't buzzed every
 * sample while they stay over the limit, only when they first exceed it (and
 * again after dropping back and re-crossing).
 */
sealed interface RideAlert {
    /** Live speed rose above [AlertConfig.maxSpeedKmh]. */
    data class OverSpeed(val speedKmh: Double) : RideAlert

    /** Heart rate rose above the target zone's upper bound. */
    data class HeartRateHigh(val bpm: Int) : RideAlert

    /** Heart rate dropped below the target zone's lower bound. */
    data class HeartRateLow(val bpm: Int) : RideAlert

    /** Rider strayed beyond the off-route threshold from the loaded route. */
    data class OffRoute(val distanceM: Double) : RideAlert
}
