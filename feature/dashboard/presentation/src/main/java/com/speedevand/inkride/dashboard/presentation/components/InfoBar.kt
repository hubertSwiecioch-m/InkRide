package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.domain.tracking.WeatherTrend
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.dashboard.presentation.model.RideMetricsUi

@Composable
fun InfoBar(
    metrics: RideMetricsUi,
    sensorPaired: Boolean,
    sensorConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextMMD(
            text =
                stringResource(
                    R.string.dashboard_gps_accuracy,
                    metrics.gpsAccuracyM,
                    metrics.altitudeUnit,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.testTag(DashboardTestTags.GPS_QUALITY),
        )
        metrics.heartRateBpm?.let { bpm ->
            val heartRateText =
                metrics.heartRateZone?.let { zone ->
                    stringResource(R.string.dashboard_heart_rate_zone, bpm, zone)
                } ?: stringResource(R.string.dashboard_heart_rate, bpm)
            TextMMD(
                text = heartRateText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag(DashboardTestTags.HEART_RATE_VALUE),
            )
        }
        metrics.cadenceRpm?.let {
            TextMMD(
                text = stringResource(R.string.dashboard_cadence, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag(DashboardTestTags.CADENCE_VALUE),
            )
        }
        if (sensorPaired && !sensorConnected) {
            TextMMD(
                text = stringResource(R.string.dashboard_sensor_disconnected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag(DashboardTestTags.SENSOR_DISCONNECTED),
            )
        }
        metrics.weatherTrend.labelRes()?.let { labelRes ->
            TextMMD(
                text = stringResource(R.string.dashboard_weather, stringResource(labelRes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// Static, E-Ink-friendly arrow glyph per trend; null hides the readout until
// enough barometer history accrues.
private fun WeatherTrend.labelRes(): Int? =
    when (this) {
        WeatherTrend.RISING -> R.string.dashboard_weather_rising
        WeatherTrend.FALLING -> R.string.dashboard_weather_falling
        WeatherTrend.STABLE -> R.string.dashboard_weather_stable
        WeatherTrend.UNKNOWN -> null
    }
