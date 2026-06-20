package com.speedevand.inkride.core.domain.history

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import kotlinx.coroutines.flow.Flow

interface RideHistoryRepository {
    fun observeAll(): Flow<List<RideRecord>>
    suspend fun getById(id: Long): Result<RideRecord, DataError.Local>
    /** Persists the ride and returns the generated row id on success. */
    suspend fun save(ride: RideRecord): Result<Long, DataError.Local>
    suspend fun deleteById(id: Long): EmptyResult<DataError.Local>
    suspend fun deleteAll(): EmptyResult<DataError.Local>
}
