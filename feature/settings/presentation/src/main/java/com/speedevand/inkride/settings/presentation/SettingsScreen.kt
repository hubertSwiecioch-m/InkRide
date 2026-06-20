package com.speedevand.inkride.settings.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.speedevand.inkride.core.design_system.InkRideTheme
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.tabs.PrimaryTabRowMMD
import com.mudita.mmd.components.tabs.TabMMD
import com.mudita.mmd.components.tabs.TabRowDefaultsMMD
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.presentation.ObserveAsEvents
import com.speedevand.inkride.core.presentation.verticalScrollbar
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.settings.presentation.SettingsConstants.ALERT_HR_MAX_BPM
import com.speedevand.inkride.settings.presentation.SettingsConstants.ALERT_HR_MIN_BPM
import com.speedevand.inkride.settings.presentation.SettingsConstants.ALERT_HR_STEP
import com.speedevand.inkride.settings.presentation.SettingsConstants.ALERT_SPEED_MAX_KMH
import com.speedevand.inkride.settings.presentation.SettingsConstants.ALERT_SPEED_MIN_KMH
import com.speedevand.inkride.settings.presentation.SettingsConstants.ALERT_SPEED_STEP
import com.speedevand.inkride.settings.presentation.SettingsConstants.KMH_TO_MPH_FACTOR
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

// MMD's default PrimaryIndicator height (3.dp) is hard to see on E-Ink; thicken it for visibility.
private val TAB_INDICATOR_HEIGHT = 6.dp

@Composable
fun SettingsRoot(
    onBack: () -> Unit,
    onOpenBleSensors: () -> Unit,
    onOpenBikeProfiles: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is SettingsEvent.OnBack -> onBack()
            is SettingsEvent.OpenBleSensors -> onOpenBleSensors()
            is SettingsEvent.OpenBikeProfiles -> onOpenBikeProfiles()
            is SettingsEvent.ShowError ->
                Toast.makeText(context, event.message.asString(context), Toast.LENGTH_LONG).show()
        }
    }

    SettingsScreen(
        state = state,
        onAction = viewModel::onAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    onAction: (SettingsAction) -> Unit
) {
    val scrollState = rememberScrollState()
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextMMD(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            PrimaryTabRowMMD(
                selectedTabIndex = state.selectedTab.ordinal,
                indicator = {
                    TabRowDefaultsMMD.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(
                            state.selectedTab.ordinal,
                            matchContentSize = true
                        ),
                        width = Dp.Unspecified,
                        height = TAB_INDICATOR_HEIGHT
                    )
                }
            ) {
                SettingsTab.entries.forEach { tab ->
                    TabMMD(
                        selected = state.selectedTab == tab,
                        onClick = { onAction(SettingsAction.OnTabSelected(tab)) },
                        text = {
                            TextMMD(
                                text = when (tab) {
                                    SettingsTab.PROFILE -> stringResource(R.string.settings_tab_profile)
                                    SettingsTab.BIKE -> stringResource(R.string.settings_tab_bike)
                                    SettingsTab.DISPLAY -> stringResource(R.string.settings_tab_display)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScrollbar(scrollState)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp)
                    .padding(top = 12.dp)
            ) {
                when (state.selectedTab) {
                    SettingsTab.PROFILE -> ProfileSection(state, onAction)
                    SettingsTab.BIKE -> BikeSection(state, onAction)
                    SettingsTab.DISPLAY -> DisplaySection(state, onAction)
                }
            }
        }
    }
}

// ── Tabs ───────────────────────────────────────────────────────────────────

@Composable
private fun ProfileSection(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    val isMetric = state.userSettings.units == MeasurementUnits.METRIC
    val weightUnit = if (isMetric) "kg" else "lbs"
    val weightMin = (if (isMetric) SettingsConstants.WEIGHT_MIN_KG else SettingsConstants.WEIGHT_MIN_LBS).toDouble()
    val weightMax = (if (isMetric) SettingsConstants.WEIGHT_MAX_KG else SettingsConstants.WEIGHT_MAX_LBS).toDouble()

    Column {
        SectionHeader(stringResource(R.string.settings_section_personal))

        NumberStepperRow(
            label = stringResource(R.string.settings_label_weight),
            value = state.userSettingsUi.weightKg,
            unit = weightUnit,
            step = 1.0,
            min = weightMin,
            max = weightMax,
            onValueChange = { onAction(SettingsAction.OnWeightChange(it)) }
        )

        NumberStepperRow(
            label = stringResource(R.string.settings_label_age),
            value = state.userSettingsUi.age,
            unit = stringResource(R.string.settings_unit_years),
            step = 1.0,
            min = SettingsConstants.AGE_MIN.toDouble(),
            max = SettingsConstants.AGE_MAX.toDouble(),
            onValueChange = { onAction(SettingsAction.OnAgeChange(it)) }
        )

        SectionDivider()
        SectionHeader(stringResource(R.string.settings_language))

        listOf(
            "en" to R.string.settings_language_english,
            "pl" to R.string.settings_language_polish
        ).forEach { (code, labelRes) ->
            SettingRadioRow(
                label = stringResource(labelRes),
                selected = state.currentLanguageCode.startsWith(code),
                onClick = { onAction(SettingsAction.OnLanguageChange(code)) }
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BikeSection(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    val isMetric = state.userSettings.units == MeasurementUnits.METRIC
    val weightUnit = if (isMetric) "kg" else "lbs"
    val bikeWeightMin = (if (isMetric) SettingsConstants.BIKE_WEIGHT_MIN_KG else SettingsConstants.BIKE_WEIGHT_MIN_LBS).toDouble()
    val bikeWeightMax = (if (isMetric) SettingsConstants.BIKE_WEIGHT_MAX_KG else SettingsConstants.BIKE_WEIGHT_MAX_LBS).toDouble()

    Column {
        SectionHeader(stringResource(R.string.settings_section_bike))

        SettingNavRow(
            label = stringResource(R.string.settings_bike_profiles),
            actionLabel = stringResource(R.string.settings_bike_profiles_open),
            onClick = { onAction(SettingsAction.OnBikeProfilesClick) }
        )

        if (state.userSettings.activeBikeProfileId == null) {
            SectionDivider()
            SectionHeader(stringResource(R.string.settings_section_default_bike))

            NumberStepperRow(
                label = stringResource(R.string.settings_label_bike_weight),
                value = state.userSettingsUi.bikeWeightKg,
                unit = weightUnit,
                step = 0.5,
                min = bikeWeightMin,
                max = bikeWeightMax,
                onValueChange = { onAction(SettingsAction.OnBikeWeightChange(it)) },
                isDecimal = true
            )

            Spacer(Modifier.height(8.dp))
            TextMMD(
                text = stringResource(R.string.settings_bike_type),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )

            BikeType.entries.forEach { type ->
                SettingRadioRow(
                    label = stringResource(type.labelRes()),
                    selected = state.userSettings.bikeType == type,
                    onClick = { onAction(SettingsAction.OnBikeTypeChange(type)) }
                )
            }
        }

        SectionDivider()
        SectionHeader(stringResource(R.string.settings_section_sensors))

        SettingNavRow(
            label = stringResource(R.string.settings_bluetooth_sensors),
            actionLabel = stringResource(R.string.settings_bluetooth_sensors_open),
            onClick = { onAction(SettingsAction.OnBluetoothSensorsClick) }
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DisplaySection(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    Column {
        SectionHeader(stringResource(R.string.settings_section_units))

        MeasurementUnits.entries.forEach { unit ->
            SettingRadioRow(
                label = when (unit) {
                    MeasurementUnits.METRIC -> stringResource(R.string.settings_units_metric)
                    MeasurementUnits.IMPERIAL -> stringResource(R.string.settings_units_imperial)
                },
                selected = state.userSettings.units == unit,
                onClick = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(units = unit))) }
            )
        }

        SectionDivider()
        SectionHeader(stringResource(R.string.settings_section_metrics))

        DashboardSettingRow(
            label = stringResource(R.string.settings_show_distance),
            checked = state.userSettings.showDistance,
            onCheckedChange = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(showDistance = it))) }
        )
        DashboardSettingRow(
            label = stringResource(R.string.settings_show_moving_time),
            checked = state.userSettings.showMovingTime,
            onCheckedChange = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(showMovingTime = it))) }
        )
        DashboardSettingRow(
            label = stringResource(R.string.settings_show_average_speed),
            checked = state.userSettings.showAverageSpeed,
            onCheckedChange = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(showAverageSpeed = it))) }
        )
        DashboardSettingRow(
            label = stringResource(R.string.settings_show_max_speed),
            checked = state.userSettings.showMaxSpeed,
            onCheckedChange = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(showMaxSpeed = it))) }
        )
        DashboardSettingRow(
            label = stringResource(R.string.settings_show_elevation_gain),
            checked = state.userSettings.showElevationGain,
            onCheckedChange = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(showElevationGain = it))) }
        )
        DashboardSettingRow(
            label = stringResource(R.string.settings_show_calories),
            checked = state.userSettings.showCalories,
            onCheckedChange = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(showCalories = it))) }
        )
        DashboardSettingRow(
            label = stringResource(R.string.settings_show_altitude),
            checked = state.userSettings.showAltitude,
            onCheckedChange = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(showAltitude = it))) }
        )
        DashboardSettingRow(
            label = stringResource(R.string.settings_show_grade),
            checked = state.userSettings.showGrade,
            onCheckedChange = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(showGrade = it))) }
        )
        DashboardSettingRow(
            label = stringResource(R.string.settings_show_power),
            checked = state.userSettings.showPower,
            onCheckedChange = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(showPower = it))) }
        )
        DashboardSettingRow(
            label = stringResource(R.string.settings_show_compass),
            checked = state.userSettings.showCompass,
            onCheckedChange = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(showCompass = it))) }
        )

        SectionDivider()
        SectionHeader(stringResource(R.string.settings_section_behavior))

        DashboardSettingRow(
            label = stringResource(R.string.settings_keep_screen_on),
            checked = state.userSettings.keepScreenOn,
            onCheckedChange = { onAction(SettingsAction.OnUserSettingsChanged(state.userSettings.copy(keepScreenOn = it))) }
        )

        SectionDivider()
        AlertsSection(state, onAction)

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AlertsSection(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    val isMetric = state.userSettings.units == MeasurementUnits.METRIC
    val speedUnit = if (isMetric) "km/h" else "mph"
    val speedFactor = if (isMetric) 1.0 else KMH_TO_MPH_FACTOR
    val speedMin = ALERT_SPEED_MIN_KMH * speedFactor
    val speedMax = ALERT_SPEED_MAX_KMH * speedFactor

    SectionHeader(stringResource(R.string.settings_section_alerts))

    AlertSwitchRow(
        label = stringResource(R.string.settings_alert_max_speed),
        enabled = state.userSettings.alerts.maxSpeedKmh != null,
        value = state.userSettingsUi.maxSpeedAlert,
        unit = speedUnit,
        step = ALERT_SPEED_STEP,
        min = speedMin,
        max = speedMax,
        onToggle = { onAction(SettingsAction.OnMaxSpeedAlertToggle(it)) },
        onStepChange = { onAction(SettingsAction.OnMaxSpeedAlertChange(it)) }
    )

    AlertSwitchRow(
        label = stringResource(R.string.settings_alert_hr_min),
        enabled = state.userSettings.alerts.hrZoneMinBpm != null,
        value = state.userSettingsUi.hrMinAlert,
        unit = "bpm",
        step = ALERT_HR_STEP,
        min = ALERT_HR_MIN_BPM.toDouble(),
        max = ALERT_HR_MAX_BPM.toDouble(),
        onToggle = { onAction(SettingsAction.OnHrMinAlertToggle(it)) },
        onStepChange = { onAction(SettingsAction.OnHrMinAlertChange(it)) }
    )

    AlertSwitchRow(
        label = stringResource(R.string.settings_alert_hr_max),
        enabled = state.userSettings.alerts.hrZoneMaxBpm != null,
        value = state.userSettingsUi.hrMaxAlert,
        unit = "bpm",
        step = ALERT_HR_STEP,
        min = ALERT_HR_MIN_BPM.toDouble(),
        max = ALERT_HR_MAX_BPM.toDouble(),
        onToggle = { onAction(SettingsAction.OnHrMaxAlertToggle(it)) },
        onStepChange = { onAction(SettingsAction.OnHrMaxAlertChange(it)) }
    )
}

// ── Shared section primitives ──────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(8.dp))
    HorizontalDividerMMD()
    Spacer(Modifier.height(12.dp))
}

// ── Row composables ────────────────────────────────────────────────────────

@Composable
private fun SettingNavRow(label: String, actionLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyLarge)
        TextMMD(
            text = actionLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SettingRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyLarge)
        RadioButtonMMD(selected = selected, onClick = onClick)
    }
}

@Composable
private fun DashboardSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextMMD(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        SwitchMMD(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NumberStepperRow(
    label: String,
    value: String,
    unit: String,
    step: Double,
    min: Double,
    max: Double,
    onValueChange: (String) -> Unit,
    isDecimal: Boolean = false,
    modifier: Modifier = Modifier
) {
    val numeric = value.toDoubleOrNull()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextMMD(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButtonMMD(
                onClick = {
                    val current = numeric ?: min
                    val next = (current - step).coerceIn(min, max)
                    onValueChange(
                        if (isDecimal) String.format(Locale.ROOT, "%.1f", next)
                        else String.format(Locale.ROOT, "%.0f", next)
                    )
                }
            ) {
                TextMMD("−")
            }
            TextMMD(
                text = "$value $unit",
                style = MaterialTheme.typography.bodyLarge
            )
            OutlinedButtonMMD(
                onClick = {
                    val current = numeric ?: (min - step)
                    val next = (current + step).coerceIn(min, max)
                    onValueChange(
                        if (isDecimal) String.format(Locale.ROOT, "%.1f", next)
                        else String.format(Locale.ROOT, "%.0f", next)
                    )
                }
            ) {
                TextMMD("+")
            }
        }
    }
}

@Composable
private fun AlertSwitchRow(
    label: String,
    enabled: Boolean,
    value: String,
    unit: String,
    step: Double,
    min: Double,
    max: Double,
    onToggle: (Boolean) -> Unit,
    onStepChange: (String) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextMMD(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            )
            SwitchMMD(checked = enabled, onCheckedChange = onToggle)
        }
        if (enabled) {
            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButtonMMD(onClick = {
                    val current = value.toDoubleOrNull() ?: min
                    val next = (current - step).coerceIn(min, max)
                    onStepChange(String.format(Locale.ROOT, "%.0f", next))
                }) {
                    TextMMD("−")
                }
                TextMMD(
                    text = "$value $unit",
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButtonMMD(onClick = {
                    val current = value.toDoubleOrNull() ?: (min - step)
                    val next = (current + step).coerceIn(min, max)
                    onStepChange(String.format(Locale.ROOT, "%.0f", next))
                }) {
                    TextMMD("+")
                }
            }
        }
    }
}

private fun BikeType.labelRes(): Int = when (this) {
    BikeType.ROAD -> R.string.settings_bike_type_road
    BikeType.MTB -> R.string.settings_bike_type_mtb
    BikeType.CITY -> R.string.settings_bike_type_city
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    InkRideTheme {
        SettingsScreen(
            state = SettingsState(
                userSettingsUi = UserSettingsUi(weightKg = "75", age = "30", bikeWeightKg = "9.5")
            ),
            onAction = {}
        )
    }
}
