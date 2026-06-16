package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.dashboard.presentation.TrackingStatus
import com.speedevand.inkride.dashboard.presentation.isActiveRide

/**
 * Slim app bar that hosts the ride status (left) and the secondary, less-frequent
 * controls as compact icon actions (right): record lap and set goal during an
 * active ride, plus load/clear route. Moving these off the main column keeps the
 * dashboard body free for the metrics and the primary Start/Stop controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardTopBar(
    status: TrackingStatus,
    hasRoute: Boolean,
    onLoadRoute: () -> Unit,
    onClearRoute: () -> Unit,
    onRecordLap: () -> Unit,
    onOpenGoal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActiveRide = status.isActiveRide

    TopAppBarMMD(
        modifier = modifier,
        // The app-level Scaffold already offsets content below the status bar;
        // zero the bar's own insets so it doesn't add a second top gap.
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
            StatusIndicator(status = status)
        },
        actions = {
            if (isActiveRide) {
                IconButton(onClick = onRecordLap) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = stringResource(R.string.dashboard_action_lap)
                    )
                }
                IconButton(onClick = onOpenGoal) {
                    Icon(
                        imageVector = Icons.Filled.Flag,
                        contentDescription = stringResource(R.string.dashboard_action_goal)
                    )
                }
            }
            // One toggle: load a route when none is active, clear it otherwise.
            // The glyph itself signals the action (route vs. clear).
            IconButton(
                onClick = { if (hasRoute) onClearRoute() else onLoadRoute() },
                modifier = Modifier.padding(end = DesignConstants.PADDING_TINY)
            ) {
                if (hasRoute) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.dashboard_route_clear)
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.AltRoute,
                        contentDescription = stringResource(R.string.dashboard_route_load)
                    )
                }
            }
        }
    )
}
