package com.speedevand.inkride.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ride_history")
data class RideHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val bikeType: String = "ROAD"
)
