package com.speedevand.inkride.settings.presentation

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingsPresentationModule =
    module {
        viewModelOf(::SettingsViewModel)
        viewModelOf(::BikeProfilesViewModel)
    }
