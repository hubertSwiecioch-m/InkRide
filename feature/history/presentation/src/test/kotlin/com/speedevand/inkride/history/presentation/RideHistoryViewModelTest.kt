package com.speedevand.inkride.history.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
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

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state shows loading then becomes false after flow emits`() = runTest {
        val viewModel = RideHistoryViewModel(rideRepo, settingsRepo)
        // After combine emits (immediate with UnconfinedTestDispatcher), loading becomes false
        assertThat(viewModel.state.value.isLoading).isEqualTo(false)
        assertThat(viewModel.state.value.rides).isEqualTo(emptyList())
    }

    @Test
    fun `combine flows produces list of UI models`() = runTest {
        val ride = RideRecord(
            id = 1L, startTimestamp = 0L, endTimestamp = 1000L,
            distanceKm = 10.0, movingTimeSeconds = 600L, elapsedTimeSeconds = 1200L,
            averageSpeedKmh = 20.0, maxSpeedKmh = 30.0, elevationGainM = 50.0, caloriesKcal = 200.0
        )
        rideRepo.emitRides(listOf(ride))

        val viewModel = RideHistoryViewModel(rideRepo, settingsRepo)

        assertThat(viewModel.state.value.rides.size).isEqualTo(1)
        assertThat(viewModel.state.value.isLoading).isEqualTo(false)
    }

    @Test
    fun `empty rides produces empty list`() = runTest {
        rideRepo.emitRides(emptyList())
        val viewModel = RideHistoryViewModel(rideRepo, settingsRepo)

        assertThat(viewModel.state.value.rides).isEqualTo(emptyList())
        assertThat(viewModel.state.value.isLoading).isEqualTo(false)
    }

    @Test
    fun `ride click sends NavigateToDetail event`() = runTest {
        val viewModel = RideHistoryViewModel(rideRepo, settingsRepo)

        viewModel.events.test {
            viewModel.onAction(RideHistoryAction.OnRideClick(42L))
            val event = awaitItem()
            assertThat(event).isEqualTo(RideHistoryEvent.NavigateToDetail(42L))
        }
    }

    @Test
    fun `delete ride sends undo snackbar`() = runTest {
        val ride = RideRecord(
            id = 1L, startTimestamp = 0L, endTimestamp = 1000L,
            distanceKm = 10.0, movingTimeSeconds = 600L, elapsedTimeSeconds = 1200L,
            averageSpeedKmh = 20.0, maxSpeedKmh = 30.0, elevationGainM = 50.0, caloriesKcal = 200.0
        )
        rideRepo.rides.add(ride)

        val viewModel = RideHistoryViewModel(rideRepo, settingsRepo)

        viewModel.events.test {
            viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
            val event = awaitItem()
            assertThat(event).isEqualTo(RideHistoryEvent.ShowUndoSnackbar)
        }
    }

    @Test
    fun `undo restores recently deleted ride`() = runTest {
        val ride = RideRecord(
            id = 1L, startTimestamp = 0L, endTimestamp = 1000L,
            distanceKm = 10.0, movingTimeSeconds = 600L, elapsedTimeSeconds = 1200L,
            averageSpeedKmh = 20.0, maxSpeedKmh = 30.0, elevationGainM = 50.0, caloriesKcal = 200.0
        )
        rideRepo.rides.add(ride)

        val viewModel = RideHistoryViewModel(rideRepo, settingsRepo)

        // Delete the ride
        viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
        assertThat(rideRepo.rides.size).isEqualTo(0)

        // Undo
        viewModel.onAction(RideHistoryAction.OnUndoDelete)
        assertThat(rideRepo.rides.size).isEqualTo(1)
    }

    @Test
    fun `delete all clears all rides`() = runTest {
        repeat(3) { i ->
            rideRepo.rides.add(
                RideRecord(
                    id = i.toLong(), startTimestamp = 0L, endTimestamp = 1000L,
                    distanceKm = 10.0, movingTimeSeconds = 600L, elapsedTimeSeconds = 1200L,
                    averageSpeedKmh = 20.0, maxSpeedKmh = 30.0, elevationGainM = 50.0, caloriesKcal = 200.0
                )
            )
        }

        val viewModel = RideHistoryViewModel(rideRepo, settingsRepo)
        viewModel.onAction(RideHistoryAction.OnDeleteAll)
        assertThat(rideRepo.rides.size).isEqualTo(0)
    }

    // ── Fakes ─────────────────────────────────────────────────────────────

    class FakeRideHistoryRepository : RideHistoryRepository {
        val rides = mutableListOf<RideRecord>()

        fun emitRides(list: List<RideRecord>) {
            rides.clear()
            rides.addAll(list)
        }

        override fun observeAll(): Flow<List<RideRecord>> = flowOf(rides.toList())
        override suspend fun getById(id: Long): Result<RideRecord, DataError.Local> {
            return rides.find { it.id == id }?.let { Result.Success(it) }
                ?: Result.Error(DataError.Local.NOT_FOUND)
        }
        override suspend fun save(ride: RideRecord) = Result.Success(ride.id).also {
            rides.removeAll { it.id == ride.id }
            rides.add(ride)
        }
        override suspend fun deleteById(id: Long) = Result.Success(Unit).also { rides.removeAll { it.id == id } }
        override suspend fun deleteAll() = Result.Success(Unit).also { rides.clear() }
    }

    class FakeUserSettingsRepository : UserSettingsRepository {
        override fun observeSettings(): Flow<UserSettings> =
            flowOf(UserSettings(weightKg = 75, age = 30))

        override suspend fun save(settings: UserSettings) = Result.Success(Unit)
    }
}
