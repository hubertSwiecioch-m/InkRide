package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.startsWith
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.support.RideSamples
import com.speedevand.inkride.tracking.support.textOf
import com.speedevand.inkride.tracking.support.waitUntilTagText
import org.junit.Test

class RideTrackingLapRecordingTest : RideTrackingE2ETestBase() {
    @Test
    fun recordingALapShowsItsSummaryInTheStatusStrip() {
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).performClick()
        Thread.sleep(300L)

        // 10 steps at 20 km/h ≈ 55m, comfortably past RideTracker's
        // minLapDistanceKm (10m) floor for a lap to be recorded.
        for (step in 1..10) {
            fakeSensorSource.emit(RideSamples.movingSample(stepIndex = step, nowMs = System.currentTimeMillis()))
            Thread.sleep(1_000L)
        }

        composeTestRule.onNodeWithTag(DashboardTestTags.RECORD_LAP_BUTTON).performClick()

        composeTestRule.waitUntilTagText(DashboardTestTags.LAST_LAP_STATUS) { it.startsWith("Last lap:") }
        val lapText = composeTestRule.textOf(DashboardTestTags.LAST_LAP_STATUS)
        assertThat(lapText).startsWith("Last lap:")
        assertThat(lapText).contains("km")
    }
}
