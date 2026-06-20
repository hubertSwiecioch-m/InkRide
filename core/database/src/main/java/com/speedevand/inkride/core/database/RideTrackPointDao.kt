package com.speedevand.inkride.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RideTrackPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<RideTrackPointEntity>)

    @Query("SELECT * FROM ride_track_point WHERE rideId = :rideId ORDER BY timestampMs ASC")
    suspend fun getForRide(rideId: Long): List<RideTrackPointEntity>
}
