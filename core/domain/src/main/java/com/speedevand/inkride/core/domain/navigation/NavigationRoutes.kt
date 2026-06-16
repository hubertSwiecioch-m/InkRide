package com.speedevand.inkride.core.domain.navigation

import kotlinx.serialization.Serializable

@Serializable
data object DashboardGraph

@Serializable
data object HistoryGraph

@Serializable
data object SettingsGraph

@Serializable
data object DashboardRoute

@Serializable
data object SettingsRoute

@Serializable
data object RideHistoryRoute

@Serializable
data class RideDetailRoute(val rideId: Long)

@Serializable
data object LifetimeStatsRoute

@Serializable
data object BleSensorsRoute

@Serializable
data object BikeProfilesRoute
