package com.speedevand.inkride.settings.data

import android.database.sqlite.SQLiteFullException
import com.speedevand.inkride.core.database.BikeProfileDao
import com.speedevand.inkride.core.database.BikeProfileEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.BikeProfile
import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import com.speedevand.inkride.core.domain.settings.BikeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomBikeProfileRepository(
    private val dao: BikeProfileDao
) : BikeProfileRepository {

    override fun observeProfiles(): Flow<List<BikeProfile>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun upsert(profile: BikeProfile): Result<Long, DataError.Local> {
        return try {
            Result.Success(dao.upsert(profile.toEntity()))
        } catch (e: SQLiteFullException) {
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }
    }

    override suspend fun delete(id: Long): EmptyResult<DataError.Local> {
        return try {
            dao.deleteById(id)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }
    }
}

private fun BikeProfileEntity.toDomain() = BikeProfile(
    id = id,
    name = name,
    weightKg = weightKg,
    type = try { BikeType.valueOf(type) } catch (e: Exception) { BikeType.ROAD }
)

private fun BikeProfile.toEntity() = BikeProfileEntity(
    id = id,
    name = name,
    weightKg = weightKg,
    type = type.name
)
