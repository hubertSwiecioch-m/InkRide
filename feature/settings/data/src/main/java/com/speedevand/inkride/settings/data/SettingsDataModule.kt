package com.speedevand.inkride.settings.data

import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val settingsDataModule =
    module {
        singleOf(::RoomUserSettingsRepository) { bind<UserSettingsRepository>() }
        singleOf(::RoomBikeProfileRepository) { bind<BikeProfileRepository>() }
    }
