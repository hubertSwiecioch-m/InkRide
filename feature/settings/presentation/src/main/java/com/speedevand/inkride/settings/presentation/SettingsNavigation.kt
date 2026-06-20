package com.speedevand.inkride.settings.presentation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.speedevand.inkride.core.domain.navigation.BikeProfilesRoute
import com.speedevand.inkride.core.domain.navigation.BleSensorsRoute
import com.speedevand.inkride.core.domain.navigation.SettingsRoute

fun NavGraphBuilder.settingsGraph(navController: NavController) {
    composable<SettingsRoute> {
        SettingsRoot(
            onBack = {
                navController.popBackStack()
            },
            onOpenBleSensors = {
                navController.navigate(BleSensorsRoute)
            },
            onOpenBikeProfiles = {
                navController.navigate(BikeProfilesRoute)
            },
        )
    }
    composable<BikeProfilesRoute> {
        BikeProfilesRoot(
            onNavigateBack = {
                navController.popBackStack()
            },
        )
    }
}
