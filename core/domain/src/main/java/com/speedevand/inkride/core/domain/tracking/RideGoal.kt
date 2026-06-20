package com.speedevand.inkride.core.domain.tracking

/**
 * A per-ride target the rider sets before/while riding. Held only in
 * [RideTracker] state for the current ride — never persisted — and cleared when
 * the ride stops.
 */
sealed interface RideGoal {
    data class Distance(val targetKm: Double) : RideGoal
    data class Duration(val targetSeconds: Long) : RideGoal
}
