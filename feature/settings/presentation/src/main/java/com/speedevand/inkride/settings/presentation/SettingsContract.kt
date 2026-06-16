package com.speedevand.inkride.settings.presentation

import com.speedevand.inkride.core.presentation.UiText
import com.speedevand.inkride.core.domain.settings.UserSettings

sealed interface SettingsAction {
    data class OnUserSettingsChanged(val settings: UserSettings) : SettingsAction
    data class OnWeightChange(val weight: String) : SettingsAction
    data class OnAgeChange(val age: String) : SettingsAction
    data class OnBikeWeightChange(val weight: String) : SettingsAction
    data class OnBikeTypeChange(val type: com.speedevand.inkride.core.domain.settings.BikeType) : SettingsAction
    data class OnLanguageChange(val languageCode: String) : SettingsAction
    data class OnMaxSpeedAlertChange(val value: String) : SettingsAction
    data class OnMaxSpeedAlertToggle(val enabled: Boolean) : SettingsAction
    data class OnHrMinAlertChange(val value: String) : SettingsAction
    data class OnHrMinAlertToggle(val enabled: Boolean) : SettingsAction
    data class OnHrMaxAlertChange(val value: String) : SettingsAction
    data class OnHrMaxAlertToggle(val enabled: Boolean) : SettingsAction
    data object OnBackClick : SettingsAction
    data object OnBluetoothSensorsClick : SettingsAction
    data object OnBikeProfilesClick : SettingsAction
    data class OnTabSelected(val tab: SettingsTab) : SettingsAction
}

sealed interface SettingsEvent {
    data class ShowError(val message: UiText) : SettingsEvent
    data object OnBack : SettingsEvent
    data object OpenBleSensors : SettingsEvent
    data object OpenBikeProfiles : SettingsEvent
}

enum class SettingsTab {
    PROFILE,
    BIKE,
    DISPLAY
}

data class SettingsState(
    val userSettings: UserSettings = UserSettings(weightKg = 75, age = 30),
    val userSettingsUi: UserSettingsUi = UserSettingsUi(),
    val selectedTab: SettingsTab = SettingsTab.PROFILE,
    val currentLanguageCode: String = "en"
)
