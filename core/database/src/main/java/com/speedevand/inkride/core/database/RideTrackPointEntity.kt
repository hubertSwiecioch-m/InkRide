package com.speedevand.inkride.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ride_track_point",
    foreignKeys = [
        ForeignKey(
            entity = RideHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rideId")],
)
data class RideTrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val accuracyM: Float?,
)
