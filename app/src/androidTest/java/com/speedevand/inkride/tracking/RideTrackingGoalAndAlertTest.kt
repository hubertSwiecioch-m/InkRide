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
import com.speedevand.inkride.tracking.support.RideSamples
import com.speedevand.inkride.tracking.support.waitUntilTagText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Test
import org.koin.core.context.GlobalContext

class RideTrackingGoalAndAlertTest : RideTrackingE2ETestBase() {
    override fun seedSettings(): UserSettings =
        super.seedSettings().copy(alerts = AlertConfig(maxSpeedKmh = 15.0))

    @Test
    fun settingADistanceGoalShowsProgressThenReached() {
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        Thread.sleep(300L)

        composeTestRule.onNodeWithTag(DashboardTestTags.GOAL_BUTTON).performClick()
        // Distance is the default goal type in the sheet; 0.05 km ≈ 9-10s of
        // riding at 20 km/h.
        composeTestRule.onNodeWithTag(DashboardTestTags.GOAL_VALUE_FIELD).performTextInput("0.05")
        composeTestRule.onNodeWithTag(DashboardTestTags.GOAL_SET_BUTTON).performClick()

        composeTestRule.waitUntilTagText(DashboardTestTags.GOAL_STATUS) { it.contains("km") }

        for (step in 1..15) {
            fakeSensorSource.emit(RideSamples.movingSample(stepIndex = step, nowMs = System.currentTimeMillis()))
            Thread.sleep(1_000L)
        }

        composeTestRule.waitUntilTagText(DashboardTestTags.GOAL_STATUS, timeoutMillis = 20_000L) {
            it == "Goal reached!"
        }
    }

    @Test
    fun overSpeedSampleEmitsExactlyOneAlert() {
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        Thread.sleep(300L)

        val alerts = mutableListOf<RideAlert>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        collectorScope.launch {
            GlobalContext.get().get<RideTracker>().alerts.collect { alerts.add(it) }
        }

        // 20 km/h steadily exceeds the 15 km/h threshold seeded above; the
        // alert is edge-triggered so it must fire exactly once even though
        // every sample after the crossing stays above the limit.
        for (step in 1..10) {
            fakeSensorSource.emit(RideSamples.movingSample(stepIndex = step, nowMs = System.currentTimeMillis()))
            Thread.sleep(1_000L)
        }

        collectorScope.cancel()
        assertThat(alerts).hasSize(1)
        assertThat(alerts.first()).isInstanceOf(RideAlert.OverSpeed::class)
    }
}
