package com.speedevand.inkride.dashboard.presentation

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val dashboardPresentationModule = module {
    single<GpxRouteLoader> { AndroidGpxRouteLoader(androidContext()) }
    viewModelOf(::DashboardViewModel)
}
