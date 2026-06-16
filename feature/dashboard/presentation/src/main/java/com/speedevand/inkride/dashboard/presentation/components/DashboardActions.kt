package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.dashboard.presentation.DashboardAction
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.DISTANCE_ZERO
import com.speedevand.inkride.dashboard.presentation.DashboardConstants.TIME_ZERO
import com.speedevand.inkride.dashboard.presentation.TrackingStatus
import com.speedevand.inkride.dashboard.presentation.model.RideMetricsUi
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD

@Composable
fun DashboardActions(
    status: TrackingStatus,
    metrics: RideMetricsUi,
    onAction: (DashboardAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DesignConstants.SPACING_MEDIUM)
    ) {
        val hasSessionData = metrics.distanceKm != DISTANCE_ZERO ||
                metrics.movingTime != TIME_ZERO
        val showSecondary = status != TrackingStatus.IDLE || hasSessionData

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DesignConstants.SPACING_MEDIUM)
        ) {
            val primaryWeight = 1.5f
            val secondaryWeight = 1f
            ButtonMMD(
                modifier = if (showSecondary) Modifier.weight(primaryWeight) else Modifier.fillMaxWidth(),
                onClick = { onAction(DashboardAction.OnToggleTrackingClick) }
            ) {
                TextMMD(
                    text = when (status) {
                        TrackingStatus.IDLE -> stringResource(R.string.dashboard_action_start)
                        TrackingStatus.TRACKING -> stringResource(R.string.dashboard_action_pause)
                        TrackingStatus.PAUSED, TrackingStatus.AUTO_PAUSED ->
                            stringResource(R.string.dashboard_action_resume)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (showSecondary) {
                ButtonMMD(
                    modifier = Modifier.weight(secondaryWeight),
                    onClick = {
                        if (status != TrackingStatus.IDLE) {
                            onAction(DashboardAction.OnStopClick)
                        } else {
                            onAction(DashboardAction.OnResetClick)
                        }
                    }
                ) {
                    TextMMD(
                        text = if (status != TrackingStatus.IDLE) {
                            stringResource(R.string.dashboard_action_stop)
                        } else {
                            stringResource(R.string.dashboard_action_reset)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
