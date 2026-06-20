package com.speedevand.inkride.settings.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository = FakeUserSettingsRepository()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state loads observed settings`() =
        runTest {
            repository.emitSettings(UserSettings(weightKg = 80, age = 25))

            val viewModel = SettingsViewModel(repository)
            val state = viewModel.state.value

            assertThat(state.userSettings.weightKg).isEqualTo(80)
            assertThat(state.userSettings.age).isEqualTo(25)
        }

    @Test
    fun `initial state has default ui values`() =
        runTest {
            val viewModel = SettingsViewModel(repository)
            val state = viewModel.state.value

            assertThat(state.userSettingsUi.weightKg).isEqualTo("75")
            assertThat(state.userSettingsUi.age).isEqualTo("30")
        }

    @Test
    fun `user settings change updates state and saves`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(
                SettingsAction.OnUserSettingsChanged(
                    UserSettings(weightKg = 82, age = 35, units = MeasurementUnits.METRIC),
                ),
            )

            val state = viewModel.state.value
            assertThat(state.userSettings.weightKg).isEqualTo(82)
            assertThat(state.userSettings.age).isEqualTo(35)
            assertThat(state.userSettingsUi.weightKg).isEqualTo("82")
            assertThat(repository.lastSaved?.weightKg).isEqualTo(82)
        }

    @Test
    fun `user settings change with imperial units updates UI`() =
        runTest {
            repository.emitSettings(UserSettings(weightKg = 75, age = 30, units = MeasurementUnits.IMPERIAL))
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(
                SettingsAction.OnUserSettingsChanged(
                    UserSettings(weightKg = 80, age = 30, units = MeasurementUnits.IMPERIAL),
                ),
            )

            val state = viewModel.state.value
            // 80 kg * 2.20462 ≈ 176 lbs
            assertThat(state.userSettingsUi.weightKg).isEqualTo("176")
        }

    @Test
    fun `valid weight updates state and saves`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnWeightChange("80"))

            val state = viewModel.state.value
            assertThat(state.userSettingsUi.weightKg).isEqualTo("80")
            assertThat(state.userSettingsUi.weightError).isNull()
            assertThat(repository.lastSaved?.weightKg).isEqualTo(80)
        }

    @Test
    fun `weight below minimum shows error`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnWeightChange("10"))

            val state = viewModel.state.value
            assertThat(state.userSettingsUi.weightError).isNotNull()
        }

    @Test
    fun `weight above maximum clamps to max`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnWeightChange("250"))

            val state = viewModel.state.value
            assertThat(state.userSettingsUi.weightKg).isEqualTo("200")
            assertThat(state.userSettingsUi.weightError).isNull()
            assertThat(repository.lastSaved?.weightKg).isEqualTo(200)
        }

    @Test
    fun `non-numeric weight shows error`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnWeightChange("abc"))

            val state = viewModel.state.value
            assertThat(state.userSettingsUi.weightError).isNotNull()
        }

    @Test
    fun `valid age updates state and saves`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnAgeChange("45"))

            val state = viewModel.state.value
            assertThat(state.userSettingsUi.age).isEqualTo("45")
            assertThat(state.userSettingsUi.ageError).isNull()
            assertThat(repository.lastSaved?.age).isEqualTo(45)
        }

    @Test
    fun `age below minimum shows error`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnAgeChange("5"))

            assertThat(viewModel.state.value.userSettingsUi.ageError).isNotNull()
        }

    @Test
    fun `age above maximum clamps to max`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnAgeChange("150"))

            val state = viewModel.state.value
            assertThat(state.userSettingsUi.age).isEqualTo("100")
            assertThat(state.userSettingsUi.ageError).isNull()
            assertThat(repository.lastSaved?.age).isEqualTo(100)
        }

    @Test
    fun `valid bike weight updates state and saves`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnBikeWeightChange("15"))

            val state = viewModel.state.value
            assertThat(state.userSettingsUi.bikeWeightKg).isEqualTo("15")
            assertThat(state.userSettingsUi.bikeWeightError).isNull()
            assertThat(repository.lastSaved?.bikeWeightKg).isEqualTo(15.0)
        }

    @Test
    fun `bike weight below minimum shows error`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnBikeWeightChange("1"))

            assertThat(viewModel.state.value.userSettingsUi.bikeWeightError).isNotNull()
        }

    @Test
    fun `bike weight with decimal point accepted`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnBikeWeightChange("12.5"))

            val state = viewModel.state.value
            assertThat(state.userSettingsUi.bikeWeightKg).isEqualTo("12.5")
            assertThat(state.userSettingsUi.bikeWeightError).isNull()
            assertThat(repository.lastSaved?.bikeWeightKg).isEqualTo(12.5)
        }

    @Test
    fun `bike type change saves`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnBikeTypeChange(com.speedevand.inkride.core.domain.settings.BikeType.MTB))
            assertThat(repository.lastSaved?.bikeType).isEqualTo(com.speedevand.inkride.core.domain.settings.BikeType.MTB)
        }

    @Test
    fun `tab selected updates state`() =
        runTest {
            val viewModel = SettingsViewModel(repository)

            viewModel.onAction(SettingsAction.OnTabSelected(SettingsTab.DISPLAY))
            assertThat(viewModel.state.value.selectedTab).isEqualTo(SettingsTab.DISPLAY)
        }

    @Test
    fun `save failure sends error event`() =
        runTest {
            repository.saveResult = Result.Error(DataError.Local.DISK_FULL)
            val viewModel = SettingsViewModel(repository)

            viewModel.events.test {
                viewModel.onAction(SettingsAction.OnWeightChange("80"))
                val event = awaitItem()
                assertThat(event is SettingsEvent.ShowError).isEqualTo(true)
            }
        }
}
