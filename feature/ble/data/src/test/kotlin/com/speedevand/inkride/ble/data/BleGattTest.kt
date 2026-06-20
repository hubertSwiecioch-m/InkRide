package com.speedevand.inkride.ble.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class BleGattTest {

    @Test
    fun `parseHeartRate reads an 8-bit value`() {
        val data = byteArrayOf(0x00, 75)
        assertThat(parseHeartRate(data)).isEqualTo(75)
    }

    @Test
    fun `parseHeartRate reads a 16-bit value`() {
        // Flags bit0 set → 16-bit little-endian value 0x012C = 300.
        val data = byteArrayOf(0x01, 0x2C, 0x01)
        assertThat(parseHeartRate(data)).isEqualTo(300)
    }

    @Test
    fun `parseHeartRate returns null on an empty packet`() {
        assertThat(parseHeartRate(byteArrayOf())).isNull()
    }

    @Test
    fun `CscCadenceTracker yields null on the first crank sample`() {
        val tracker = CscCadenceTracker()
        // flags 0x02 (crank present), revs = 10, eventTime = 0
        val result = tracker.update(byteArrayOf(0x02, 0x0A, 0x00, 0x00, 0x00))
        assertThat(result?.cadenceRpm).isNull()
    }

    @Test
    fun `CscCadenceTracker computes 60 rpm for one rev per second`() {
        val tracker = CscCadenceTracker()
        tracker.update(byteArrayOf(0x02, 0x0A, 0x00, 0x00, 0x00))
        // +1 revolution, +1024 ticks (= 1 second at 1/1024 s resolution).
        val result = tracker.update(byteArrayOf(0x02, 0x0B, 0x00, 0x00, 0x04))
        assertThat(result?.cadenceRpm).isEqualTo(60)
    }

    @Test
    fun `CscCadenceTracker handles crank-event-time wraparound`() {
        val tracker = CscCadenceTracker()
        // Start near the uint16 ceiling: time = 65535.
        tracker.update(byteArrayOf(0x02, 0x0A, 0x00, 0xFF.toByte(), 0xFF.toByte()))
        // Wrap to 1023 → delta = 1024 ticks, +1 rev → 60 rpm.
        val result = tracker.update(byteArrayOf(0x02, 0x0B, 0x00, 0xFF.toByte(), 0x03))
        assertThat(result?.cadenceRpm).isEqualTo(60)
    }
}
