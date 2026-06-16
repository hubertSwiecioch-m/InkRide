package com.speedevand.inkride.core.domain.history

import com.speedevand.inkride.core.domain.settings.BikeType

data class RideRecord(
    val id: Long,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val distanceKm: Double,
    val movingTimeSeconds: Long,
    val elapsedTimeSeconds: Long,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val elevationGainM: Double,
    val caloriesKcal: Double,
    val averagePowerWatts: Int = 0,
    val bikeWeightKg: Double = 10.0,
    val bikeType: BikeType = BikeType.ROAD
)
