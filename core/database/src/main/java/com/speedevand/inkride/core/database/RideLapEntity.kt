package com.speedevand.inkride.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ride_lap",
    foreignKeys = [
        ForeignKey(
            entity = RideHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("rideId")]
)
data class RideLapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val lapNumber: Int,
    val distanceKm: Double,
    val movingTimeSeconds: Long,
    val averageSpeedKmh: Double,
    val elevationGainM: Double
)
