package com.speedevand.inkride.dashboard.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.core.domain.tracking.TrackingStatus
import com.speedevand.inkride.core.presentation.UiText
import com.speedevand.inkride.dashboard.presentation.model.goalProgressUi
import com.speedevand.inkride.dashboard.presentation.model.routeProgressUi
import com.speedevand.inkride.dashboard.presentation.model.toRideMetricsUi
import com.speedevand.inkride.dashboard.presentation.model.toSummaryUi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val rideTracker: RideTracker,
    private val userSettingsRepository: UserSettingsRepository,
    private val routeLoader: GpxRouteLoader,
) : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state = _state.asStateFlow()

    private val _events = Channel<DashboardEvent>()
    val events = _events.receiveAsFlow()

    init {
        observeTracking()
        observeErrors()
    }

    fun onAction(action: DashboardAction) {
        when (action) {
            DashboardAction.OnToggleTrackingClick -> toggleTracking()
            DashboardAction.OnStopClick -> rideTracker.stop()
            DashboardAction.OnResetClick -> rideTracker.stop()
            DashboardAction.OnOpenSettingsClick -> Unit
            DashboardAction.OnRecordLapClick -> rideTracker.recordLap()
            is DashboardAction.OnSetGoal -> rideTracker.setGoal(action.goal)
            DashboardAction.OnClearGoal -> rideTracker.clearGoal()
            is DashboardAction.OnRouteSelected -> loadRoute(action.uri)
            DashboardAction.OnClearRoute -> rideTracker.clearRoute()
        }
    }

    private fun loadRoute(uri: Uri) {
        viewModelScope.launch {
            when (val result = routeLoader.load(uri)) {
                is Result.Success -> {
                    rideTracker.loadRoute(result.data)
                }

                is Result.Error -> {
                    _events.send(
                        DashboardEvent.ShowError(UiText.StringResource(result.error.messageRes())),
                    )
                }
            }
        }
    }

    // The ride lives in the process-scoped RideTracker; the ViewModel is a thin
    // observer that maps the tracker's state and the user's units into UI state.
    private fun observeTracking() {
        viewModelScope.launch {
            combine(
                rideTracker.state,
                userSettingsRepository.observeSettings(),
            ) { tracking, settings ->
                DashboardState(
                    rideMetrics = tracking.metrics.toRideMetricsUi(settings.units),
                    status = tracking.status,
                    userSettings = settings,
                    lastLap = tracking.laps.lastOrNull()?.toSummaryUi(settings.units),
                    goal = tracking.activeGoal?.let { goalProgressUi(it, tracking.metrics, settings.units) },
                    route =
                        tracking.activeRoute?.let {
                            routeProgressUi(it, tracking.routeProgress, settings.units)
                        },
                )
            }.collect { newState -> _state.value = newState }
        }
    }

    private fun observeErrors() {
        viewModelScope.launch {
            rideTracker.errors.collect { error ->
                _events.send(DashboardEvent.ShowError(error.toUiText()))
            }
        }
    }

    private fun toggleTracking() {
        when (rideTracker.state.value.status) {
            TrackingStatus.IDLE -> rideTracker.start()

            TrackingStatus.TRACKING -> rideTracker.pause()

            // start() resumes both a manual PAUSED and an AUTO_PAUSED ride back
            // to TRACKING (and clears the auto-pause timer).
            TrackingStatus.PAUSED, TrackingStatus.AUTO_PAUSED -> rideTracker.start()
        }
    }
}

private fun GpxLoadError.messageRes(): Int =
    when (this) {
        GpxLoadError.READ_FAILED -> R.string.dashboard_route_error_read
        GpxLoadError.EMPTY -> R.string.dashboard_route_error_empty
        GpxLoadError.MALFORMED -> R.string.dashboard_route_error_malformed
    }
