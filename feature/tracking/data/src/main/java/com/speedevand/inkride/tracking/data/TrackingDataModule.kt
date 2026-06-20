package com.speedevand.inkride.tracking.data

import com.speedevand.inkride.core.domain.tracking.CaloriesEstimator
import com.speedevand.inkride.core.domain.tracking.RideMetricsCalculator
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideTracker
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val trackingDataModule =
    module {
        single<RideSensorDataSource> { AndroidRideSensorDataSource(get()) }
        singleOf(::CaloriesEstimator)
        single { RideMetricsCalculator(get()) }
        // (sensorDataSource, metricsCalculator, historyRepository, trackPointRepository,
        //  lapRepository, bleSensorDataSource, userSettingsRepository)
        single { RideTracker(get(), get(), get(), get(), get(), get(), get()) }
    }
