package com.speedevand.inkride.history.presentation

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val historyPresentationModule =
    module {
        single<GpxExporter> { AndroidGpxExporter(androidContext(), get(), get()) }
        viewModelOf(::RideHistoryViewModel)
        viewModelOf(::LifetimeStatsViewModel)
        // RideDetailViewModel requires rideId — injected as a parameter at call site
        viewModel { p ->
            RideDetailViewModel(
                rideId = p.get(),
                rideHistoryRepository = get(),
                lapRepository = get(),
                trackPointRepository = get(),
                userSettingsRepository = get(),
                gpxExporter = get(),
            )
        }
    }
