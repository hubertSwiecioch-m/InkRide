package com.speedevand.inkride.settings.presentation

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.onFailure
import com.speedevand.inkride.core.domain.onSuccess
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.presentation.UiText
import com.speedevand.inkride.core.presentation.toUiText
import com.speedevand.inkride.settings.presentation.SettingsConstants.AGE_MAX
import com.speedevand.inkride.settings.presentation.SettingsConstants.AGE_MIN
import com.speedevand.inkride.settings.presentation.SettingsConstants.ALERT_HR_DEFAULT_MAX_BPM
import com.speedevand.inkride.settings.presentation.SettingsConstants.ALERT_HR_DEFAULT_MIN_BPM
import com.speedevand.inkride.settings.presentation.SettingsConstants.ALERT_SPEED_DEFAULT_KMH
import com.speedevand.inkride.settings.presentation.SettingsConstants.BIKE_WEIGHT_MAX_KG
import com.speedevand.inkride.settings.presentation.SettingsConstants.BIKE_WEIGHT_MAX_LBS
import com.speedevand.inkride.settings.presentation.SettingsConstants.BIKE_WEIGHT_MIN_KG
import com.speedevand.inkride.settings.presentation.SettingsConstants.BIKE_WEIGHT_MIN_LBS
import com.speedevand.inkride.settings.presentation.SettingsConstants.KMH_TO_MPH_FACTOR
import com.speedevand.inkride.settings.presentation.SettingsConstants.WEIGHT_FACTOR_LBS
import com.speedevand.inkride.settings.presentation.SettingsConstants.WEIGHT_MAX_KG
import com.speedevand.inkride.settings.presentation.SettingsConstants.WEIGHT_MAX_LBS
import com.speedevand.inkride.settings.presentation.SettingsConstants.WEIGHT_MIN_KG
import com.speedevand.inkride.settings.presentation.SettingsConstants.WEIGHT_MIN_LBS
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class SettingsViewModel(
    private val userSettingsRepository: UserSettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state = _state.asStateFlow()

    private val _events = Channel<SettingsEvent>()
    val events = _events.receiveAsFlow()

    init {
        observeUserSettings()
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val lang =
            if (currentLocales.isEmpty) {
                LocaleListCompat.getAdjustedDefault()[0]?.language ?: "en"
            } else {
                currentLocales[0]?.language ?: "en"
            }
        _state.update { it.copy(currentLanguageCode = lang) }
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.OnUserSettingsChanged -> {
                _state.update {
                    it.copy(
                        userSettings = action.settings,
                        userSettingsUi = action.settings.toUserSettingsUi(),
                    )
                }
                saveSettings(action.settings)
            }

            is SettingsAction.OnWeightChange -> {
                val isMetric = _state.value.userSettings.units == MeasurementUnits.METRIC
                val weightMin = (if (isMetric) WEIGHT_MIN_KG else WEIGHT_MIN_LBS).toInt()
                val weightMax = (if (isMetric) WEIGHT_MAX_KG else WEIGHT_MAX_LBS).toInt()
                val weightUnit = if (isMetric) "kg" else "lbs"
                val maxLength = weightMax.toString().length

                val filtered = action.weight.filter { it.isDigit() }
                var finalWeightText = filtered
                var numericValue = filtered.toIntOrNull()
                var error: UiText? = null

                if (numericValue != null) {
                    if (numericValue > weightMax) {
                        numericValue = weightMax
                        finalWeightText = weightMax.toString()
                    } else if (numericValue < weightMin) {
                        if (filtered.length >= maxLength) {
                            numericValue = weightMin
                            finalWeightText = weightMin.toString()
                        } else {
                            error =
                                UiText.StringResource(
                                    R.string.settings_error_weight_range,
                                    arrayOf(weightMin, weightMax, weightUnit),
                                )
                        }
                    }
                } else {
                    error =
                        UiText.StringResource(
                            R.string.settings_error_weight_range,
                            arrayOf(weightMin, weightMax, weightUnit),
                        )
                }

                _state.update {
                    it.copy(userSettingsUi = it.userSettingsUi.copy(weightKg = finalWeightText, weightError = error))
                }

                if (error == null && numericValue != null) {
                    val weightInKg = if (isMetric) numericValue else (numericValue / WEIGHT_FACTOR_LBS).roundToInt()
                    saveSettings(_state.value.userSettings.copy(weightKg = weightInKg))
                }
            }

            is SettingsAction.OnAgeChange -> {
                val ageMin = AGE_MIN.toInt()
                val ageMax = AGE_MAX.toInt()
                val maxLength = ageMax.toString().length

                val filtered = action.age.filter { it.isDigit() }
                var finalAgeText = filtered
                var numericValue = filtered.toIntOrNull()
                var error: UiText? = null

                if (numericValue != null) {
                    if (numericValue > ageMax) {
                        numericValue = ageMax
                        finalAgeText = ageMax.toString()
                    } else if (numericValue < ageMin) {
                        if (filtered.length >= maxLength) {
                            numericValue = ageMin
                            finalAgeText = ageMin.toString()
                        } else {
                            error =
                                UiText.StringResource(
                                    R.string.settings_error_age_range,
                                    arrayOf(ageMin, ageMax),
                                )
                        }
                    }
                } else {
                    error =
                        UiText.StringResource(
                            R.string.settings_error_age_range,
                            arrayOf(ageMin, ageMax),
                        )
                }

                _state.update {
                    it.copy(userSettingsUi = it.userSettingsUi.copy(age = finalAgeText, ageError = error))
                }

                if (error == null && numericValue != null) {
                    saveSettings(_state.value.userSettings.copy(age = numericValue))
                }
            }

            is SettingsAction.OnBikeWeightChange -> {
                val isMetric = _state.value.userSettings.units == MeasurementUnits.METRIC
                val weightMin = (if (isMetric) BIKE_WEIGHT_MIN_KG else BIKE_WEIGHT_MIN_LBS).toInt()
                val weightMax = (if (isMetric) BIKE_WEIGHT_MAX_KG else BIKE_WEIGHT_MAX_LBS).toInt()
                val weightUnit = if (isMetric) "kg" else "lbs"

                val filtered = action.weight.filter { it.isDigit() || it == '.' }
                var finalWeightText = filtered
                var numericValue = filtered.toDoubleOrNull()
                var error: UiText? = null

                if (numericValue != null) {
                    if (numericValue > weightMax) {
                        numericValue = weightMax.toDouble()
                        finalWeightText = weightMax.toString()
                    } else if (numericValue < weightMin) {
                        if (filtered.length >= weightMax.toString().length) {
                            numericValue = weightMin.toDouble()
                            finalWeightText = weightMin.toString()
                        } else {
                            error =
                                UiText.StringResource(
                                    R.string.settings_error_bike_weight_range,
                                    arrayOf(weightMin, weightMax, weightUnit),
                                )
                        }
                    }
                } else {
                    error =
                        UiText.StringResource(
                            R.string.settings_error_bike_weight_range,
                            arrayOf(weightMin, weightMax, weightUnit),
                        )
                }

                _state.update {
                    it.copy(userSettingsUi = it.userSettingsUi.copy(bikeWeightKg = finalWeightText, bikeWeightError = error))
                }

                if (error == null && numericValue != null) {
                    val weightInKg = if (isMetric) numericValue else (numericValue / WEIGHT_FACTOR_LBS)
                    saveSettings(_state.value.userSettings.copy(bikeWeightKg = weightInKg))
                }
            }

            is SettingsAction.OnBikeTypeChange -> {
                saveSettings(_state.value.userSettings.copy(bikeType = action.type))
            }

            is SettingsAction.OnLanguageChange -> {
                _state.update { it.copy(currentLanguageCode = action.languageCode) }
                viewModelScope.launch {
                    val newSettings = _state.value.userSettings.copy(languageCode = action.languageCode)
                    userSettingsRepository
                        .save(newSettings)
                        .onSuccess {
                            AppCompatDelegate.setApplicationLocales(
                                LocaleListCompat.forLanguageTags(action.languageCode),
                            )
                        }.onFailure { error ->
                            _events.send(SettingsEvent.ShowError(error.toUiText()))
                        }
                }
            }

            is SettingsAction.OnMaxSpeedAlertChange -> {
                // Allow a decimal point so a fractional threshold (e.g. "32.5")
                // isn't silently collapsed into an integer; toDoubleOrNull below
                // rejects malformed input (treating the alert as disabled).
                val filtered = action.value.filter { it.isDigit() || it == '.' }
                _state.update {
                    it.copy(userSettingsUi = it.userSettingsUi.copy(maxSpeedAlert = filtered))
                }
                // Only persist when input is a valid number; mid-edit empty/partial
                // strings must not null out the stored threshold and disable the alert.
                val isMetric = _state.value.userSettings.units == MeasurementUnits.METRIC
                val speedFactor = if (isMetric) 1.0 else KMH_TO_MPH_FACTOR
                val kmh = filtered.toDoubleOrNull()?.let { it / speedFactor }
                if (kmh != null) {
                    val current = _state.value.userSettings
                    saveSettings(current.copy(alerts = current.alerts.copy(maxSpeedKmh = kmh)))
                }
            }

            is SettingsAction.OnMaxSpeedAlertToggle -> {
                val current = _state.value.userSettings
                val isMetric = current.units == MeasurementUnits.METRIC
                val speedFactor = if (isMetric) 1.0 else KMH_TO_MPH_FACTOR
                if (action.enabled) {
                    val kept = _state.value.userSettingsUi.maxSpeedAlert
                    val kmh = kept.toDoubleOrNull()?.let { it / speedFactor } ?: ALERT_SPEED_DEFAULT_KMH
                    val display = kept.ifEmpty { String.format(Locale.ROOT, "%.0f", kmh * speedFactor) }
                    _state.update { it.copy(userSettingsUi = it.userSettingsUi.copy(maxSpeedAlert = display)) }
                    saveSettings(current.copy(alerts = current.alerts.copy(maxSpeedKmh = kmh)))
                } else {
                    saveSettings(current.copy(alerts = current.alerts.copy(maxSpeedKmh = null)))
                }
            }

            is SettingsAction.OnHrMinAlertChange -> {
                val filtered = action.value.filter { it.isDigit() }
                _state.update {
                    it.copy(userSettingsUi = it.userSettingsUi.copy(hrMinAlert = filtered))
                }
                val bpm = filtered.toIntOrNull()
                if (bpm != null) {
                    val current = _state.value.userSettings
                    saveSettings(current.copy(alerts = current.alerts.copy(hrZoneMinBpm = bpm)))
                }
            }

            is SettingsAction.OnHrMinAlertToggle -> {
                val current = _state.value.userSettings
                if (action.enabled) {
                    val kept = _state.value.userSettingsUi.hrMinAlert
                    val bpm = kept.toIntOrNull() ?: ALERT_HR_DEFAULT_MIN_BPM
                    val display = kept.ifEmpty { bpm.toString() }
                    _state.update { it.copy(userSettingsUi = it.userSettingsUi.copy(hrMinAlert = display)) }
                    saveSettings(current.copy(alerts = current.alerts.copy(hrZoneMinBpm = bpm)))
                } else {
                    saveSettings(current.copy(alerts = current.alerts.copy(hrZoneMinBpm = null)))
                }
            }

            is SettingsAction.OnHrMaxAlertChange -> {
                val filtered = action.value.filter { it.isDigit() }
                _state.update {
                    it.copy(userSettingsUi = it.userSettingsUi.copy(hrMaxAlert = filtered))
                }
                val bpm = filtered.toIntOrNull()
                if (bpm != null) {
                    val current = _state.value.userSettings
                    saveSettings(current.copy(alerts = current.alerts.copy(hrZoneMaxBpm = bpm)))
                }
            }

            is SettingsAction.OnHrMaxAlertToggle -> {
                val current = _state.value.userSettings
                if (action.enabled) {
                    val kept = _state.value.userSettingsUi.hrMaxAlert
                    val bpm = kept.toIntOrNull() ?: ALERT_HR_DEFAULT_MAX_BPM
                    val display = kept.ifEmpty { bpm.toString() }
                    _state.update { it.copy(userSettingsUi = it.userSettingsUi.copy(hrMaxAlert = display)) }
                    saveSettings(current.copy(alerts = current.alerts.copy(hrZoneMaxBpm = bpm)))
                } else {
                    saveSettings(current.copy(alerts = current.alerts.copy(hrZoneMaxBpm = null)))
                }
            }

            SettingsAction.OnBackClick -> {
                _state.update { it.copy(userSettingsUi = it.userSettings.toUserSettingsUi()) }
                viewModelScope.launch {
                    _events.send(SettingsEvent.OnBack)
                }
            }

            is SettingsAction.OnTabSelected -> {
                _state.update { it.copy(selectedTab = action.tab) }
            }

            SettingsAction.OnBluetoothSensorsClick -> {
                viewModelScope.launch {
                    _events.send(SettingsEvent.OpenBleSensors)
                }
            }

            SettingsAction.OnBikeProfilesClick -> {
                viewModelScope.launch {
                    _events.send(SettingsEvent.OpenBikeProfiles)
                }
            }
        }
    }

    private fun saveSettings(settings: UserSettings) {
        _state.update { it.copy(userSettings = settings) }
        viewModelScope.launch {
            userSettingsRepository.save(settings).onFailure { error ->
                _events.send(SettingsEvent.ShowError(error.toUiText()))
            }
        }
    }

    private fun observeUserSettings() {
        viewModelScope.launch {
            userSettingsRepository.observeSettings().collect { settings ->
                if (settings != _state.value.userSettings) {
                    _state.update {
                        it.copy(
                            userSettings = settings,
                            userSettingsUi = settings.toUserSettingsUi(),
                            currentLanguageCode = settings.languageCode,
                        )
                    }
                }
            }
        }
    }
}
