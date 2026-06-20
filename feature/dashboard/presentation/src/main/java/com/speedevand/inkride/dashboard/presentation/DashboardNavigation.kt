package com.speedevand.inkride.dashboard.presentation

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.speedevand.inkride.core.domain.navigation.DashboardGraph
import com.speedevand.inkride.core.domain.navigation.DashboardRoute
import com.speedevand.inkride.core.domain.navigation.SettingsRoute

fun NavGraphBuilder.dashboardGraph(
    navController: NavController,
    trackingServiceClass: Class<*>
) {
    navigation<DashboardGraph>(startDestination = DashboardRoute) {
        composable<DashboardRoute> {
            val context = LocalContext.current
            DashboardRoot(
                onOpenSettings = {
                    navController.navigate(SettingsRoute)
                },
                onStartService = {
                    val intent = Intent(context, trackingServiceClass).apply {
                        action = "ACTION_START"
                    }
                    context.startService(intent)
                },
                onStopService = {
                    val intent = Intent(context, trackingServiceClass).apply {
                        action = "ACTION_STOP"
                    }
                    context.startService(intent)
                }
            )
        }
    }
}
