package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isInstanceOf
import com.speedevand.inkride.core.domain.settings.AlertConfig
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.tracking.RideAlert
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.tracking.support.dashboardString
import com.speedevand.inkride.tracking.support.waitUntilTagText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.koin.core.context.GlobalContext

class RideTrackingGoalAndAlertTest : RideTrackingE2ETestBase() {
    override fun seedSettings(): UserSettings =
        super.seedSettings().copy(alerts = AlertConfig(maxSpeedKmh = 15.0))

    @Test
    fun settingADistanceGoalShowsProgressThenReached() {
        startRideAndSettle()

        composeTestRule.onNodeWithTag(DashboardTestTags.GOAL_BUTTON).performClick()
        // Distance is the default goal type in the sheet; 0.05 km ≈ 9-10s of
        // riding at 20 km/h.
        composeTestRule.onNodeWithTag(DashboardTestTags.GOAL_VALUE_FIELD).performTextInput("0.05")
        composeTestRule.onNodeWithTag(DashboardTestTags.GOAL_SET_BUTTON).performClick()

        composeTestRule.waitUntilTagText(DashboardTestTags.GOAL_STATUS) { it.contains("km") }

        feedMovingSteps(count = 15)

        composeTestRule.waitUntilTagText(DashboardTestTags.GOAL_STATUS, timeoutMillis = 20_000L) {
            it == dashboardString(R.string.dashboard_goal_reached)
        }
    }

    @Test
    fun overSpeedSampleEmitsExactlyOneAlert() {
        startRideAndSettle()

        val alerts = java.util.Collections.synchronizedList(mutableListOf<RideAlert>())
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collectorJob =
            collectorScope.launch {
                GlobalContext.get().get<RideTracker>().alerts.collect { alerts.add(it) }
            }

        // 20 km/h steadily exceeds the 15 km/h threshold seeded above; the
        // alert is edge-triggered so it must fire exactly once even though
        // every sample after the crossing stays above the limit.
        feedMovingSteps(count = 10)

        runBlocking { collectorJob.cancelAndJoin() }
        assertThat(alerts).hasSize(1)
        assertThat(alerts.first()).isInstanceOf(RideAlert.OverSpeed::class)
    }
}
