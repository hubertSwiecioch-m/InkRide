# Quality & Tech Debt Round 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three verified correctness bugs (undo-delete data loss, GPS bounce distance inflation, a BLE/GPS state race), delete three dead Gradle modules, enable Room schema export with migration tests, and extract testable heading-smoothing logic out of the GPS sensor data source.

**Architecture:** No architectural changes. Each task is a small, independent fix or addition within the existing Clean Architecture / MVI structure described in `CLAUDE.md`. Tasks touch different files and can be implemented and reviewed in any order.

**Tech Stack:** Kotlin, JUnit 5 (Jupiter) via `useJUnitPlatform()`, AssertK, Turbine, `kotlinx-coroutines-test`, Koin, Room 2.8.4. Task 5 additionally introduces Robolectric + JUnit 4 (via the Vintage engine) for `:core:database` only, since Room migration SQL can only be executed against a real SQLite engine, which plain JVM unit tests don't provide.

## Global Constraints

- Tests use JUnit 5 (`org.junit.jupiter.api.Test`) and AssertK (`assertk.assertThat`), per the existing test files in this repo — except Task 5's `MigrationTest`, which must use JUnit 4 (`org.junit.Test`, `@RunWith`) because Robolectric's `RobolectricTestRunner` is a JUnit 4 runner; the Vintage engine bridges it onto the JUnit 5 platform that `useJUnitPlatform()` already runs.
- Kotlin coroutine tests use `UnconfinedTestDispatcher` / `runTest`, matching `RideHistoryViewModelTest.kt` and `RideTrackerTest.kt`.
- Follow existing package conventions exactly: `com.speedevand.inkride.core.domain.tracking`, `com.speedevand.inkride.core.domain.history`, `com.speedevand.inkride.history.presentation`, `com.speedevand.inkride.core.database`.
- No new user-facing strings, no new permissions, no navigation changes.

---

## Task 1: Fix undo-delete data loss in Ride History

**Files:**
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideHistoryViewModel.kt`
- Test: `feature/history/presentation/src/test/kotlin/com/speedevand/inkride/history/presentation/RideHistoryViewModelTest.kt`

**Interfaces:**
- Consumes (already exist, no changes needed): `RideTrackPointRepository.getPoints(rideId: Long): Result<List<RideTrackPoint>, DataError.Local>`, `RideTrackPointRepository.savePoints(rideId: Long, points: List<RideTrackPoint>): EmptyResult<DataError.Local>`, `RideLapRepository.getLaps(rideId: Long): Result<List<LapRecord>, DataError.Local>`, `RideLapRepository.saveLaps(rideId: Long, laps: List<LapRecord>): EmptyResult<DataError.Local>`.
- Produces: nothing consumed by later tasks — independent.

- [ ] **Step 1: Update the test file first (adds two fakes, updates all constructor call sites, adds the failing test)**

Replace the entire contents of `feature/history/presentation/src/test/kotlin/com/speedevand/inkride/history/presentation/RideHistoryViewModelTest.kt` with:

```kotlin
package com.speedevand.inkride.history.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
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
            val laps = listOf(LapRecord(lapNumber = 1, distanceKm = 5.0, movingTimeSeconds = 300L, averageSpeedKmh = 20.0, elevationGainM = 10.0))
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

        override suspend fun savePoints(
            rideId: Long,
            points: List<RideTrackPoint>,
        ): EmptyResult<DataError.Local> {
            saved[rideId] = points
            return Result.Success(Unit)
        }

        override suspend fun getPoints(rideId: Long): Result<List<RideTrackPoint>, DataError.Local> =
            Result.Success(saved[rideId] ?: emptyList())
    }

    class FakeLapRepository : RideLapRepository {
        val saved = mutableMapOf<Long, List<LapRecord>>()

        override suspend fun saveLaps(
            rideId: Long,
            laps: List<LapRecord>,
        ): EmptyResult<DataError.Local> {
            saved[rideId] = laps
            return Result.Success(Unit)
        }

        override suspend fun getLaps(rideId: Long): Result<List<LapRecord>, DataError.Local> =
            Result.Success(saved[rideId] ?: emptyList())
    }
}
```

- [ ] **Step 2: Run the test file to confirm it fails to compile against the current 2-arg constructor**

Run: `./gradlew :feature:history:presentation:testDebugUnitTest --tests "com.speedevand.inkride.history.presentation.RideHistoryViewModelTest"`
Expected: FAIL — compile error, `RideHistoryViewModel(rideRepo, settingsRepo, trackPointRepo, lapRepo)` does not match the current constructor, which only takes 2 params.

- [ ] **Step 3: Implement the fix in `RideHistoryViewModel.kt`**

Replace the entire contents of `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideHistoryViewModel.kt` with:

```kotlin
package com.speedevand.inkride.history.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.onFailure
import com.speedevand.inkride.core.domain.onSuccess
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.domain.tracking.LapRecord
import com.speedevand.inkride.core.presentation.toUiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RideHistoryViewModel(
    private val rideHistoryRepository: RideHistoryRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val trackPointRepository: RideTrackPointRepository,
    private val lapRepository: RideLapRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(RideHistoryState())
    val state = _state.asStateFlow()

    private val _events = Channel<RideHistoryEvent>()
    val events = _events.receiveAsFlow()

    private data class DeletedRideBundle(
        val ride: RideRecord,
        val trackPoints: List<RideTrackPoint>,
        val laps: List<LapRecord>,
    )

    private var recentlyDeletedRide: DeletedRideBundle? = null

    init {
        viewModelScope.launch {
            combine(
                rideHistoryRepository.observeAll(),
                userSettingsRepository.observeSettings(),
            ) { rides, settings ->
                rides.map { r -> r.toUi(settings.units) }
            }.collect { uiRides ->
                _state.update {
                    it.copy(rides = uiRides, isLoading = false)
                }
            }
        }
    }

    fun onAction(action: RideHistoryAction) {
        when (action) {
            is RideHistoryAction.OnRideClick -> {
                viewModelScope.launch {
                    _events.send(RideHistoryEvent.NavigateToDetail(action.id))
                }
            }

            is RideHistoryAction.OnDeleteRide -> {
                viewModelScope.launch {
                    rideHistoryRepository
                        .getById(action.id)
                        .onSuccess { ride ->
                            val points =
                                when (val result = trackPointRepository.getPoints(action.id)) {
                                    is Result.Success -> result.data
                                    is Result.Error -> emptyList()
                                }
                            val laps =
                                when (val result = lapRepository.getLaps(action.id)) {
                                    is Result.Success -> result.data
                                    is Result.Error -> emptyList()
                                }
                            recentlyDeletedRide = DeletedRideBundle(ride, points, laps)
                            rideHistoryRepository
                                .deleteById(action.id)
                                .onSuccess {
                                    _events.send(RideHistoryEvent.ShowUndoSnackbar)
                                }.onFailure { error ->
                                    _events.send(RideHistoryEvent.ShowError(error.toUiText()))
                                }
                        }.onFailure { error ->
                            _events.send(RideHistoryEvent.ShowError(error.toUiText()))
                        }
                }
            }

            RideHistoryAction.OnUndoDelete -> {
                viewModelScope.launch {
                    recentlyDeletedRide?.let { bundle ->
                        rideHistoryRepository
                            .save(bundle.ride)
                            .onSuccess { newId ->
                                if (bundle.trackPoints.isNotEmpty()) {
                                    trackPointRepository.savePoints(newId, bundle.trackPoints)
                                }
                                if (bundle.laps.isNotEmpty()) {
                                    lapRepository.saveLaps(newId, bundle.laps)
                                }
                            }.onFailure { error ->
                                _events.send(RideHistoryEvent.ShowError(error.toUiText()))
                            }
                        recentlyDeletedRide = null
                    }
                }
            }

            RideHistoryAction.OnDeleteAll -> {
                viewModelScope.launch {
                    rideHistoryRepository.deleteAll().onFailure { error ->
                        _events.send(RideHistoryEvent.ShowError(error.toUiText()))
                    }
                }
            }

            RideHistoryAction.OnLifetimeStatsClick -> {
                viewModelScope.launch {
                    _events.send(RideHistoryEvent.NavigateToLifetimeStats)
                }
            }
        }
    }
}
```

Note: `historyPresentationModule` (`feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/HistoryPresentationModule.kt`) already registers `RideHistoryViewModel` via `viewModelOf(::RideHistoryViewModel)`, which resolves constructor parameters reflectively — no change needed there. `RideTrackPointRepository` and `RideLapRepository` are already bound in the Koin graph (confirmed: `RideDetailViewModel` in the same module already injects both via `get()`).

- [ ] **Step 4: Run the test file again to confirm it passes**

Run: `./gradlew :feature:history:presentation:testDebugUnitTest --tests "com.speedevand.inkride.history.presentation.RideHistoryViewModelTest"`
Expected: PASS — all 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideHistoryViewModel.kt feature/history/presentation/src/test/kotlin/com/speedevand/inkride/history/presentation/RideHistoryViewModelTest.kt
git commit -m "$(cat <<'EOF'
Fix undo-delete losing a ride's GPS track and laps

OnDeleteRide cached only the ride's summary row, but track_point/lap
rows cascade-delete with it, so undo silently restored a ride with no
track and no laps. Cache and restore all three, using the id save()
returns rather than assuming it matches the pre-delete id.
EOF
)"
```

---

## Task 2: Fix GPS bounce detection distance inflation

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorTest.kt`

**Interfaces:** None — independent of other tasks in this plan.

- [ ] **Step 1: Write the failing test**

In `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorTest.kt`, replace the existing test (lines 419-443):

```kotlin
    @Test
    fun `bounce detection rejects return leg of GPS jump-bounce`() {
        // Position A
        calculator.process(
            sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings,
        )
        // Position B: jump ~555m away (glitch)
        calculator.process(
            sampleAt(1000L, latitude = 0.005, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
            settings,
        )
        // Position C: back within 5m of A — this is a bounce return
        val metrics =
            calculator.process(
                sampleAt(2000L, latitude = 0.00001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f),
                settings,
            )
        // The return segment C should NOT add distance (bounce detected).
        // But A→B was already processed. Since GPS speed is reliable and shows
        // movement, the auto-pause check would have allowed it.
        // The key assertion: C's return segment is zeroed out.
        // We verify the calculator didn't crash and produced sensible output.
        assertThat(metrics.gpsQuality).isNotNull()
    }
```

with:

```kotlin
    @Test
    fun `bounce detection reverses the outbound jump distance, not just the return leg`() {
        // recentPositions only reaches its size-3 warm-up once 3 *accepted*
        // location fixes have landed, and bounce detection reads
        // recentPositions[0]/[1] as "oldest"/"middle" — so this sequence is
        // built to land the jump exactly at index 1 when the return fix is
        // evaluated: one steady fix (S1), the jump (S2), one more steady fix
        // continuing from the jump's position (S3) to shift the ring buffer,
        // then the return (S4).
        calculator.process(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 11.0, accuracy = 5.0f), settings)
        calculator.process(sampleAt(1000L, latitude = 0.00010, longitude = 0.0, speedFromGpsMps = 11.0, accuracy = 5.0f), settings)

        // The jump: ~33m over 3s (a plausible post-dropout speed, ~11 m/s) so
        // it doesn't independently trip the speed/accel/cross-validation
        // outlier checks and gets accepted as a real fix.
        val afterJump =
            calculator
                .process(sampleAt(4000L, latitude = 0.00040, longitude = 0.0, speedFromGpsMps = 11.13, accuracy = 5.0f), settings)
                .distanceKm

        // A further steady fix continuing from the jump's (wrong) position —
        // needed only to shift the jump into recentPositions[1] for the
        // return fix's bounce check.
        calculator.process(sampleAt(5000L, latitude = 0.00050, longitude = 0.0, speedFromGpsMps = 11.13, accuracy = 5.0f), settings)

        // The return: back within 5m of the position from before the jump.
        val afterReturn =
            calculator
                .process(sampleAt(6000L, latitude = 0.00012, longitude = 0.0, speedFromGpsMps = 11.13, accuracy = 5.0f), settings)
                .distanceKm

        // If only the return leg were zeroed (the pre-fix behavior), distance
        // could only stay flat or grow from here — it can never go back down.
        // A real decrease proves the jump's ~33m was reversed once the bounce
        // was confirmed.
        assertThat(afterReturn).isLessThan(afterJump)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorTest.bounce detection reverses the outbound jump distance, not just the return leg"`
Expected: FAIL — `afterReturn` is not less than `afterJump` (current code never reverses the outbound leg, so distance only grows or holds flat).

- [ ] **Step 3: Implement the fix in `RideMetricsCalculator.kt`**

In `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt`:

1. Change the `recentPositions` declaration (currently around line 92):

```kotlin
    // Bounce detection: track the last 3 position-bearing samples (lat, lng).
    // When GPS jumps away and immediately returns, the middle sample is an
    // artifact that should be rejected.
    private val recentPositions = ArrayDeque<Pair<Double, Double>>(3)
```

to:

```kotlin
    // Bounce detection: track the last 3 position-bearing samples along with
    // the distance each contributed to totalDistanceM. When GPS jumps away
    // and immediately returns, the middle sample is an artifact — and its
    // distance (already added to totalDistanceM on a prior call) must be
    // reversed, not just the return leg's.
    private val recentPositions = ArrayDeque<Triple<Double, Double, Double>>(3)
```

2. Add one new state variable next to the other per-sample locals (currently around line 181-182):

```kotlin
        // Default outputs carried over from the last GPS-derived state so that
        // intermediate non-location samples don't reset the live readout.
        var speedMps = lastReportedSpeedMps
        var isActuallyMoving = false
```

to:

```kotlin
        // Default outputs carried over from the last GPS-derived state so that
        // intermediate non-location samples don't reset the live readout.
        var speedMps = lastReportedSpeedMps
        var isActuallyMoving = false

        // Distance this fix actually contributed to totalDistanceM (0 if
        // rejected as an outlier, paused, or otherwise suppressed). Recorded
        // alongside the position in recentPositions so a later-confirmed
        // bounce can reverse exactly what this fix added — see the isBounce
        // handling below.
        var appliedDistanceM = 0.0
```

3. In the `if (!isPaused) { ... }` block (currently lines 353-357), record and reverse:

```kotlin
            if (!isPaused) {
                if (isActuallyMoving) {
                    movingTimeMs += integrationDtMs
                }
                totalDistanceM += effectiveDistanceM
                maxSpeedMps = max(maxSpeedMps, speedMps)
```

to:

```kotlin
            if (!isPaused) {
                if (isActuallyMoving) {
                    movingTimeMs += integrationDtMs
                }
                appliedDistanceM = effectiveDistanceM
                totalDistanceM += effectiveDistanceM
                // A confirmed bounce means the jump leg (recorded at
                // recentPositions[1]) was itself a GPS artifact that already
                // added its distance on a prior call — reverse it now so a
                // jump-then-bounce pattern doesn't permanently inflate total
                // distance. Zeroing the stored distance after reversing
                // guards against double-subtracting it if a later fix also
                // reads as a bounce against the same stale reference pair.
                if (isBounce && recentPositions.size >= 3) {
                    val middle = recentPositions[1]
                    totalDistanceM = (totalDistanceM - middle.third).coerceAtLeast(0.0)
                    recentPositions[1] = middle.copy(third = 0.0)
                }
                maxSpeedMps = max(maxSpeedMps, speedMps)
```

4. Update the bounce-check reads to use `.first`/`.second` on the now-`Triple` elements (currently lines 225-234) — the field access already works unchanged since `Triple` also exposes `.first`/`.second`, so **no edit needed** here; leave as-is:

```kotlin
            val isBounce: Boolean =
                if (recentPositions.size >= 3) {
                    val oldest = recentPositions.first()
                    val middle = recentPositions[1]
                    val jumpDist = haversineMeters(oldest.first, oldest.second, middle.first, middle.second)
                    val returnDist = haversineMeters(oldest.first, oldest.second, sample.latitude, sample.longitude)
                    jumpDist > bounceJumpRadiusM && returnDist < bounceReturnRadiusM
                } else {
                    false
                }
```

5. Update where positions are recorded into the ring buffer (currently lines 452-458):

```kotlin
        if (sample.latitude != null && sample.longitude != null && !locationOutlierRejected) {
            lastLocationSample = sample
            // Maintain a ring buffer of the last 3 GPS positions for bounce detection.
            recentPositions.addLast(sample.latitude to sample.longitude)
            while (recentPositions.size > 3) {
                recentPositions.removeFirst()
            }
        }
```

to:

```kotlin
        if (sample.latitude != null && sample.longitude != null && !locationOutlierRejected) {
            lastLocationSample = sample
            // Maintain a ring buffer of the last 3 GPS positions (plus the
            // distance each contributed) for bounce detection.
            recentPositions.addLast(Triple(sample.latitude, sample.longitude, appliedDistanceM))
            while (recentPositions.size > 3) {
                recentPositions.removeFirst()
            }
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorTest"`
Expected: PASS — all tests in the class green, including the new bounce test.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorTest.kt
git commit -m "$(cat <<'EOF'
Reverse the outbound leg's distance on a confirmed GPS bounce

Bounce detection only zeroed the return leg's distance; the outbound
jump (which passes the speed/accel/cross-validation checks whenever
it follows a brief GPS dropout) had already been added to
totalDistanceM and was never reversed, permanently inflating ride
distance. recentPositions now tracks the distance each fix
contributed so a confirmed bounce can reverse the jump leg too.
EOF
)"
```

---

## Task 3: Fix BLE/GPS state race in RideTracker

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt`

**Interfaces:** None — independent of other tasks in this plan.

- [ ] **Step 1: Write the failing test**

In `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt`, add this test after `a BLE disconnect clears the connected flag` (after line 274):

```kotlin

    @Test
    fun `HR set by a BLE sample survives a subsequent GPS-only metrics update`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(BleSample(timestampMs = 0L, heartRateBpm = 142, cadenceRpm = 88, connected = true))
            assertThat(tracker.state.value.metrics.heartRateBpm).isEqualTo(142)

            // A GPS fix must not clobber the HR/cadence the BLE collector just
            // committed to state — it should read the live value at commit
            // time inside the same atomic update, not a snapshot taken
            // before it.
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))

            assertThat(tracker.state.value.metrics.heartRateBpm).isEqualTo(142)
            assertThat(tracker.state.value.metrics.cadenceRpm).isEqualTo(88)
        }
```

(This needs `assertk.assertions.isEqualTo`, already imported at the top of the file.)

- [ ] **Step 2: Run test to verify it fails or passes for the wrong reason**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest.HR set by a BLE sample survives a subsequent GPS-only metrics update"`
Expected: PASS even before the fix — this specific sequential (non-racing) scenario doesn't reproduce the race under `UnconfinedTestDispatcher` (see note below). This step exists to confirm the test itself is well-formed and the assertions hold under the *intended* behavior before refactoring the production code around it.

Note: the actual race (a `bleJob` commit landing between the GPS handler's read of `latestBle` and its own `updateAndGet` call) requires genuine multi-threaded interleaving that a deterministic single-threaded test dispatcher cannot reproduce — this matches the design doc's expectation ("if feasible... otherwise cover via existing patterns"). This test is a regression guard for the merge-correctness property, verified by code inspection to close the actual race (Step 3), not a reproduction of the race itself.

- [ ] **Step 3: Implement the fix in `RideTracker.kt`**

1. Remove the `latestBle` field entirely — it becomes dead once the GPS handler stops reading it. Delete (currently lines 133-137):

```kotlin
    // Latest BLE sensor reading, folded into every emitted RideMetrics. Written by
    // the BLE collector coroutine, read by the GPS collector — @Volatile for
    // cross-thread visibility.
    @Volatile
    private var latestBle: BleSample? = null

```

2. Remove its reset in `stop()` (currently line 243):

```kotlin
        lowSpeedSinceMs = null
        latestBle = null
        resetAlertState()
```

to:

```kotlin
        lowSpeedSinceMs = null
        resetAlertState()
```

3. Remove its reset in `startNewSession()` (currently line 260):

```kotlin
                sessionStartMs = System.currentTimeMillis()
                metricsCalculator.reset()
                lowSpeedSinceMs = null
                latestBle = null
                resetAlertState()
```

to:

```kotlin
                sessionStartMs = System.currentTimeMillis()
                metricsCalculator.reset()
                lowSpeedSinceMs = null
                resetAlertState()
```

4. Remove the write in `bleJob` (currently line 348):

```kotlin
                        bleSensorDataSource.observeSamples().collect { ble ->
                            latestBle = ble
                            val updated =
```

to:

```kotlin
                        bleSensorDataSource.observeSamples().collect { ble ->
                            val updated =
```

5. Fix the GPS handler (currently lines 376-406):

```kotlin
                        val baseMetrics =
                            metricsCalculator.process(
                                sample = sample,
                                userSettings = latestSettings,
                                isPaused = isPaused,
                            )
                        // Fold the latest BLE reading into the published metrics; the
                        // calculator stays GPS-only.
                        val ble = latestBle
                        val metrics =
                            if (ble != null) {
                                baseMetrics.copy(heartRateBpm = ble.heartRateBpm, cadenceRpm = ble.cadenceRpm)
                            } else {
                                baseMetrics
                            }
                        val autoStatus = evaluateAutoPause(statusBefore, metrics.currentSpeedKmh, sample.timestampMs)
                        // Recompute route-follow progress from this fix; carry the
                        // previous value forward on a fix-less sample so the readout
                        // doesn't flicker between GPS updates.
                        val progress = evaluateRoute(sample)
                        // A manual start/pause/resume/stop may have landed on the caller
                        // thread since statusBefore was read. Only apply the auto-pause
                        // decision when the status is unchanged, and never write over a
                        // stop() that reset us to IDLE — so the rider's explicit command
                        // is never clobbered by this (asynchronous) sample.
                        val newState =
                            _state.updateAndGet { current ->
                                if (current.status == TrackingStatus.IDLE) return@updateAndGet current
                                val resolved = if (current.status == statusBefore) autoStatus else current.status
                                current.copy(status = resolved, metrics = metrics, routeProgress = progress)
                            }
                        recordTrackPoint(newState.status, sample, metrics)
                        evaluateAlerts(newState.status, newState.metrics)
                        evaluateOffRoute(newState.status, newState.routeProgress)
```

to:

```kotlin
                        val baseMetrics =
                            metricsCalculator.process(
                                sample = sample,
                                userSettings = latestSettings,
                                isPaused = isPaused,
                            )
                        val autoStatus = evaluateAutoPause(statusBefore, baseMetrics.currentSpeedKmh, sample.timestampMs)
                        // Recompute route-follow progress from this fix; carry the
                        // previous value forward on a fix-less sample so the readout
                        // doesn't flicker between GPS updates.
                        val progress = evaluateRoute(sample)
                        // A manual start/pause/resume/stop may have landed on the caller
                        // thread since statusBefore was read. Only apply the auto-pause
                        // decision when the status is unchanged, and never write over a
                        // stop() that reset us to IDLE — so the rider's explicit command
                        // is never clobbered by this (asynchronous) sample.
                        val newState =
                            _state.updateAndGet { current ->
                                if (current.status == TrackingStatus.IDLE) return@updateAndGet current
                                val resolved = if (current.status == statusBefore) autoStatus else current.status
                                // Merge HR/cadence from whatever the BLE collector has
                                // most recently committed to `current` inside this same
                                // atomic update, rather than a snapshot read earlier —
                                // otherwise a concurrent BLE commit landing in between
                                // would be clobbered by a stale value here.
                                val metrics =
                                    baseMetrics.copy(
                                        heartRateBpm = current.metrics.heartRateBpm,
                                        cadenceRpm = current.metrics.cadenceRpm,
                                    )
                                current.copy(status = resolved, metrics = metrics, routeProgress = progress)
                            }
                        recordTrackPoint(newState.status, sample, newState.metrics)
                        evaluateAlerts(newState.status, newState.metrics)
                        evaluateOffRoute(newState.status, newState.routeProgress)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest"`
Expected: PASS — all tests in the class green, including the new one.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt
git commit -m "$(cat <<'EOF'
Fix a BLE/GPS state race that could drop a live HR/cadence update

The GPS-sample handler merged a BLE snapshot taken before its atomic
state update, so a concurrent BLE commit landing in between was
clobbered. Read HR/cadence from `current.metrics` inside the same
updateAndGet block instead, so it always reflects whatever bleJob most
recently committed. Removes the now-dead latestBle field.
EOF
)"
```

---

## Task 4: Delete the three empty Gradle modules

**Files:**
- Delete: `core/data/` (entire directory)
- Delete: `feature/dashboard/domain/` (entire directory)
- Delete: `feature/dashboard/data/` (entire directory)
- Modify: `settings.gradle.kts`

**Interfaces:** None — independent of other tasks in this plan.

- [ ] **Step 1: Delete the three module directories**

```bash
git rm -r core/data feature/dashboard/domain feature/dashboard/data
```

- [ ] **Step 2: Remove their entries from `settings.gradle.kts`**

In `settings.gradle.kts`, remove these three lines:

```kotlin
include(":core:data")
```

```kotlin
include(":feature:dashboard:domain")
include(":feature:dashboard:data")
```

- [ ] **Step 3: Verify the build still resolves with no dangling references**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — no module resolution errors, no compile errors elsewhere referencing the deleted modules (confirmed during design that nothing depends on them).

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts
git commit -m "$(cat <<'EOF'
Delete three empty Gradle modules

:core:data, :feature:dashboard:domain, and :feature:dashboard:data
have only ever contained a build.gradle.kts with no src/ directory,
and nothing depends on them.
EOF
)"
```

---

## Task 5: Enable Room schema export and add migration tests

**Files:**
- Modify: `core/database/src/main/java/com/speedevand/inkride/core/database/AppDatabase.kt`
- Modify: `core/database/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `core/database/src/test/java/com/speedevand/inkride/core/database/MigrationTest.kt`
- Create (generated, then committed): `core/database/schemas/com.speedevand.inkride.core.database.AppDatabase/6.json`

**Interfaces:** None — independent of other tasks in this plan.

**Context:** `AppDatabase` has `exportSchema = false`, so there's no historical schema JSON for versions 4/5, and Room's `MigrationTestHelper` requires a schema snapshot for the *starting* version of any migration it tests — meaning it cannot be used to test the existing `MIGRATION_4_5`/`MIGRATION_5_6` retroactively. These tests instead hand-build just the columns each migration's SQL actually reads/writes and run the `Migration` objects directly against a real SQLite engine — which requires Robolectric, since plain JVM unit tests only stub `android.database.sqlite.*` (per `testOptions.unitTests.isReturnDefaultValues = true` in the Android convention plugin) rather than providing a working implementation.

- [ ] **Step 1: Add Robolectric, JUnit 4, the JUnit 5 Vintage engine, and androidx.test:core to the version catalog**

In `gradle/libs.versions.toml`, add to the `[versions]` block (after `ktlintGradle = "14.2.0"`):

```toml
robolectric = "4.16.1"
androidxTestCore = "1.7.0"
```

Add to the `[libraries]` block (after `osmdroid-android = ...`):

```toml
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
junit-vintage-engine = { group = "org.junit.vintage", name = "junit-vintage-engine", version.ref = "junitJupiter" }
```

- [ ] **Step 2: Add the new test dependencies to `core/database/build.gradle.kts`**

Replace:

```kotlin
dependencies {
    implementation(project(":core:domain"))
    implementation(libs.koin.android)
}
```

with:

```kotlin
dependencies {
    implementation(project(":core:domain"))
    implementation(libs.koin.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage.engine)
}
```

(`assertk` is already provided to every Android library module's `testImplementation` by `AndroidLibraryConventionPlugin` — no need to add it again here.)

- [ ] **Step 3: Write the failing tests**

Create `core/database/src/test/java/com/speedevand/inkride/core/database/MigrationTest.kt`:

```kotlin
package com.speedevand.inkride.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Historical schema JSON for versions 4/5 was never exported (exportSchema
 * was false until this change), so Room's MigrationTestHelper — which
 * requires a schema snapshot of the *starting* version — can't validate
 * MIGRATION_4_5/MIGRATION_5_6 retroactively. These tests hand-build just the
 * columns each migration's SQL reads or writes and run the Migration object
 * directly against a real (Robolectric-backed) SQLite database instead.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun openHelper(
        dbName: String,
        version: Int,
        createSql: List<String>,
    ): SupportSQLiteOpenHelper {
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createSql.forEach { db.execSQL(it) }
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    @Test
    fun `migration 4 to 5 adds ride_lap table and paired-address columns`() {
        val db =
            openHelper(
                dbName = "migration_4_5_test",
                version = 4,
                createSql =
                    listOf(
                        "CREATE TABLE `user_settings` (`id` INTEGER PRIMARY KEY NOT NULL, `weightKg` INTEGER NOT NULL)",
                        "INSERT INTO `user_settings` (`id`, `weightKg`) VALUES (1, 75)",
                    ),
            ).writableDatabase

        MIGRATION_4_5.migrate(db)

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='ride_lap'").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
        }
        db.query("SELECT weightKg, pairedHrmAddress, pairedCadenceAddress FROM user_settings WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(75)
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.isNull(2)).isTrue()
        }
        db.close()
    }

    @Test
    fun `migration 5 to 6 seeds a default bike profile from the flat settings columns`() {
        val db =
            openHelper(
                dbName = "migration_5_6_test",
                version = 5,
                createSql =
                    listOf(
                        "CREATE TABLE `user_settings` (`id` INTEGER PRIMARY KEY NOT NULL, `bikeWeightKg` REAL NOT NULL, `bikeType` TEXT NOT NULL)",
                        "INSERT INTO `user_settings` (`id`, `bikeWeightKg`, `bikeType`) VALUES (1, 12.5, 'GRAVEL')",
                    ),
            ).writableDatabase

        MIGRATION_5_6.migrate(db)

        var seededProfileId = -1L
        db.query("SELECT id, name, weightKg, type FROM bike_profile").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.moveToFirst()).isTrue()
            seededProfileId = cursor.getLong(0)
            assertThat(cursor.getString(1)).isEqualTo("Default")
            assertThat(cursor.getDouble(2)).isEqualTo(12.5)
            assertThat(cursor.getString(3)).isEqualTo("GRAVEL")
        }
        db.query("SELECT activeBikeProfileId FROM user_settings WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(seededProfileId)
        }
        db.close()
    }
}
```

- [ ] **Step 4: Run tests to verify they fail (compile error — `MIGRATION_4_5`/`MIGRATION_5_6` aren't imported issues, but Robolectric/JUnit4 wiring isn't in place yet in a way that's been verified)**

Run: `./gradlew :core:database:testDebugUnitTest --tests "com.speedevand.inkride.core.database.MigrationTest"`
Expected: either a build/dependency-resolution error (fix by adjusting the exact Robolectric/androidx.test:core versions in `libs.versions.toml` if resolution fails) or a runtime failure. Iterate here until the failure is a genuine assertion failure or the test doesn't run at all — not a class-not-found/dependency error — before moving on. If Robolectric reports an unsupported SDK for `compileSdk = 36`, add `@Config(sdk = [35])` (import `org.robolectric.annotation.Config`) to the `MigrationTest` class and re-run.

- [ ] **Step 5: Enable schema export in `AppDatabase.kt`**

In `core/database/src/main/java/com/speedevand/inkride/core/database/AppDatabase.kt`, change:

```kotlin
@Database(
    entities = [
        UserSettingsEntity::class,
        RideHistoryEntity::class,
        RideTrackPointEntity::class,
        RideLapEntity::class,
        BikeProfileEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
```

to:

```kotlin
@Database(
    entities = [
        UserSettingsEntity::class,
        RideHistoryEntity::class,
        RideTrackPointEntity::class,
        RideLapEntity::class,
        BikeProfileEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
```

- [ ] **Step 6: Generate and commit the schema JSON, then run tests to verify they pass**

Run: `./gradlew :core:database:assembleDebug`
Expected: BUILD SUCCESSFUL, and a new file appears at `core/database/schemas/com.speedevand.inkride.core.database.AppDatabase/6.json` (path configured by `schemaDirectory("$projectDir/schemas")` in `RoomConventionPlugin`).

Run: `./gradlew :core:database:testDebugUnitTest --tests "com.speedevand.inkride.core.database.MigrationTest"`
Expected: PASS — both migration tests green.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml core/database/build.gradle.kts core/database/src/main/java/com/speedevand/inkride/core/database/AppDatabase.kt core/database/src/test/java/com/speedevand/inkride/core/database/MigrationTest.kt core/database/schemas
git commit -m "$(cat <<'EOF'
Enable Room schema export and add migration tests

exportSchema was false despite the Room Gradle plugin already
configuring a schema directory, so migrations were never validated
against a real schema and couldn't be tested. Enables export (covering
all future migrations) and adds Robolectric-backed tests for the two
existing migrations, focused on MIGRATION_5_6's bike-profile seeding
logic — the riskiest part of either.
EOF
)"
```

---

## Task 6: Extract heading-smoothing logic into a testable class

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/HeadingSmoother.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/HeadingSmootherTest.kt`
- Modify: `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt`

**Interfaces:**
- Produces: `HeadingSmoother(smoothingAlpha: Float = 0.2f, emitThresholdDeg: Float = 2.0f)` with `fun update(magneticAzimuthDeg: Float, declinationDeg: Float): HeadingUpdate` and `fun reset()`, where `data class HeadingUpdate(val smoothedHeadingDeg: Float, val shouldEmit: Boolean)`. Consumed by `AndroidRideSensorDataSource` in this same task.

- [ ] **Step 1: Write the failing tests**

Create `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/HeadingSmootherTest.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class HeadingSmootherTest {
    @Test
    fun `first update returns the raw declination-corrected heading and emits`() {
        val smoother = HeadingSmoother()
        val update = smoother.update(magneticAzimuthDeg = 90f, declinationDeg = 5f)
        assertThat(update.smoothedHeadingDeg).isEqualTo(95f)
        assertThat(update.shouldEmit).isTrue()
    }

    @Test
    fun `small change below the emit threshold is smoothed but not emitted`() {
        val smoother = HeadingSmoother(emitThresholdDeg = 2.0f)
        smoother.update(magneticAzimuthDeg = 90f, declinationDeg = 0f)
        val update = smoother.update(magneticAzimuthDeg = 91f, declinationDeg = 0f)
        assertThat(update.shouldEmit).isFalse()
    }

    @Test
    fun `change at or above the emit threshold is emitted`() {
        val smoother = HeadingSmoother(emitThresholdDeg = 2.0f, smoothingAlpha = 1.0f)
        smoother.update(magneticAzimuthDeg = 90f, declinationDeg = 0f)
        // alpha = 1.0 makes the filter track the raw input exactly, so a
        // 10-degree swing produces a 10-degree smoothed change — comfortably
        // over the 2-degree gate.
        val update = smoother.update(magneticAzimuthDeg = 100f, declinationDeg = 0f)
        assertThat(update.shouldEmit).isTrue()
        assertThat(update.smoothedHeadingDeg).isEqualTo(100f)
    }

    @Test
    fun `smoothing blends toward the new heading along the short arc across the wrap point`() {
        val smoother = HeadingSmoother(smoothingAlpha = 0.5f)
        smoother.update(magneticAzimuthDeg = 350f, declinationDeg = 0f)
        // 350 -> 10 is a 20-degree step across the 0/360 wrap, not a
        // 340-degree step the long way around.
        val update = smoother.update(magneticAzimuthDeg = 10f, declinationDeg = 0f)
        assertThat(update.smoothedHeadingDeg).isEqualTo(0f)
    }

    @Test
    fun `reset clears smoothing and throttle state so the next update emits immediately`() {
        val smoother = HeadingSmoother(emitThresholdDeg = 2.0f)
        smoother.update(magneticAzimuthDeg = 90f, declinationDeg = 0f)
        smoother.reset()
        val update = smoother.update(magneticAzimuthDeg = 91f, declinationDeg = 0f)
        assertThat(update.shouldEmit).isTrue()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.HeadingSmootherTest"`
Expected: FAIL — compile error, `HeadingSmoother` doesn't exist yet.

- [ ] **Step 3: Implement `HeadingSmoother`**

Create `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/HeadingSmoother.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

/**
 * Result of feeding one raw orientation reading into [HeadingSmoother].
 * [smoothedHeadingDeg] is always the latest smoothed true-north heading;
 * [shouldEmit] is true only when it has moved far enough since the last
 * emitted value to be worth publishing.
 */
data class HeadingUpdate(
    val smoothedHeadingDeg: Float,
    val shouldEmit: Boolean,
)

/**
 * Smooths raw magnetometer-derived headings with a circular EMA filter,
 * corrects to true north using magnetic declination, and throttles emission
 * to [emitThresholdDeg] steps so downstream consumers (e.g. an E-Ink
 * compass) aren't flooded with micro-changes.
 */
class HeadingSmoother(
    private val smoothingAlpha: Float = 0.2f,
    private val emitThresholdDeg: Float = 2.0f,
) {
    private var smoothedHeadingDeg: Float? = null
    private var lastEmittedHeadingDeg: Float? = null

    /**
     * Feeds a new raw magnetic azimuth (degrees) and declination (degrees to
     * add to reach true north).
     */
    fun update(
        magneticAzimuthDeg: Float,
        declinationDeg: Float,
    ): HeadingUpdate {
        val magneticHeading = (magneticAzimuthDeg + 360f) % 360f
        val trueHeading = (magneticHeading + declinationDeg + 360f) % 360f

        // Circular EMA: blend along the shortest arc so the filter doesn't
        // lurch the long way around the 0/360 wrap point.
        val smoothed =
            smoothedHeadingDeg?.let { prev ->
                val delta = angularDifference(prev, trueHeading)
                (prev + smoothingAlpha * delta + 360f) % 360f
            } ?: trueHeading
        smoothedHeadingDeg = smoothed

        val emitted = lastEmittedHeadingDeg
        val shouldEmit = emitted == null || Math.abs(angularDifference(emitted, smoothed)) >= emitThresholdDeg
        if (shouldEmit) lastEmittedHeadingDeg = smoothed

        return HeadingUpdate(smoothed, shouldEmit)
    }

    /** Resets all smoothing/throttling state, e.g. when tracking stops. */
    fun reset() {
        smoothedHeadingDeg = null
        lastEmittedHeadingDeg = null
    }

    /**
     * Shortest signed angular difference from [from] to [to], in degrees,
     * within (-180, 180]. Used so circular EMA and threshold checks move
     * along the short arc across the 0/360 wrap point.
     */
    private fun angularDifference(
        from: Float,
        to: Float,
    ): Float {
        var diff = (to - from + 540f) % 360f - 180f
        if (diff == -180f) diff = 180f
        return diff
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.HeadingSmootherTest"`
Expected: PASS — all 5 tests green.

- [ ] **Step 5: Wire `HeadingSmoother` into `AndroidRideSensorDataSource` and delete the now-duplicated logic**

In `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt`:

1. Add the import (alongside the existing `com.speedevand.inkride.core.domain.tracking.*` imports):

```kotlin
import com.speedevand.inkride.core.domain.tracking.HeadingSmoother
```

2. Replace these five fields (currently lines 62-87):

```kotlin
    // True-north heading (magnetic reading + declination), circular-EMA smoothed.
    private var lastHeading: Float? = null

    // Raw smoothed magnetic heading state for the circular EMA filter.
    private var smoothedHeadingDeg: Float? = null

    // Last heading actually emitted — used to throttle emissions to ~2° steps,
    // matching the E-Ink compass's discrete rendering and cutting sample churn.
    private var lastEmittedHeadingDeg: Float? = null

    // Magnetic declination (degrees to add to a magnetic heading to get true
    // north), refreshed from the current location. 0 until the first fix.
    private var magneticDeclinationDeg: Float = 0f

    // True while the magnetometer reports UNRELIABLE/LOW calibration accuracy.
    // While set, magnetometer-derived heading is suppressed (GPS course-over-
    // ground is still used when moving). Unknown accuracy is treated as usable
    // so devices that never fire onAccuracyChanged still get a compass.
    private var isMagnetometerUnreliable: Boolean = false

    // Heading is only emitted when it changes by at least this many degrees.
    private val headingEmitThresholdDeg: Float = 2.0f

    // EMA smoothing factor for the magnetometer heading (higher = more responsive,
    // lower = smoother). 0.2 tames magnetometer jitter without feeling laggy.
    private val headingSmoothingAlpha: Float = 0.2f
```

with:

```kotlin
    // True-north heading (magnetic reading + declination), circular-EMA smoothed.
    private var lastHeading: Float? = null

    private val headingSmoother = HeadingSmoother()

    // Magnetic declination (degrees to add to a magnetic heading to get true
    // north), refreshed from the current location. 0 until the first fix.
    private var magneticDeclinationDeg: Float = 0f

    // True while the magnetometer reports UNRELIABLE/LOW calibration accuracy.
    // While set, magnetometer-derived heading is suppressed (GPS course-over-
    // ground is still used when moving). Unknown accuracy is treated as usable
    // so devices that never fire onAccuracyChanged still get a compass.
    private var isMagnetometerUnreliable: Boolean = false
```

3. Replace the smoothing block inside `localOrientationListener.onSensorChanged` (currently lines 166-196):

```kotlin
                    if (gravity != null && geomagnetic != null) {
                        val r = FloatArray(9)
                        val i = FloatArray(9)
                        if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                            val orientation = FloatArray(3)
                            SensorManager.getOrientation(r, orientation)
                            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                            // Convert magnetic azimuth to a true-north heading.
                            val magneticHeading = (azimuth + 360f) % 360f
                            val trueHeading = (magneticHeading + magneticDeclinationDeg + 360f) % 360f

                            // Circular EMA: blend along the shortest arc so the filter
                            // doesn't lurch the long way around the 0/360 wrap point.
                            val smoothed =
                                smoothedHeadingDeg?.let { prev ->
                                    val delta = angularDifference(prev, trueHeading)
                                    (prev + headingSmoothingAlpha * delta + 360f) % 360f
                                } ?: trueHeading
                            smoothedHeadingDeg = smoothed
                            lastHeading = smoothed
                            lastHeadingTimestampMs = System.currentTimeMillis()

                            // Throttle emissions to ~2° steps to avoid flooding the
                            // sample flow (and the E-Ink redraw) with micro-changes.
                            val emitted = lastEmittedHeadingDeg
                            if (emitted == null || Math.abs(angularDifference(emitted, smoothed)) >= headingEmitThresholdDeg) {
                                lastEmittedHeadingDeg = smoothed
                                emitSample()
                            }
                        }
                    }
```

with:

```kotlin
                    if (gravity != null && geomagnetic != null) {
                        val r = FloatArray(9)
                        val i = FloatArray(9)
                        if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                            val orientation = FloatArray(3)
                            SensorManager.getOrientation(r, orientation)
                            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()

                            val update = headingSmoother.update(azimuth, magneticDeclinationDeg)
                            lastHeading = update.smoothedHeadingDeg
                            lastHeadingTimestampMs = System.currentTimeMillis()

                            // Throttle emissions to ~2° steps to avoid flooding the
                            // sample flow (and the E-Ink redraw) with micro-changes.
                            if (update.shouldEmit) emitSample()
                        }
                    }
```

4. Replace the reset lines in `stop()` (currently lines 388-390):

```kotlin
        lastHeading = null
        smoothedHeadingDeg = null
        lastEmittedHeadingDeg = null
        magneticDeclinationDeg = 0f
```

with:

```kotlin
        lastHeading = null
        headingSmoother.reset()
        magneticDeclinationDeg = 0f
```

5. Delete the now-unused private `angularDifference` function (currently lines 400-412):

```kotlin
    /**
     * Shortest signed angular difference from [from] to [to], in degrees,
     * within (-180, 180]. Used so circular EMA and threshold checks move
     * along the short arc across the 0/360 wrap point.
     */
    private fun angularDifference(
        from: Float,
        to: Float,
    ): Float {
        var diff = (to - from + 540f) % 360f - 180f
        if (diff == -180f) diff = 180f
        return diff
    }

```

- [ ] **Step 6: Verify the module still compiles**

Run: `./gradlew :feature:tracking:data:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run the full `core:domain` test suite once more to confirm nothing else broke**

Run: `./gradlew :core:domain:test`
Expected: PASS — all tests green, including `HeadingSmootherTest`, `RideMetricsCalculatorTest`, and `RideTrackerTest` from Tasks 2 and 3.

- [ ] **Step 8: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/HeadingSmoother.kt core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/HeadingSmootherTest.kt feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt
git commit -m "$(cat <<'EOF'
Extract heading-smoothing logic into a testable HeadingSmoother

AndroidRideSensorDataSource mixed circular-EMA heading smoothing and
2-degree emit throttling directly into LocationManager/SensorManager
callback plumbing tied to Context, with zero test coverage. Extracted
into a plain Kotlin class in :core:domain, matching the existing
ElevationProfileBuilder/CaloriesEstimator pattern. The Android-
framework glue (listener registration/lifecycle) stays untested, as
elsewhere in this module.
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** all six design-doc items have a task (1↔1, 2↔2, 3↔3, 4↔4, 5↔5, 6↔6).
- **Type consistency:** `HeadingSmoother`/`HeadingUpdate` signatures in Task 6 Step 3 match the usage in Step 5 exactly. `DeletedRideBundle` in Task 1 is private to `RideHistoryViewModel`, not referenced elsewhere. `Triple<Double, Double, Double>` in Task 2 is used consistently across all edited call sites (`.first`/`.second`/`.third`).
- **Task 5 risk called out explicitly:** exact Robolectric/androidx.test:core artifact versions were verified against Maven Central as of 2026-07-08, but dependency resolution in this specific project (AGP 9.2.1, compileSdk 36) hasn't been run — Step 4 tells the implementer to iterate on versions/SDK config if resolution fails, rather than treating the first `./gradlew` run as authoritative.
