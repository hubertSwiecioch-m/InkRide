package com.speedevand.inkride.core.domain.settings

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for the rider's bike profiles. [upsert] returns the row id (new or
 * existing) so a freshly created profile can be made active.
 */
interface BikeProfileRepository {
    fun observeProfiles(): Flow<List<BikeProfile>>

    suspend fun upsert(profile: BikeProfile): Result<Long, DataError.Local>

    suspend fun delete(id: Long): EmptyResult<DataError.Local>
}
