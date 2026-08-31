package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class HeartRateFilterTest {
    @Test
    fun `accepts the first plausible reading`() {
        val filter = HeartRateFilter()
        assertThat(filter.filter(rawBpm = 145, timestampMs = 0L)).isEqualTo(145)
    }

    @Test
    fun `first reading is subject to the plausible range check`() {
        val filter = HeartRateFilter()
        assertThat(filter.filter(rawBpm = 255, timestampMs = 0L)).isNull()
    }

    @Test
    fun `rejects a reading below the plausible range`() {
        val filter = HeartRateFilter()
        filter.filter(rawBpm = 145, timestampMs = 0L)
        assertThat(filter.filter(rawBpm = 0, timestampMs = 1_000L)).isEqualTo(145)
    }

    @Test
    fun `rejects a reading above the plausible range`() {
        val filter = HeartRateFilter()
        filter.filter(rawBpm = 145, timestampMs = 0L)
        assertThat(filter.filter(rawBpm = 255, timestampMs = 1_000L)).isEqualTo(145)
    }

    @Test
    fun `rejects an implausible jump between consecutive readings`() {
        val filter = HeartRateFilter()
        filter.filter(rawBpm = 70, timestampMs = 0L)
        // 70 -> 190 in one second is not a real heartbeat change.
        assertThat(filter.filter(rawBpm = 190, timestampMs = 1_000L)).isEqualTo(70)
    }

    @Test
    fun `accepts a fast but physiologically plausible change`() {
        val filter = HeartRateFilter()
        filter.filter(rawBpm = 140, timestampMs = 0L)
        // A 30 bpm surge over one second (hard effort/sprint) is plausible.
        assertThat(filter.filter(rawBpm = 170, timestampMs = 1_000L)).isEqualTo(170)
    }

    @Test
    fun `null reading resets state and returns null`() {
        val filter = HeartRateFilter()
        filter.filter(rawBpm = 145, timestampMs = 0L)
        assertThat(filter.filter(rawBpm = null, timestampMs = 1_000L)).isNull()
        // After the reset, the next reading is accepted outright rather than
        // being compared against the stale 145 baseline.
        assertThat(filter.filter(rawBpm = 200, timestampMs = 2_000L)).isEqualTo(200)
    }
}
