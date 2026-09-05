package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isGreaterThan
import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.support.RideSamples
import com.speedevand.inkride.tracking.support.textOf
import com.speedevand.inkride.tracking.support.waitUntilTagText
import org.junit.Test

/**
 * A BLE sensor drop clears its readings immediately (BleSample.connected
 * docs: "readings cleared" as soon as a sensor drops) — this checks the UI
 * reflects that, and that GPS-derived metrics are unaffected.
 */
class RideTrackingBleSensorLossTest : RideTrackingE2ETestBase() {
    @Test
    fun bleDisconnectHidesHrAndCadenceWhileGpsMetricsKeepUpdating() {
        startRideAndSettle()

        fakeSensorSource.emit(RideSamples.movingSample(stepIndex = 1, nowMs = System.currentTimeMillis()))
        fakeBleSource.emit(
            BleSample(
                timestampMs = System.currentTimeMillis(),
                heartRateBpm = 150,
                cadenceRpm = 90,
                connected = true,
                cadenceUpdatedAtMs = System.currentTimeMillis(),
            ),
        )

        composeTestRule.waitUntilTagText(DashboardTestTags.HEART_RATE_VALUE) { it.contains("150") }
        assertThat(composeTestRule.textOf(DashboardTestTags.CADENCE_VALUE)).contains("90")
        composeTestRule.onNodeWithTag(DashboardTestTags.SENSOR_DISCONNECTED).assertDoesNotExist()

        val distanceBeforeDisconnect = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()

        fakeBleSource.emit(
            BleSample(
                timestampMs = System.currentTimeMillis(),
                heartRateBpm = null,
                cadenceRpm = null,
                connected = false,
            ),
        )

        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(DashboardTestTags.SENSOR_DISCONNECTED)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag(DashboardTestTags.HEART_RATE_VALUE).assertDoesNotExist()
        composeTestRule.onNodeWithTag(DashboardTestTags.CADENCE_VALUE).assertDoesNotExist()

        // GPS-derived metrics keep moving regardless of the BLE drop.
        feedMovingSteps(count = 5)
        val distanceAfter = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE).toDouble()
        assertThat(distanceAfter).isGreaterThan(distanceBeforeDisconnect)
    }
}
