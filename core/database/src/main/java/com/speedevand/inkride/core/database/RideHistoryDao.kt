package com.speedevand.inkride.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RideHistoryDao {
    @Query("SELECT * FROM ride_history ORDER BY startTimestamp DESC")
    fun observeAll(): Flow<List<RideHistoryEntity>>

    @Query("SELECT * FROM ride_history WHERE id = :id")
    suspend fun getById(id: Long): RideHistoryEntity?

    @Query(
        """
        SELECT
            COUNT(*) AS totalRides,
            COALESCE(SUM(distanceKm), 0) AS totalDistanceKm,
            COALESCE(SUM(movingTimeSeconds), 0) AS totalMovingTimeSeconds,
            COALESCE(SUM(elevationGainM), 0) AS totalElevationGainM,
            COALESCE(MAX(maxSpeedKmh), 0) AS maxSpeedKmh,
            COALESCE(SUM(caloriesKcal), 0) AS totalCaloriesKcal
        FROM ride_history
        """,
    )
    fun observeLifetimeStats(): Flow<LifetimeStatsAggregate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ride: RideHistoryEntity): Long

    @Query("DELETE FROM ride_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM ride_history")
    suspend fun deleteAll()
}
