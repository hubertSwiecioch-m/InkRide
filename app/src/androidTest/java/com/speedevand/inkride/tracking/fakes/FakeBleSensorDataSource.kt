package com.speedevand.inkride.tracking.fakes

import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test double for [BleSensorDataSource], swapped in via Koin override in
 * instrumented tests so an emulator's absent BLE HRM/cadence hardware isn't a
 * blocker. `connect`/`disconnect` are no-ops; tests drive the sample flow
 * directly with [emit].
 */
class FakeBleSensorDataSource : BleSensorDataSource {
    private val samplesFlow =
        MutableSharedFlow<BleSample>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun observeSamples(): Flow<BleSample> = samplesFlow.asSharedFlow()

    override fun connect(
        hrmAddress: String?,
        cadenceAddress: String?,
    ) = Unit

    override fun disconnect() = Unit

    fun emit(sample: BleSample) {
        check(samplesFlow.tryEmit(sample)) { "Failed to emit $sample" }
    }
}
