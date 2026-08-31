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
    // Wall-clock timestamp of the last CSC notification that actually carried
    // a cadence value. Null when cadence has never been reported. Distinct
    // from [timestampMs] (this emission's own time) because most CSC sensors
    // keep the last cadence cached and re-emit it alongside unrelated HR
    // notifications, without a fresh crank event — this field lets a
    // consumer tell "cadence is still arriving" from "cadence is stale".
    val cadenceUpdatedAtMs: Long? = null,
)
