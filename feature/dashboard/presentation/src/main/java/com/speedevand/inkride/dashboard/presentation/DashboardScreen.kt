package com.speedevand.inkride.dashboard.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.speedevand.inkride.core.design_system.InkRideTheme
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.core.presentation.ObserveAsEvents
import com.speedevand.inkride.dashboard.presentation.components.ClearRouteConfirmationSheet
import com.speedevand.inkride.dashboard.presentation.components.DashboardActions
import com.speedevand.inkride.dashboard.presentation.components.DashboardTopBar
import com.speedevand.inkride.dashboard.presentation.components.GoalBottomSheet
import com.speedevand.inkride.dashboard.presentation.components.InfoBar
import com.speedevand.inkride.dashboard.presentation.components.LapGoalStatus
import com.speedevand.inkride.dashboard.presentation.components.MetricsPager
import com.speedevand.inkride.dashboard.presentation.components.RouteStatus
import com.speedevand.inkride.dashboard.presentation.components.VerticalPagerIndicator
import com.speedevand.inkride.dashboard.presentation.isActiveRide
import com.speedevand.inkride.dashboard.presentation.model.RideMetricsUi
import org.koin.androidx.compose.koinViewModel

@Composable
fun DashboardRoot(
    onOpenSettings: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: DashboardViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is DashboardEvent.ShowError -> {
                Toast
                    .makeText(
                        context,
                        event.message.asString(context),
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { }

    LaunchedEffect(Unit) {
        val permissions =
            mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions =
            permissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }

        if (missingPermissions.isNotEmpty()) {
            launcher.launch(missingPermissions.toTypedArray())
        }
    }

    DisposableEffect(state.status, state.userSettings.keepScreenOn) {
        val window = context.findActivity()?.window
        // Keep the screen awake during an active ride so the rider can glance at
        // live metrics. Gated on the user's preference; auto-paused still counts
        // as an active ride. The foreground service's PARTIAL_WAKE_LOCK keeps the
        // CPU/GPS alive independently when the screen is off.
        if (state.status.isActiveRide && state.userSettings.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(state.status) {
        // Any non-idle ride (including AUTO_PAUSED) must keep the foreground
        // service alive; only IDLE tears it down.
        if (state.status != TrackingStatus.IDLE) {
            onStartService()
        } else {
            onStopService()
        }
    }

    DashboardScreen(
        state = state,
        onAction = { action ->
            if (action is DashboardAction.OnOpenSettingsClick) {
                onOpenSettings()
            } else {
                viewModel.onAction(action)
            }
        },
    )
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@Composable
fun DashboardScreen(
    state: DashboardState,
    onAction: (DashboardAction) -> Unit,
) {
    var showGoalSheet by remember { mutableStateOf(false) }
    if (showGoalSheet) {
        GoalBottomSheet(
            units = state.userSettings.units,
            onDismiss = { showGoalSheet = false },
            onAction = onAction,
        )
    }

    var showClearRouteConfirm by remember { mutableStateOf(false) }
    if (showClearRouteConfirm) {
        ClearRouteConfirmationSheet(
            onDismiss = { showClearRouteConfirm = false },
            onConfirm = {
                onAction(DashboardAction.OnClearRoute)
                showClearRouteConfirm = false
            },
        )
    }

    // System file picker for a .gpx route. GPX has no reliable MIME type across
    // file providers, so accept any document and let the parser reject non-GPX.
    val routePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri -> uri?.let { onAction(DashboardAction.OnRouteSelected(it)) } }

    val pagerState =
        rememberPagerState(pageCount = {
            var count = 1
            val hasSecondary =
                state.userSettings.showMaxSpeed ||
                    state.userSettings.showElevationGain ||
                    state.userSettings.showCalories ||
                    state.userSettings.showAltitude ||
                    state.userSettings.showPower
            if (hasSecondary) count++
            if (state.userSettings.showCompass) count++
            count
        })

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            DashboardTopBar(
                status = state.status,
                hasRoute = state.route != null,
                onLoadRoute = { routePicker.launch(arrayOf("*/*")) },
                onClearRoute = { showClearRouteConfirm = true },
                onRecordLap = { onAction(DashboardAction.OnRecordLapClick) },
                onOpenGoal = { showGoalSheet = true },
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(DesignConstants.PADDING_MEDIUM),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesignConstants.SPACING_MEDIUM),
            ) {
                MetricsPager(
                    pagerState = pagerState,
                    metrics = state.rideMetrics,
                    settings = state.userSettings,
                    modifier = Modifier.weight(1f),
                )

                InfoBar(metrics = state.rideMetrics)

                RouteStatus(route = state.route)

                LapGoalStatus(state = state)

                HorizontalDividerMMD()

                DashboardActions(
                    status = state.status,
                    metrics = state.rideMetrics,
                    onAction = onAction,
                )
            }

            VerticalPagerIndicator(
                pagerState = pagerState,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = DesignConstants.PADDING_SMALL),
            )
        }
    }
}

@Preview
@Composable
private fun DashboardScreenPreview() {
    InkRideTheme {
        DashboardScreen(
            state =
                DashboardState(
                    status = TrackingStatus.TRACKING,
                    rideMetrics =
                        RideMetricsUi(
                            currentSpeedKmh = "27.4",
                            averageSpeedKmh = "23.8",
                            distanceKm = "18.42",
                            movingTime = "00:48:12",
                            gradePercent = "2.1",
                            gpsAccuracyM = "4",
                        ),
                ),
            onAction = {},
        )
    }
}
