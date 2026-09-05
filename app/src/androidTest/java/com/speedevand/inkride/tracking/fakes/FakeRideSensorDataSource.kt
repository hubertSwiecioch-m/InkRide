package com.speedevand.inkride.tracking.fakes

import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideSensorSample
import com.speedevand.inkride.core.domain.tracking.SensorError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test double for [RideSensorDataSource], swapped in via Koin override in
 * instrumented tests so an emulator's absent real GPS/barometer isn't a
 * blocker. `start`/`stop` are no-ops; tests drive the sample flow directly
 * with [emit].
 */
class FakeRideSensorDataSource : RideSensorDataSource {
    private val samplesFlow =
        MutableSharedFlow<RideSensorSample>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun observeSamples(): Flow<RideSensorSample> = samplesFlow.asSharedFlow()

    override fun start(): EmptyResult<SensorError> = Result.Success(Unit)

    override fun stop() = Unit

    fun emit(sample: RideSensorSample) {
        check(samplesFlow.tryEmit(sample)) { "Failed to emit $sample" }
    }
}
