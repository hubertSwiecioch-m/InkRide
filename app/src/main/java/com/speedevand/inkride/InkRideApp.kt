package com.speedevand.inkride

import android.app.Application
import com.speedevand.inkride.ble.data.bleDataModule
import com.speedevand.inkride.ble.presentation.blePresentationModule
import com.speedevand.inkride.core.database.databaseModule
import com.speedevand.inkride.dashboard.presentation.dashboardPresentationModule
import com.speedevand.inkride.history.data.historyDataModule
import com.speedevand.inkride.history.presentation.historyPresentationModule
import com.speedevand.inkride.settings.data.settingsDataModule
import com.speedevand.inkride.settings.presentation.settingsPresentationModule
import com.speedevand.inkride.tracking.data.trackingDataModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class InkRideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@InkRideApp)
            modules(
                databaseModule,
                trackingDataModule,
                historyDataModule,
                settingsDataModule,
                settingsPresentationModule,
                dashboardPresentationModule,
                historyPresentationModule,
                bleDataModule,
                blePresentationModule,
            )
        }
    }
}
