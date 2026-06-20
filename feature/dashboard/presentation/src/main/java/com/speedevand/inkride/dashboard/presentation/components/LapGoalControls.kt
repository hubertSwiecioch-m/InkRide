package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.tracking.RideGoal
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.dashboard.presentation.DashboardAction
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.KM_TO_MI_FACTOR
import com.speedevand.inkride.dashboard.presentation.DashboardState
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.dashboard.presentation.isActiveRide

/**
 * Read-only last-lap summary and active-goal label. Only shown during an active
 * ride (tracking or auto-paused), and only when there is something to report.
 * Recording a lap and opening the goal editor are driven from the top bar
 * ([DashboardTopBar]) — this strip is just the live readout.
 */
@Composable
fun LapGoalStatus(
    state: DashboardState,
    modifier: Modifier = Modifier,
) {
    if (!state.status.isActiveRide) return
    if (state.lastLap == null && state.goal == null) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_TINY),
    ) {
        state.goal?.let { goal ->
            TextMMD(
                text =
                    if (goal.reached) {
                        stringResource(R.string.dashboard_goal_reached)
                    } else {
                        stringResource(R.string.dashboard_goal_remaining, goal.remainingValue, goal.unitLabel)
                    },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        state.lastLap?.let { lap ->
            TextMMD(
                text =
                    stringResource(
                        R.string.dashboard_last_lap,
                        lap.distance,
                        lap.time,
                        lap.averageSpeed,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalBottomSheet(
    units: MeasurementUnits,
    onDismiss: () -> Unit,
    onAction: (DashboardAction) -> Unit,
) {
    var isDistance by remember { mutableStateOf(true) }
    var value by remember { mutableStateOf("") }
    val imperial = units == MeasurementUnits.IMPERIAL
    val distanceUnit = if (imperial) "mi" else "km"

    ModalBottomSheetMMD(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextMMD(
                text = stringResource(R.string.dashboard_goal_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            GoalTypeRow(
                label = stringResource(R.string.dashboard_goal_type_distance),
                selected = isDistance,
                onClick = { isDistance = true },
            )
            GoalTypeRow(
                label = stringResource(R.string.dashboard_goal_type_duration),
                selected = !isDistance,
                onClick = { isDistance = false },
            )

            TextFieldMMD(
                value = value,
                onValueChange = { value = it },
                label = {
                    TextMMD(
                        text =
                            if (isDistance) {
                                "${stringResource(R.string.dashboard_goal_distance_hint)} ($distanceUnit)"
                            } else {
                                stringResource(R.string.dashboard_goal_duration_hint)
                            },
                    )
                },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (isDistance) KeyboardType.Decimal else KeyboardType.Number,
                    ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedButtonMMD(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onAction(DashboardAction.OnClearGoal)
                        onDismiss()
                    },
                ) {
                    TextMMD(text = stringResource(R.string.dashboard_goal_clear))
                }
                ButtonMMD(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        buildGoal(isDistance, value, imperial)?.let { onAction(DashboardAction.OnSetGoal(it)) }
                        onDismiss()
                    },
                ) {
                    TextMMD(text = stringResource(R.string.dashboard_goal_set))
                }
            }
        }
    }
}

@Composable
private fun GoalTypeRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyLarge)
        RadioButtonMMD(selected = selected, onClick = onClick)
    }
}

private fun buildGoal(
    isDistance: Boolean,
    raw: String,
    imperial: Boolean,
): RideGoal? {
    val parsed = raw.trim().toDoubleOrNull() ?: return null
    if (parsed <= 0.0) return null
    return if (isDistance) {
        // Input is in the user's distance unit; store the target in km.
        val km = if (imperial) parsed / KM_TO_MI_FACTOR else parsed
        RideGoal.Distance(targetKm = km)
    } else {
        RideGoal.Duration(targetSeconds = (parsed * 60.0).toLong())
    }
}
