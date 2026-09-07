package com.speedevand.inkride.dashboard.presentation

/**
 * Stable identifiers for Compose UI tests. Applied directly to the leaf
 * text/interactive nodes that carry live ride data, so instrumented tests can
 * assert on values without depending on formatted/localized text.
 */
object DashboardTestTags {
    const val METRICS_PAGER = "dashboard_metrics_pager"
    const val SPEED_VALUE = "dashboard_speed_value"
    const val STATUS_INDICATOR = "dashboard_status_indicator"
    const val START_PAUSE_BUTTON = "dashboard_start_pause_button"
    const val STOP_RESET_BUTTON = "dashboard_stop_reset_button"
    const val COMPASS_BEARING = "dashboard_compass_bearing"
    const val HEART_RATE_VALUE = "dashboard_heart_rate_value"
    const val CADENCE_VALUE = "dashboard_cadence_value"
    const val SENSOR_DISCONNECTED = "dashboard_sensor_disconnected"
    const val GPS_QUALITY = "dashboard_gps_quality"
    const val GOAL_STATUS = "dashboard_goal_status"
    const val LAST_LAP_STATUS = "dashboard_last_lap_status"
    const val RECORD_LAP_BUTTON = "dashboard_record_lap_button"
    const val GOAL_BUTTON = "dashboard_goal_button"
    const val GOAL_VALUE_FIELD = "dashboard_goal_value_field"
    const val GOAL_SET_BUTTON = "dashboard_goal_set_button"

    const val METRIC_DISTANCE = "dashboard_metric_value_distance"
    const val METRIC_MOVING_TIME = "dashboard_metric_value_moving_time"
    const val METRIC_AVG_SPEED = "dashboard_metric_value_avg_speed"
    const val METRIC_GRADE = "dashboard_metric_value_grade"
    const val METRIC_MAX_SPEED = "dashboard_metric_value_max_speed"
    const val METRIC_ELEVATION_GAIN = "dashboard_metric_value_elevation_gain"
    const val METRIC_CALORIES = "dashboard_metric_value_calories"
    const val METRIC_ALTITUDE = "dashboard_metric_value_altitude"
    const val METRIC_POWER = "dashboard_metric_value_power"
}
