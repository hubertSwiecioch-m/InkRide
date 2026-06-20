package com.speedevand.inkride.history.data

import android.database.sqlite.SQLiteFullException
import com.speedevand.inkride.core.database.RideHistoryDao
import com.speedevand.inkride.core.database.RideHistoryEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.BikeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomRideHistoryRepository(
    private val dao: RideHistoryDao,
) : RideHistoryRepository {
    override fun observeAll(): Flow<List<RideRecord>> =
        dao.observeAll().map { list ->
            list.map { it.toRideRecord() }
        }

    override suspend fun getById(id: Long): Result<RideRecord, DataError.Local> {
        val entity = dao.getById(id)
        return if (entity != null) {
            Result.Success(entity.toRideRecord())
        } else {
            Result.Error(DataError.Local.NOT_FOUND)
        }
    }

    override suspend fun save(ride: RideRecord): Result<Long, DataError.Local> =
        try {
            Result.Success(dao.insert(ride.toEntity()))
        } catch (e: SQLiteFullException) {
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun deleteById(id: Long): EmptyResult<DataError.Local> =
        try {
            dao.deleteById(id)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun deleteAll(): EmptyResult<DataError.Local> =
        try {
            dao.deleteAll()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }
}

private fun RideHistoryEntity.toRideRecord() =
    RideRecord(
        id = id,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp,
        distanceKm = distanceKm,
        movingTimeSeconds = movingTimeSeconds,
        elapsedTimeSeconds = elapsedTimeSeconds,
        averageSpeedKmh = averageSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        elevationGainM = elevationGainM,
        caloriesKcal = caloriesKcal,
        averagePowerWatts = averagePowerWatts,
        bikeWeightKg = bikeWeightKg,
        bikeType =
            try {
                BikeType.valueOf(bikeType)
            } catch (e: Exception) {
                BikeType.ROAD
            },
    )

private fun RideRecord.toEntity() =
    RideHistoryEntity(
        id = id,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp,
        distanceKm = distanceKm,
        movingTimeSeconds = movingTimeSeconds,
        elapsedTimeSeconds = elapsedTimeSeconds,
        averageSpeedKmh = averageSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        elevationGainM = elevationGainM,
        caloriesKcal = caloriesKcal,
        averagePowerWatts = averagePowerWatts,
        bikeWeightKg = bikeWeightKg,
        bikeType = bikeType.name,
    )
