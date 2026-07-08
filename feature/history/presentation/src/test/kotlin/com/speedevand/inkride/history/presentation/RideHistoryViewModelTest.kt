package com.speedevand.inkride.history.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.domain.tracking.LapRecord
import com.speedevand.inkride.core.presentation.toUiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideHistoryViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val rideRepo = FakeRideHistoryRepository()
    private val settingsRepo = FakeUserSettingsRepository()
    private val trackPointRepo = FakeTrackPointRepository()
    private val lapRepo = FakeLapRepository()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = RideHistoryViewModel(rideRepo, settingsRepo, trackPointRepo, lapRepo)

    @Test
    fun `initial state shows loading then becomes false after flow emits`() =
        runTest {
            val viewModel = viewModel()
            // After combine emits (immediate with UnconfinedTestDispatcher), loading becomes false
            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
            assertThat(viewModel.state.value.rides).isEqualTo(emptyList())
        }

    @Test
    fun `combine flows produces list of UI models`() =
        runTest {
            val ride =
                RideRecord(
                    id = 1L,
                    startTimestamp = 0L,
                    endTimestamp = 1000L,
                    distanceKm = 10.0,
                    movingTimeSeconds = 600L,
                    elapsedTimeSeconds = 1200L,
                    averageSpeedKmh = 20.0,
                    maxSpeedKmh = 30.0,
                    elevationGainM = 50.0,
                    caloriesKcal = 200.0,
                )
            rideRepo.emitRides(listOf(ride))

            val viewModel = viewModel()

            assertThat(viewModel.state.value.rides.size).isEqualTo(1)
            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
        }

    @Test
    fun `empty rides produces empty list`() =
        runTest {
            rideRepo.emitRides(emptyList())
            val viewModel = viewModel()

            assertThat(viewModel.state.value.rides).isEqualTo(emptyList())
            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
        }

    @Test
    fun `ride click sends NavigateToDetail event`() =
        runTest {
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnRideClick(42L))
                val event = awaitItem()
                assertThat(event).isEqualTo(RideHistoryEvent.NavigateToDetail(42L))
            }
        }

    @Test
    fun `delete ride sends undo snackbar`() =
        runTest {
            val ride =
                RideRecord(
                    id = 1L,
                    startTimestamp = 0L,
                    endTimestamp = 1000L,
                    distanceKm = 10.0,
                    movingTimeSeconds = 600L,
                    elapsedTimeSeconds = 1200L,
                    averageSpeedKmh = 20.0,
                    maxSpeedKmh = 30.0,
                    elevationGainM = 50.0,
                    caloriesKcal = 200.0,
                )
            rideRepo.rides.add(ride)

            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
                val event = awaitItem()
                assertThat(event).isEqualTo(RideHistoryEvent.ShowUndoSnackbar)
            }
        }

    @Test
    fun `undo restores recently deleted ride`() =
        runTest {
            val ride =
                RideRecord(
                    id = 1L,
                    startTimestamp = 0L,
                    endTimestamp = 1000L,
                    distanceKm = 10.0,
                    movingTimeSeconds = 600L,
                    elapsedTimeSeconds = 1200L,
                    averageSpeedKmh = 20.0,
                    maxSpeedKmh = 30.0,
                    elevationGainM = 50.0,
                    caloriesKcal = 200.0,
                )
            rideRepo.rides.add(ride)

            val viewModel = viewModel()

            // Delete the ride
            viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
            assertThat(rideRepo.rides.size).isEqualTo(0)

            // Undo
            viewModel.onAction(RideHistoryAction.OnUndoDelete)
            assertThat(rideRepo.rides.size).isEqualTo(1)
        }

    @Test
    fun `undo restores track points and laps that were deleted`() =
        runTest {
            val ride =
                RideRecord(
                    id = 1L,
                    startTimestamp = 0L,
                    endTimestamp = 1000L,
                    distanceKm = 10.0,
                    movingTimeSeconds = 600L,
                    elapsedTimeSeconds = 1200L,
                    averageSpeedKmh = 20.0,
                    maxSpeedKmh = 30.0,
                    elevationGainM = 50.0,
                    caloriesKcal = 200.0,
                )
            rideRepo.rides.add(ride)
            val points = listOf(RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0))
            val laps =
                listOf(LapRecord(lapNumber = 1, distanceKm = 5.0, movingTimeSeconds = 300L, averageSpeedKmh = 20.0, elevationGainM = 10.0))
            trackPointRepo.saved[1L] = points
            lapRepo.saved[1L] = laps

            val viewModel = viewModel()

            viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
            // A real cascade delete would also wipe these; the fake doesn't
            // cascade, so simulate it explicitly to prove restore happens
            // from the *cached* copy, not a re-read after delete.
            trackPointRepo.saved.remove(1L)
            lapRepo.saved.remove(1L)

            viewModel.onAction(RideHistoryAction.OnUndoDelete)

            val restoredRideId = rideRepo.rides.first().id
            assertThat(trackPointRepo.saved[restoredRideId]).isEqualTo(points)
            assertThat(lapRepo.saved[restoredRideId]).isEqualTo(laps)
        }

    @Test
    fun `delete all clears all rides`() =
        runTest {
            repeat(3) { i ->
                rideRepo.rides.add(
                    RideRecord(
                        id = i.toLong(),
                        startTimestamp = 0L,
                        endTimestamp = 1000L,
                        distanceKm = 10.0,
                        movingTimeSeconds = 600L,
                        elapsedTimeSeconds = 1200L,
                        averageSpeedKmh = 20.0,
                        maxSpeedKmh = 30.0,
                        elevationGainM = 50.0,
                        caloriesKcal = 200.0,
                    ),
                )
            }

            val viewModel = viewModel()
            viewModel.onAction(RideHistoryAction.OnDeleteAll)
            assertThat(rideRepo.rides.size).isEqualTo(0)
        }

    @Test
    fun `delete ride with getPoints failure sends error event`() =
        runTest {
            val ride =
                RideRecord(
                    id = 1L,
                    startTimestamp = 0L,
                    endTimestamp = 1000L,
                    distanceKm = 10.0,
                    movingTimeSeconds = 600L,
                    elapsedTimeSeconds = 1200L,
                    averageSpeedKmh = 20.0,
                    maxSpeedKmh = 30.0,
                    elevationGainM = 50.0,
                    caloriesKcal = 200.0,
                )
            rideRepo.rides.add(ride)
            trackPointRepo.getPointsError = DataError.Local.UNKNOWN

            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
                val errorEvent = awaitItem()
                assertThat(errorEvent).isInstanceOf<RideHistoryEvent.ShowError>()
                val undoEvent = awaitItem()
                assertThat(undoEvent).isEqualTo(RideHistoryEvent.ShowUndoSnackbar)
                // Verify ride was still deleted despite error
                assertThat(rideRepo.rides.size).isEqualTo(0)
            }
        }

    @Test
    fun `delete ride with getLaps failure sends error event`() =
        runTest {
            val ride =
                RideRecord(
                    id = 1L,
                    startTimestamp = 0L,
                    endTimestamp = 1000L,
                    distanceKm = 10.0,
                    movingTimeSeconds = 600L,
                    elapsedTimeSeconds = 1200L,
                    averageSpeedKmh = 20.0,
                    maxSpeedKmh = 30.0,
                    elevationGainM = 50.0,
                    caloriesKcal = 200.0,
                )
            rideRepo.rides.add(ride)
            lapRepo.getLapsError = DataError.Local.UNKNOWN

            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
                val errorEvent = awaitItem()
                assertThat(errorEvent).isInstanceOf<RideHistoryEvent.ShowError>()
                val undoEvent = awaitItem()
                assertThat(undoEvent).isEqualTo(RideHistoryEvent.ShowUndoSnackbar)
                // Verify ride was still deleted despite error
                assertThat(rideRepo.rides.size).isEqualTo(0)
            }
        }

    @Test
    fun `undo delete with savePoints failure sends error event`() =
        runTest {
            val ride =
                RideRecord(
                    id = 1L,
                    startTimestamp = 0L,
                    endTimestamp = 1000L,
                    distanceKm = 10.0,
                    movingTimeSeconds = 600L,
                    elapsedTimeSeconds = 1200L,
                    averageSpeedKmh = 20.0,
                    maxSpeedKmh = 30.0,
                    elevationGainM = 50.0,
                    caloriesKcal = 200.0,
                )
            rideRepo.rides.add(ride)
            val points = listOf(RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0))
            trackPointRepo.saved[1L] = points

            val viewModel = viewModel()

            // Delete the ride (consume events from delete)
            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
                awaitItem() // ShowUndoSnackbar
            }

            // Set up save error for undo
            trackPointRepo.savePointsError = DataError.Local.UNKNOWN

            // Undo and check for error
            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnUndoDelete)
                val errorEvent = awaitItem()
                assertThat(errorEvent).isInstanceOf<RideHistoryEvent.ShowError>()
            }

            // Verify ride was still restored despite savePoints failure
            assertThat(rideRepo.rides.size).isEqualTo(1)
        }

    @Test
    fun `undo delete with saveLaps failure sends error event`() =
        runTest {
            val ride =
                RideRecord(
                    id = 1L,
                    startTimestamp = 0L,
                    endTimestamp = 1000L,
                    distanceKm = 10.0,
                    movingTimeSeconds = 600L,
                    elapsedTimeSeconds = 1200L,
                    averageSpeedKmh = 20.0,
                    maxSpeedKmh = 30.0,
                    elevationGainM = 50.0,
                    caloriesKcal = 200.0,
                )
            rideRepo.rides.add(ride)
            val laps =
                listOf(LapRecord(lapNumber = 1, distanceKm = 5.0, movingTimeSeconds = 300L, averageSpeedKmh = 20.0, elevationGainM = 10.0))
            lapRepo.saved[1L] = laps

            val viewModel = viewModel()

            // Delete the ride (consume events from delete)
            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
                awaitItem() // ShowUndoSnackbar
            }

            // Set up save error for undo
            lapRepo.saveLapsError = DataError.Local.UNKNOWN

            // Undo and check for error
            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnUndoDelete)
                val errorEvent = awaitItem()
                assertThat(errorEvent).isInstanceOf<RideHistoryEvent.ShowError>()
            }

            // Verify ride was still restored despite saveLaps failure
            assertThat(rideRepo.rides.size).isEqualTo(1)
        }

    class FakeRideHistoryRepository : RideHistoryRepository {
        val rides = mutableListOf<RideRecord>()

        fun emitRides(list: List<RideRecord>) {
            rides.clear()
            rides.addAll(list)
        }

        override fun observeAll(): Flow<List<RideRecord>> = flowOf(rides.toList())

        override suspend fun getById(id: Long): Result<RideRecord, DataError.Local> =
            rides.find { it.id == id }?.let { Result.Success(it) }
                ?: Result.Error(DataError.Local.NOT_FOUND)

        override suspend fun save(ride: RideRecord) =
            Result.Success(ride.id).also {
                rides.removeAll { it.id == ride.id }
                rides.add(ride)
            }

        override suspend fun deleteById(id: Long) = Result.Success(Unit).also { rides.removeAll { it.id == id } }

        override suspend fun deleteAll() = Result.Success(Unit).also { rides.clear() }
    }

    class FakeUserSettingsRepository : UserSettingsRepository {
        override fun observeSettings(): Flow<UserSettings> = flowOf(UserSettings(weightKg = 75, age = 30))

        override suspend fun save(settings: UserSettings) = Result.Success(Unit)
    }

    class FakeTrackPointRepository : RideTrackPointRepository {
        val saved = mutableMapOf<Long, List<RideTrackPoint>>()
        var getPointsError: DataError.Local? = null
        var savePointsError: DataError.Local? = null

        override suspend fun savePoints(
            rideId: Long,
            points: List<RideTrackPoint>,
        ): EmptyResult<DataError.Local> =
            savePointsError?.let { Result.Error(it) } ?: run {
                saved[rideId] = points
                Result.Success(Unit)
            }

        override suspend fun getPoints(rideId: Long): Result<List<RideTrackPoint>, DataError.Local> =
            getPointsError?.let { Result.Error(it) }
                ?: Result.Success(saved[rideId] ?: emptyList())
    }

    class FakeLapRepository : RideLapRepository {
        val saved = mutableMapOf<Long, List<LapRecord>>()
        var getLapsError: DataError.Local? = null
        var saveLapsError: DataError.Local? = null

        override suspend fun saveLaps(
            rideId: Long,
            laps: List<LapRecord>,
        ): EmptyResult<DataError.Local> =
            saveLapsError?.let { Result.Error(it) } ?: run {
                saved[rideId] = laps
                Result.Success(Unit)
            }

        override suspend fun getLaps(rideId: Long): Result<List<LapRecord>, DataError.Local> =
            getLapsError?.let { Result.Error(it) }
                ?: Result.Success(saved[rideId] ?: emptyList())
    }
}
