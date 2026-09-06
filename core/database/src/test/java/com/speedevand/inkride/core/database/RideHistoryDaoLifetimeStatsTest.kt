package com.speedevand.inkride.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [RideHistoryDao.observeLifetimeStats]'s raw SQL aggregate query
 * against a real (Robolectric-backed) Room database, since its COALESCE/SUM/
 * MAX behavior can't be exercised by a hand-written fake DAO.
 */
@RunWith(RobolectricTestRunner::class)
class RideHistoryDaoLifetimeStatsTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: RideHistoryDao

    @Before
    fun createDatabase() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
                .build()
        dao = database.rideHistoryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun ride(
        distanceKm: Double,
        movingTimeSeconds: Long,
        elevationGainM: Double,
        maxSpeedKmh: Double,
        caloriesKcal: Double,
    ) = RideHistoryEntity(
        startTimestamp = 0L,
        endTimestamp = 0L,
        distanceKm = distanceKm,
        movingTimeSeconds = movingTimeSeconds,
        elapsedTimeSeconds = movingTimeSeconds,
        averageSpeedKmh = 0.0,
        maxSpeedKmh = maxSpeedKmh,
        elevationGainM = elevationGainM,
        caloriesKcal = caloriesKcal,
    )

    @Test
    fun `emits a zero aggregate for an empty table`() =
        runTest {
            val stats = dao.observeLifetimeStats().first()

            assertThat(stats.totalRides).isEqualTo(0)
            assertThat(stats.totalDistanceKm).isEqualTo(0.0)
            assertThat(stats.totalMovingTimeSeconds).isEqualTo(0L)
            assertThat(stats.totalElevationGainM).isEqualTo(0.0)
            assertThat(stats.maxSpeedKmh).isEqualTo(0.0)
            assertThat(stats.totalCaloriesKcal).isEqualTo(0.0)
        }

    @Test
    fun `reflects a single inserted ride`() =
        runTest {
            dao.insert(
                ride(
                    distanceKm = 20.0,
                    movingTimeSeconds = 3_000L,
                    elevationGainM = 150.0,
                    maxSpeedKmh = 40.0,
                    caloriesKcal = 500.0,
                ),
            )

            val stats = dao.observeLifetimeStats().first()

            assertThat(stats.totalRides).isEqualTo(1)
            assertThat(stats.totalDistanceKm).isEqualTo(20.0)
            assertThat(stats.totalMovingTimeSeconds).isEqualTo(3_000L)
            assertThat(stats.totalElevationGainM).isEqualTo(150.0)
            assertThat(stats.maxSpeedKmh).isEqualTo(40.0)
            assertThat(stats.totalCaloriesKcal).isEqualTo(500.0)
        }

    @Test
    fun `sums across multiple rides and picks the true maximum speed`() =
        runTest {
            dao.insert(
                ride(distanceKm = 10.0, movingTimeSeconds = 1_000L, elevationGainM = 50.0, maxSpeedKmh = 30.0, caloriesKcal = 200.0),
            )
            dao.insert(
                ride(distanceKm = 15.0, movingTimeSeconds = 1_500L, elevationGainM = 80.0, maxSpeedKmh = 45.0, caloriesKcal = 300.0),
            )
            dao.insert(
                ride(distanceKm = 5.0, movingTimeSeconds = 500L, elevationGainM = 20.0, maxSpeedKmh = 25.0, caloriesKcal = 100.0),
            )

            val stats = dao.observeLifetimeStats().first()

            assertThat(stats.totalRides).isEqualTo(3)
            assertThat(stats.totalDistanceKm).isEqualTo(30.0)
            assertThat(stats.totalMovingTimeSeconds).isEqualTo(3_000L)
            assertThat(stats.totalElevationGainM).isEqualTo(150.0)
            // The highest of the three, not the first or last inserted.
            assertThat(stats.maxSpeedKmh).isEqualTo(45.0)
            assertThat(stats.totalCaloriesKcal).isEqualTo(600.0)
        }

    @Test
    fun `emits an updated aggregate reactively when a new ride is inserted`() =
        runTest {
            dao.observeLifetimeStats().test {
                val initial = awaitItem()
                assertThat(initial.totalRides).isEqualTo(0)

                dao.insert(
                    ride(
                        distanceKm = 20.0,
                        movingTimeSeconds = 3_000L,
                        elevationGainM = 150.0,
                        maxSpeedKmh = 40.0,
                        caloriesKcal = 500.0,
                    ),
                )

                val updated = awaitItem()
                assertThat(updated.totalRides).isEqualTo(1)
                assertThat(updated.totalDistanceKm).isEqualTo(20.0)

                cancelAndIgnoreRemainingEvents()
            }
        }
}
