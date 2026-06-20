package com.speedevand.inkride.core.domain.history

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result

/**
 * Persistence for the per-ride GPS track. Points are written in bulk when a ride
 * is saved and read back for GPX export. Track points are tied to a ride row via
 * a foreign key with cascade delete, so removing a ride also removes its track.
 */
interface RideTrackPointRepository {
    suspend fun savePoints(
        rideId: Long,
        points: List<RideTrackPoint>,
    ): EmptyResult<DataError.Local>

    suspend fun getPoints(rideId: Long): Result<List<RideTrackPoint>, DataError.Local>
}
