package com.speedevand.inkride.ble.presentation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.speedevand.inkride.core.domain.navigation.BleSensorsRoute

fun NavGraphBuilder.bleGraph(navController: NavController) {
    composable<BleSensorsRoute> {
        BleSensorsRoot(
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
