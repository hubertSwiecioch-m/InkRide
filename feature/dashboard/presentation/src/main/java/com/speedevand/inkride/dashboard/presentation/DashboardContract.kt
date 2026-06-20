package com.speedevand.inkride.dashboard.presentation

import android.net.Uri
import androidx.compose.runtime.Stable
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.tracking.RideGoal
import com.speedevand.inkride.core.presentation.UiText
import com.speedevand.inkride.dashboard.presentation.model.GoalProgressUi
import com.speedevand.inkride.dashboard.presentation.model.LapSummaryUi
import com.speedevand.inkride.dashboard.presentation.model.RideMetricsUi
import com.speedevand.inkride.dashboard.presentation.model.RouteProgressUi

// Tracking status is owned by the domain RideTracker; re-exported here so the
// presentation layer keeps a stable, package-local name.
typealias TrackingStatus = com.speedevand.inkride.core.domain.tracking.TrackingStatus

// Shared "is there a ride in progress" check, used to gate ride-only UI (lap/goal
// controls, keep-screen-on) across the dashboard screen and its components.
val TrackingStatus.isActiveRide: Boolean
    get() = this == TrackingStatus.TRACKING || this == TrackingStatus.AUTO_PAUSED

sealed interface DashboardAction {
    data object OnToggleTrackingClick : DashboardAction

    data object OnStopClick : DashboardAction

    data object OnResetClick : DashboardAction

    data object OnOpenSettingsClick : DashboardAction

    data object OnRecordLapClick : DashboardAction

    data class OnSetGoal(
        val goal: RideGoal,
    ) : DashboardAction

    data object OnClearGoal : DashboardAction

    data class OnRouteSelected(
        val uri: Uri,
    ) : DashboardAction

    data object OnClearRoute : DashboardAction
}

sealed interface DashboardEvent {
    data class ShowError(
        val message: UiText,
    ) : DashboardEvent
}

@Stable
data class DashboardState(
    val rideMetrics: RideMetricsUi = RideMetricsUi(),
    val status: TrackingStatus = TrackingStatus.IDLE,
    val userSettings: UserSettings = UserSettings(weightKg = 75, age = 30),
    val lastLap: LapSummaryUi? = null,
    val goal: GoalProgressUi? = null,
    val route: RouteProgressUi? = null,
)
