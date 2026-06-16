package com.speedevand.inkride.history.presentation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import androidx.navigation.toRoute
import com.speedevand.inkride.core.domain.navigation.HistoryGraph
import com.speedevand.inkride.core.domain.navigation.LifetimeStatsRoute
import com.speedevand.inkride.core.domain.navigation.RideDetailRoute
import com.speedevand.inkride.core.domain.navigation.RideHistoryRoute

fun NavGraphBuilder.historyGraph(navController: NavController) {
    navigation<HistoryGraph>(startDestination = RideHistoryRoute) {
        composable<RideHistoryRoute> {
            RideHistoryRoot(
                onNavigateToDetail = { rideId ->
                    navController.navigate(RideDetailRoute(rideId))
                },
                onNavigateToLifetimeStats = {
                    navController.navigate(LifetimeStatsRoute)
                }
            )
        }
        composable<RideDetailRoute> { backStackEntry ->
            val route: RideDetailRoute = backStackEntry.toRoute()
            RideDetailRoot(
                rideId = route.rideId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable<LifetimeStatsRoute> {
            LifetimeStatsRoot(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
