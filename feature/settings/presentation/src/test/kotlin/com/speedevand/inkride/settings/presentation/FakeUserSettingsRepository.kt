package com.speedevand.inkride.settings.presentation

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeUserSettingsRepository : UserSettingsRepository {
    private val settingsFlow = MutableStateFlow(UserSettings(weightKg = 75, age = 30))
    var lastSaved: UserSettings? = null
        private set
    var saveResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    override fun observeSettings(): Flow<UserSettings> = settingsFlow

    override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> {
        lastSaved = settings
        settingsFlow.value = settings
        return saveResult
    }

    fun emitSettings(settings: UserSettings) {
        settingsFlow.value = settings
    }
}
