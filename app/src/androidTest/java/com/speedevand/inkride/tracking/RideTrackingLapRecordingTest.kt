package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.startsWith
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.tracking.support.dashboardString
import com.speedevand.inkride.tracking.support.textOf
import com.speedevand.inkride.tracking.support.waitUntilTagText
import org.junit.Test

class RideTrackingLapRecordingTest : RideTrackingE2ETestBase() {
    @Test
    fun recordingALapShowsItsSummaryInTheStatusStrip() {
        startRideAndSettle()

        // 10 steps at 20 km/h ≈ 55m, comfortably past RideTracker's
        // minLapDistanceKm (10m) floor for a lap to be recorded.
        feedMovingSteps(count = 10)

        composeTestRule.onNodeWithTag(DashboardTestTags.RECORD_LAP_BUTTON).performClick()

        val lastLapPrefix = dashboardString(R.string.dashboard_last_lap).substringBefore("%1\$s").trim()
        composeTestRule.waitUntilTagText(DashboardTestTags.LAST_LAP_STATUS) { it.startsWith(lastLapPrefix) }
        val lapText = composeTestRule.textOf(DashboardTestTags.LAST_LAP_STATUS)
        assertThat(lapText).startsWith(lastLapPrefix)
        assertThat(lapText).contains("km")
    }
}
