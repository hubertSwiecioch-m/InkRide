package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.EmptyResult
import kotlinx.coroutines.flow.Flow

interface RideSensorDataSource {
    fun observeSamples(): Flow<RideSensorSample>
    fun start(): EmptyResult<SensorError>
    fun stop()
}
