package com.speedevand.inkride.core.domain.ble

/**
 * Latest values read from paired BLE sensors. Each field is null until a sensor
 * of that kind is connected and has reported a value. [wheelRevolutions] is the
 * cumulative count from a CSC sensor, exposed for completeness; [cadenceRpm] is
 * the derived crank cadence most riders care about. [connected] is true while at
 * least one paired device has a live GATT connection; it flips to false (with
 * the readings cleared) as soon as a sensor drops, so stale values never linger.
 */
data class BleSample(
    val timestampMs: Long,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val wheelRevolutions: Long? = null,
    val connected: Boolean = false,
)
