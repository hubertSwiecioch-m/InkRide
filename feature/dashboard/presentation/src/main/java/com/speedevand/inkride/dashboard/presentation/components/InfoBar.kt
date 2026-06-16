package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.speedevand.inkride.core.domain.tracking.WeatherTrend
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.dashboard.presentation.model.RideMetricsUi
import com.mudita.mmd.components.text.TextMMD

@Composable
fun InfoBar(
    metrics: RideMetricsUi,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextMMD(
            text = stringResource(
                R.string.dashboard_gps_accuracy,
                metrics.gpsAccuracyM,
                metrics.altitudeUnit
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        metrics.heartRateBpm?.let {
            TextMMD(
                text = stringResource(R.string.dashboard_heart_rate, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        metrics.cadenceRpm?.let {
            TextMMD(
                text = stringResource(R.string.dashboard_cadence, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        metrics.weatherTrend.labelRes()?.let { labelRes ->
            TextMMD(
                text = stringResource(R.string.dashboard_weather, stringResource(labelRes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// Static, E-Ink-friendly arrow glyph per trend; null hides the readout until
// enough barometer history accrues.
private fun WeatherTrend.labelRes(): Int? = when (this) {
    WeatherTrend.RISING -> R.string.dashboard_weather_rising
    WeatherTrend.FALLING -> R.string.dashboard_weather_falling
    WeatherTrend.STABLE -> R.string.dashboard_weather_stable
    WeatherTrend.UNKNOWN -> null
}
