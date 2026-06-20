package com.speedevand.inkride.core.domain.history

/** Aggregate totals across every saved ride (the lifetime odometer). */
data class LifetimeStats(
    val totalRides: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val totalMovingTimeSeconds: Long = 0L,
    val totalElevationGainM: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val totalCaloriesKcal: Double = 0.0,
)
