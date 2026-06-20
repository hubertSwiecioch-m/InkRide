package com.speedevand.inkride.ble.data

import com.speedevand.inkride.core.domain.ble.BleScanner
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val bleDataModule =
    module {
        single<BleSensorDataSource> { AndroidBleSensorDataSource(androidContext()) }
        single<BleScanner> { AndroidBleScanner(androidContext()) }
    }
