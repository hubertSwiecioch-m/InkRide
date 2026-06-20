package com.speedevand.inkride.ble.data

import java.util.UUID

/** Standard Bluetooth SIG UUIDs for the GATT profiles InkRide consumes. */
internal object BleGatt {
    private fun sig(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    // Services
    val HEART_RATE_SERVICE: UUID = sig("180d")
    val CSC_SERVICE: UUID = sig("1816")

    // Characteristics
    val HEART_RATE_MEASUREMENT: UUID = sig("2a37")
    val CSC_MEASUREMENT: UUID = sig("2a5b")

    // Client Characteristic Configuration Descriptor — written to enable notifications.
    val CCCD: UUID = sig("2902")
}

/**
 * Parses a Heart Rate Measurement (0x2A37) value. The first flag bit selects an
 * 8- or 16-bit BPM field. Returns null on a malformed packet.
 */
internal fun parseHeartRate(data: ByteArray): Int? {
    if (data.isEmpty()) return null
    val is16Bit = (data[0].toInt() and 0x01) != 0
    return if (is16Bit) {
        if (data.size < 3) {
            null
        } else {
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        }
    } else {
        if (data.size < 2) null else data[1].toInt() and 0xFF
    }
}

/**
 * Holds the previous crank revolution count / event time from a CSC sensor so
 * the next notification can be turned into an instantaneous cadence (rpm).
 */
internal class CscCadenceTracker {
    private var lastCrankRevs: Int? = null
    private var lastCrankEventTime: Int? = null

    /**
     * Decodes a CSC Measurement (0x2A5B) value and returns the derived cadence in
     * rpm, or null if the packet carries no crank data or this is the first
     * sample (no baseline to diff against). Crank event time is in 1/1024 s units
     * and wraps at 65536.
     */
    fun update(data: ByteArray): CscResult? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt()
        val wheelPresent = (flags and 0x01) != 0
        val crankPresent = (flags and 0x02) != 0

        var offset = 1
        var wheelRevolutions: Long? = null
        if (wheelPresent) {
            if (data.size < offset + 6) return null
            wheelRevolutions = readUint32(data, offset)
            offset += 6 // uint32 cumulative wheel revs + uint16 last wheel event time
        }
        if (!crankPresent) return CscResult(cadenceRpm = null, wheelRevolutions = wheelRevolutions)
        if (data.size < offset + 4) return CscResult(cadenceRpm = null, wheelRevolutions = wheelRevolutions)

        val crankRevs = readUint16(data, offset)
        val crankEventTime = readUint16(data, offset + 2)

        val prevRevs = lastCrankRevs
        val prevTime = lastCrankEventTime
        lastCrankRevs = crankRevs
        lastCrankEventTime = crankEventTime

        if (prevRevs == null || prevTime == null) {
            return CscResult(cadenceRpm = null, wheelRevolutions = wheelRevolutions)
        }

        val deltaRevs = (crankRevs - prevRevs + 0x10000) % 0x10000
        val deltaTime = (crankEventTime - prevTime + 0x10000) % 0x10000
        val cadence =
            if (deltaTime > 0) {
                (deltaRevs.toDouble() * 1024.0 * 60.0 / deltaTime.toDouble()).toInt()
            } else {
                null
            }
        return CscResult(cadenceRpm = cadence, wheelRevolutions = wheelRevolutions)
    }

    private fun readUint16(
        data: ByteArray,
        offset: Int,
    ): Int = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUint32(
        data: ByteArray,
        offset: Int,
    ): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
}

internal data class CscResult(
    val cadenceRpm: Int?,
    val wheelRevolutions: Long?,
)
