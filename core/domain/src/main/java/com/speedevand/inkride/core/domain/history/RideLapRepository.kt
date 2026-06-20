package com.speedevand.inkride.core.domain.history

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.LapRecord

/**
 * Persistence for per-ride laps. Laps are written in bulk when a ride is saved
 * and read back for the history detail breakdown. They are tied to a ride row
 * via a foreign key with cascade delete, so removing a ride removes its laps.
 */
interface RideLapRepository {
    suspend fun saveLaps(
        rideId: Long,
        laps: List<LapRecord>,
    ): EmptyResult<DataError.Local>

    suspend fun getLaps(rideId: Long): Result<List<LapRecord>, DataError.Local>
}
