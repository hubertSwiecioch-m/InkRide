package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
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

        // The lap distance is formatted as "<n.nn> km" (RideExtrasUi.kt's
        // LapRecord.toSummaryUi, 2 decimals). Extract the actual number and
        // check it's a real, plausible value for ~10s at 20 km/h -- not just
        // that the word "km" is present somewhere in the string.
        val distanceKm =
            Regex("""(\d+\.\d+) km""")
                .find(lapText)
                ?.groupValues
                ?.get(1)
                ?.toDouble()
                ?: error("Lap status text did not contain a \"<number> km\" distance: $lapText")
        assertThat(distanceKm).isGreaterThan(0.03)
        assertThat(distanceKm).isLessThan(0.07)
    }
}
