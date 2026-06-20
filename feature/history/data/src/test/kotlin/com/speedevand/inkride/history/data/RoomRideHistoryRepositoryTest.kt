package com.speedevand.inkride.history.data

import android.database.sqlite.SQLiteFullException
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.speedevand.inkride.core.database.RideHistoryDao
import com.speedevand.inkride.core.database.RideHistoryEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomRideHistoryRepositoryTest {
    private val dao = FakeRideHistoryDao()
    private val repository = RoomRideHistoryRepository(dao)

    @Test
    fun `observeAll maps entities to domain records`() =
        runTest {
            dao.setEntities(
                listOf(
                    RideHistoryEntity(
                        id = 1,
                        startTimestamp = 1000L,
                        endTimestamp = 2000L,
                        distanceKm = 10.0,
                        movingTimeSeconds = 600L,
                        elapsedTimeSeconds = 1000L,
                        averageSpeedKmh = 20.0,
                        maxSpeedKmh = 30.0,
                        elevationGainM = 50.0,
                        caloriesKcal = 200.0,
                        averagePowerWatts = 120,
                        bikeWeightKg = 10.0,
                        bikeType = "ROAD",
                    ),
                ),
            )

            val records = repository.observeAll().first()
            assertThat(records.size).isEqualTo(1)
            assertThat(records[0].id).isEqualTo(1L)
            assertThat(records[0].distanceKm).isEqualTo(10.0)
        }

    @Test
    fun `observeAll returns empty list for no entities`() =
        runTest {
            dao.setEntities(emptyList())

            val records = repository.observeAll().first()
            assertThat(records).isEqualTo(emptyList())
        }

    @Test
    fun `getById found returns Success with mapped record`() =
        runTest {
            dao.setEntities(
                listOf(
                    RideHistoryEntity(
                        id = 1,
                        startTimestamp = 1000L,
                        endTimestamp = 2000L,
                        distanceKm = 10.0,
                        movingTimeSeconds = 600L,
                        elapsedTimeSeconds = 1000L,
                        averageSpeedKmh = 20.0,
                        maxSpeedKmh = 30.0,
                        elevationGainM = 50.0,
                        caloriesKcal = 200.0,
                        averagePowerWatts = 120,
                        bikeWeightKg = 10.0,
                        bikeType = "ROAD",
                    ),
                ),
            )

            val result = repository.getById(1L)
            assertThat(result).isInstanceOf<Result.Success<RideRecord>>()
        }

    @Test
    fun `getById not found returns Error`() =
        runTest {
            val result = repository.getById(99L)
            assertThat(result).isInstanceOf<Result.Error<DataError.Local>>()
        }

    @Test
    fun `save success returns Success`() =
        runTest {
            val ride =
                RideRecord(
                    id = 1L,
                    startTimestamp = 1000L,
                    endTimestamp = 2000L,
                    distanceKm = 10.0,
                    movingTimeSeconds = 600L,
                    elapsedTimeSeconds = 1000L,
                    averageSpeedKmh = 20.0,
                    maxSpeedKmh = 30.0,
                    elevationGainM = 50.0,
                    caloriesKcal = 200.0,
                )
            val result = repository.save(ride)
            assertThat(result).isInstanceOf<Result.Success<Long>>()
        }

    @Test
    fun `save DISK_FULL returns Error`() =
        runTest {
            dao.insertException = SQLiteFullException()
            val ride =
                RideRecord(
                    id = 1L,
                    startTimestamp = 1000L,
                    endTimestamp = 2000L,
                    distanceKm = 10.0,
                    movingTimeSeconds = 600L,
                    elapsedTimeSeconds = 1000L,
                    averageSpeedKmh = 20.0,
                    maxSpeedKmh = 30.0,
                    elevationGainM = 50.0,
                    caloriesKcal = 200.0,
                )
            val result = repository.save(ride)
            assertThat((result as Result.Error).error).isEqualTo(DataError.Local.DISK_FULL)
        }

    @Test
    fun `deleteById returns Success`() =
        runTest {
            val result = repository.deleteById(1L)
            assertThat(result).isInstanceOf<Result.Success<Unit>>()
        }

    @Test
    fun `deleteAll returns Success`() =
        runTest {
            val result = repository.deleteAll()
            assertThat(result).isInstanceOf<Result.Success<Unit>>()
        }

    class FakeRideHistoryDao : RideHistoryDao {
        private val entitiesFlow = MutableStateFlow<List<RideHistoryEntity>>(emptyList())
        var insertException: Exception? = null

        override fun observeAll(): Flow<List<RideHistoryEntity>> = entitiesFlow

        override suspend fun getById(id: Long): RideHistoryEntity? = entitiesFlow.value.find { it.id == id }

        override fun observeLifetimeStats(): Flow<com.speedevand.inkride.core.database.LifetimeStatsAggregate> =
            kotlinx.coroutines.flow.flowOf(
                com.speedevand.inkride.core.database.LifetimeStatsAggregate(
                    totalRides = entitiesFlow.value.size,
                    totalDistanceKm = entitiesFlow.value.sumOf { it.distanceKm },
                    totalMovingTimeSeconds = entitiesFlow.value.sumOf { it.movingTimeSeconds },
                    totalElevationGainM = entitiesFlow.value.sumOf { it.elevationGainM },
                    maxSpeedKmh = entitiesFlow.value.maxOfOrNull { it.maxSpeedKmh } ?: 0.0,
                    totalCaloriesKcal = entitiesFlow.value.sumOf { it.caloriesKcal },
                ),
            )

        override suspend fun insert(ride: RideHistoryEntity): Long {
            insertException?.let { throw it }
            entitiesFlow.value = entitiesFlow.value + ride
            return ride.id
        }

        override suspend fun deleteById(id: Long) {
            entitiesFlow.value = entitiesFlow.value.filter { it.id != id }
        }

        override suspend fun deleteAll() {
            entitiesFlow.value = emptyList()
        }

        fun setEntities(entities: List<RideHistoryEntity>) {
            entitiesFlow.value = entities
        }
    }
}
