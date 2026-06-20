package com.speedevand.inkride.history.data

import com.speedevand.inkride.core.domain.history.LifetimeStatsRepository
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val historyDataModule = module {
    singleOf(::RoomRideHistoryRepository) { bind<RideHistoryRepository>() }
    singleOf(::RoomRideTrackPointRepository) { bind<RideTrackPointRepository>() }
    singleOf(::RoomRideLapRepository) { bind<RideLapRepository>() }
    singleOf(::RoomLifetimeStatsRepository) { bind<LifetimeStatsRepository>() }
}
