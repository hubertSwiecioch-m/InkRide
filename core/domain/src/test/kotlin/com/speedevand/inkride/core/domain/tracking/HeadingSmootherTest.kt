package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class HeadingSmootherTest {
    @Test
    fun `first update returns the raw declination-corrected heading and emits`() {
        val smoother = HeadingSmoother()
        val update = smoother.update(magneticAzimuthDeg = 90f, declinationDeg = 5f)
        assertThat(update.smoothedHeadingDeg).isEqualTo(95f)
        assertThat(update.shouldEmit).isTrue()
    }

    @Test
    fun `small change below the emit threshold is smoothed but not emitted`() {
        val smoother = HeadingSmoother(emitThresholdDeg = 2.0f)
        smoother.update(magneticAzimuthDeg = 90f, declinationDeg = 0f)
        val update = smoother.update(magneticAzimuthDeg = 91f, declinationDeg = 0f)
        assertThat(update.shouldEmit).isFalse()
    }

    @Test
    fun `change at or above the emit threshold is emitted`() {
        val smoother = HeadingSmoother(emitThresholdDeg = 2.0f, smoothingAlpha = 1.0f)
        smoother.update(magneticAzimuthDeg = 90f, declinationDeg = 0f)
        // alpha = 1.0 makes the filter track the raw input exactly, so a
        // 10-degree swing produces a 10-degree smoothed change — comfortably
        // over the 2-degree gate.
        val update = smoother.update(magneticAzimuthDeg = 100f, declinationDeg = 0f)
        assertThat(update.shouldEmit).isTrue()
        assertThat(update.smoothedHeadingDeg).isEqualTo(100f)
    }

    @Test
    fun `smoothing blends toward the new heading along the short arc across the wrap point`() {
        val smoother = HeadingSmoother(smoothingAlpha = 0.5f)
        smoother.update(magneticAzimuthDeg = 350f, declinationDeg = 0f)
        // 350 -> 10 is a 20-degree step across the 0/360 wrap, not a
        // 340-degree step the long way around.
        val update = smoother.update(magneticAzimuthDeg = 10f, declinationDeg = 0f)
        assertThat(update.smoothedHeadingDeg).isEqualTo(0f)
    }

    @Test
    fun `reset clears smoothing and throttle state so the next update emits immediately`() {
        val smoother = HeadingSmoother(emitThresholdDeg = 2.0f)
        smoother.update(magneticAzimuthDeg = 90f, declinationDeg = 0f)
        smoother.reset()
        val update = smoother.update(magneticAzimuthDeg = 91f, declinationDeg = 0f)
        assertThat(update.shouldEmit).isTrue()
    }
}
