package com.speedevand.inkride.ble.presentation

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val blePresentationModule = module {
    viewModelOf(::BleSensorsViewModel)
}
