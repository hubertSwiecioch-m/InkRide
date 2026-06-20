package com.speedevand.inkride.core.database

/** Row shape returned by [RideHistoryDao.observeLifetimeStats]. */
data class LifetimeStatsAggregate(
    val totalRides: Int,
    val totalDistanceKm: Double,
    val totalMovingTimeSeconds: Long,
    val totalElevationGainM: Double,
    val maxSpeedKmh: Double,
    val totalCaloriesKcal: Double,
)
