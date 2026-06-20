package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.dashboard.presentation.model.RideMetricsUi

enum class DashboardPage {
    PRIMARY,
    SECONDARY,
    COMPASS,
}

@Composable
fun MetricsPager(
    pagerState: PagerState,
    metrics: RideMetricsUi,
    settings: UserSettings,
    modifier: Modifier = Modifier,
) {
    val visiblePages =
        remember(settings) {
            mutableListOf<DashboardPage>().apply {
                add(DashboardPage.PRIMARY)
                val hasSecondary =
                    settings.showMaxSpeed || settings.showElevationGain ||
                        settings.showCalories || settings.showAltitude ||
                        settings.showPower
                if (hasSecondary) add(DashboardPage.SECONDARY)
                if (settings.showCompass) add(DashboardPage.COMPASS)
            }
        }

    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        flingBehavior =
            PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = snap(),
            ),
    ) { pageIndex ->
        val page = visiblePages.getOrNull(pageIndex)
        when (page) {
            DashboardPage.PRIMARY -> PrimaryMetricsPage(metrics = metrics, settings = settings)
            DashboardPage.SECONDARY -> SecondaryMetricsPage(metrics = metrics, settings = settings)
            DashboardPage.COMPASS -> Compass(bearing = metrics.bearingDegrees)
            null -> Unit
        }
    }
}

@Composable
private fun PrimaryMetricsPage(
    metrics: RideMetricsUi,
    settings: UserSettings,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SpeedHero(speed = metrics.currentSpeedKmh, unit = metrics.speedUnit)

        val showDistanceRow = settings.showDistance || settings.showMovingTime
        val showSpeedGradeRow = settings.showAverageSpeed || settings.showGrade
        if (showDistanceRow || showSpeedGradeRow) {
            HorizontalDividerMMD(
                modifier =
                    Modifier
                        .fillMaxWidth(0.5f)
                        .padding(vertical = DesignConstants.PADDING_MEDIUM),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_LARGE)) {
            if (showDistanceRow) {
                MetricRow {
                    if (settings.showDistance) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_distance),
                            value = metrics.distanceKm,
                            unit = metrics.distanceUnit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (settings.showMovingTime) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_moving_time),
                            value = metrics.movingTime,
                            unit = "",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (showSpeedGradeRow) {
                MetricRow {
                    if (settings.showAverageSpeed) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_avg_speed),
                            value = metrics.averageSpeedKmh,
                            unit = metrics.speedUnit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (settings.showGrade) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_grade),
                            value = metrics.gradePercent,
                            unit = "%",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecondaryMetricsPage(
    metrics: RideMetricsUi,
    settings: UserSettings,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_LARGE)) {
            if (settings.showMaxSpeed || settings.showElevationGain) {
                MetricRow {
                    if (settings.showMaxSpeed) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_max_speed),
                            value = metrics.maxSpeedKmh,
                            unit = metrics.speedUnit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (settings.showElevationGain) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_elevation_gain),
                            value = metrics.elevationGainM,
                            unit = metrics.altitudeUnit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (settings.showCalories || settings.showAltitude || settings.showPower) {
                MetricRow {
                    if (settings.showCalories) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_calories),
                            value = metrics.caloriesKcal,
                            unit = "kcal",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (settings.showAltitude) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_altitude),
                            value = metrics.altitudeM,
                            unit = metrics.altitudeUnit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (settings.showPower) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_power),
                            value = metrics.powerWatts,
                            unit = "W",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
