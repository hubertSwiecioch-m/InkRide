package com.speedevand.inkride.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BikeProfileDao {
    @Query("SELECT * FROM bike_profile ORDER BY id ASC")
    fun observeAll(): Flow<List<BikeProfileEntity>>

    // REPLACE so passing a non-zero id updates the existing row in place.
    // Returns the row id (new or updated) so callers can set it active.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: BikeProfileEntity): Long

    @Query("DELETE FROM bike_profile WHERE id = :id")
    suspend fun deleteById(id: Long)
}
