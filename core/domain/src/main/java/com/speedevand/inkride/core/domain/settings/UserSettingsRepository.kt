package com.speedevand.inkride.core.domain.settings

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import kotlinx.coroutines.flow.Flow

interface UserSettingsRepository {
    fun observeSettings(): Flow<UserSettings>
    suspend fun save(settings: UserSettings): EmptyResult<DataError.Local>
}
