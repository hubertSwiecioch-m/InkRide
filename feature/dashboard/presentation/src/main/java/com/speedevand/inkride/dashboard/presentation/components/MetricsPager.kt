package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.dashboard.presentation.model.RideMetricsUi

enum class DashboardPage {
    PRIMARY,
    SPEED_GRADE,
    SECONDARY,
    COMPASS,
}

/**
 * The pages [MetricsPager] shows, in swipe order, gated on which metrics the
 * user has enabled. Kept as a single source of truth so the pager's own
 * content and [rememberPagerState]'s `pageCount` in `DashboardScreen` can
 * never drift out of sync with each other.
 */
fun visibleDashboardPages(settings: UserSettings): List<DashboardPage> =
    buildList {
        add(DashboardPage.PRIMARY)
        if (settings.showAverageSpeed || settings.showGrade) add(DashboardPage.SPEED_GRADE)
        val hasSecondary =
            settings.showMaxSpeed || settings.showElevationGain ||
                settings.showCalories || settings.showAltitude ||
                settings.showPower
        if (hasSecondary) add(DashboardPage.SECONDARY)
        if (settings.showCompass) add(DashboardPage.COMPASS)
    }

@Composable
fun MetricsPager(
    pagerState: PagerState,
    metrics: RideMetricsUi,
    settings: UserSettings,
    modifier: Modifier = Modifier,
) {
    val visiblePages = remember(settings) { visibleDashboardPages(settings) }

    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize().testTag(DashboardTestTags.METRICS_PAGER),
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
            DashboardPage.SPEED_GRADE -> SpeedGradeMetricsPage(metrics = metrics, settings = settings)
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pageHeight = maxHeight
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SpeedHero(speed = metrics.currentSpeedKmh, unit = metrics.speedUnit, pageHeight = pageHeight)

            val showDistanceRow = settings.showDistance || settings.showMovingTime
            if (showDistanceRow) {
                HorizontalDividerMMD(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.5f)
                            .padding(vertical = DesignConstants.PADDING_MEDIUM),
                )
                MetricRow {
                    if (settings.showDistance) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_distance),
                            value = metrics.distanceKm,
                            unit = metrics.distanceUnit,
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_DISTANCE,
                        )
                    }
                    if (settings.showMovingTime) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_moving_time),
                            value = metrics.movingTime,
                            unit = "",
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_MOVING_TIME,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Average speed / grade, split out from [PrimaryMetricsPage] onto its own
 * page (rather than sharing it with the distance/moving-time row) so the
 * hero speed readout never has to shrink to make room for a second row on a
 * short panel -- see the pageHeight-based cap in [SpeedHero].
 */
@Composable
private fun SpeedGradeMetricsPage(
    metrics: RideMetricsUi,
    settings: UserSettings,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MetricRow {
            if (settings.showAverageSpeed) {
                MetricItem(
                    label = stringResource(R.string.dashboard_metric_avg_speed),
                    value = metrics.averageSpeedKmh,
                    unit = metrics.speedUnit,
                    modifier = Modifier.weight(1f),
                    valueTestTag = DashboardTestTags.METRIC_AVG_SPEED,
                )
            }
            if (settings.showGrade) {
                MetricItem(
                    label = stringResource(R.string.dashboard_metric_grade),
                    value = metrics.gradePercent,
                    unit = "%",
                    modifier = Modifier.weight(1f),
                    valueTestTag = DashboardTestTags.METRIC_GRADE,
                )
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
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
                            valueTestTag = DashboardTestTags.METRIC_MAX_SPEED,
                        )
                    }
                    if (settings.showElevationGain) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_elevation_gain),
                            value = metrics.elevationGainM,
                            unit = metrics.altitudeUnit,
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_ELEVATION_GAIN,
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
                            valueTestTag = DashboardTestTags.METRIC_CALORIES,
                        )
                    }
                    if (settings.showAltitude) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_altitude),
                            value = metrics.altitudeM,
                            unit = metrics.altitudeUnit,
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_ALTITUDE,
                        )
                    }
                    if (settings.showPower) {
                        MetricItem(
                            label = stringResource(R.string.dashboard_metric_power),
                            value = metrics.powerWatts,
                            unit = "W",
                            modifier = Modifier.weight(1f),
                            valueTestTag = DashboardTestTags.METRIC_POWER,
                        )
                    }
                }
            }
        }
    }
}
