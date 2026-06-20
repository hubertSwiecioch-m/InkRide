package com.speedevand.inkride.settings.presentation

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeUserSettingsRepository : UserSettingsRepository {

    private val _settings = MutableStateFlow(UserSettings(weightKg = 75, age = 30))
    var lastSaved: UserSettings? = null
        private set
    var saveResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    override fun observeSettings(): Flow<UserSettings> = _settings

    override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> {
        lastSaved = settings
        _settings.value = settings
        return saveResult
    }

    fun emitSettings(settings: UserSettings) {
        _settings.value = settings
    }
}
