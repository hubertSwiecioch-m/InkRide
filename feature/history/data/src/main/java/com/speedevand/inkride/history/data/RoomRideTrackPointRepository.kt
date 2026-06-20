package com.speedevand.inkride.history.data

import android.database.sqlite.SQLiteFullException
import com.speedevand.inkride.core.database.RideTrackPointDao
import com.speedevand.inkride.core.database.RideTrackPointEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository

class RoomRideTrackPointRepository(
    private val dao: RideTrackPointDao,
) : RideTrackPointRepository {
    override suspend fun savePoints(
        rideId: Long,
        points: List<RideTrackPoint>,
    ): EmptyResult<DataError.Local> =
        try {
            dao.insertAll(points.map { it.toEntity(rideId) })
            Result.Success(Unit)
        } catch (e: SQLiteFullException) {
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun getPoints(rideId: Long): Result<List<RideTrackPoint>, DataError.Local> =
        try {
            Result.Success(dao.getForRide(rideId).map { it.toDomain() })
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }
}

private fun RideTrackPoint.toEntity(rideId: Long) =
    RideTrackPointEntity(
        rideId = rideId,
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitudeM = altitudeM,
        accuracyM = accuracyM,
    )

private fun RideTrackPointEntity.toDomain() =
    RideTrackPoint(
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitudeM = altitudeM,
        accuracyM = accuracyM,
    )
