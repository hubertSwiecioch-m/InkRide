package com.speedevand.inkride.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RideLapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(laps: List<RideLapEntity>)

    @Query("SELECT * FROM ride_lap WHERE rideId = :rideId ORDER BY lapNumber ASC")
    suspend fun getForRide(rideId: Long): List<RideLapEntity>
}
