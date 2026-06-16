package com.speedevand.inkride.history.data

import android.database.sqlite.SQLiteFullException
import com.speedevand.inkride.core.database.RideLapDao
import com.speedevand.inkride.core.database.RideLapEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.tracking.LapRecord

class RoomRideLapRepository(
    private val dao: RideLapDao
) : RideLapRepository {

    override suspend fun saveLaps(rideId: Long, laps: List<LapRecord>): EmptyResult<DataError.Local> {
        return try {
            dao.insertAll(laps.map { it.toEntity(rideId) })
            Result.Success(Unit)
        } catch (e: SQLiteFullException) {
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }
    }

    override suspend fun getLaps(rideId: Long): Result<List<LapRecord>, DataError.Local> {
        return try {
            Result.Success(dao.getForRide(rideId).map { it.toDomain() })
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }
    }
}

private fun LapRecord.toEntity(rideId: Long) = RideLapEntity(
    rideId = rideId,
    lapNumber = lapNumber,
    distanceKm = distanceKm,
    movingTimeSeconds = movingTimeSeconds,
    averageSpeedKmh = averageSpeedKmh,
    elevationGainM = elevationGainM
)

private fun RideLapEntity.toDomain() = LapRecord(
    lapNumber = lapNumber,
    distanceKm = distanceKm,
    movingTimeSeconds = movingTimeSeconds,
    averageSpeedKmh = averageSpeedKmh,
    elevationGainM = elevationGainM
)
