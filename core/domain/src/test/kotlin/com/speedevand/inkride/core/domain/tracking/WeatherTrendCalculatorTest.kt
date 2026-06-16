package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class WeatherTrendCalculatorTest {

    private val hourMs = 60 * 60 * 1000L

    @Test
    fun `returns unknown with too little history`() {
        val calc = WeatherTrendCalculator()
        calc.add(0L, 1013.0)
        calc.add(5 * 60 * 1000L, 1013.5) // only 5 min of span
        assertThat(calc.trend()).isEqualTo(WeatherTrend.UNKNOWN)
    }

    @Test
    fun `steady pressure reads as stable`() {
        val calc = WeatherTrendCalculator()
        // Flat line across the full hour → ~0 hPa/h.
        for (min in 0..60 step 10) {
            calc.add(min * 60_000L, 1013.0)
        }
        assertThat(calc.trend()).isEqualTo(WeatherTrend.STABLE)
    }

    @Test
    fun `sustained rise reads as rising`() {
        val calc = WeatherTrendCalculator()
        // +2 hPa over the hour, well above the 0.6 hPa/h threshold.
        for (min in 0..60 step 10) {
            calc.add(min * 60_000L, 1010.0 + min / 30.0)
        }
        assertThat(calc.trend()).isEqualTo(WeatherTrend.RISING)
    }

    @Test
    fun `sustained fall reads as falling`() {
        val calc = WeatherTrendCalculator()
        for (min in 0..60 step 10) {
            calc.add(min * 60_000L, 1015.0 - min / 30.0)
        }
        assertThat(calc.trend()).isEqualTo(WeatherTrend.FALLING)
    }

    @Test
    fun `old readings outside the window are dropped`() {
        val calc = WeatherTrendCalculator()
        // A big early dip more than an hour before the latest reading must not
        // skew the recent (steady) trend.
        calc.add(0L, 980.0)
        for (min in 70..130 step 10) {
            calc.add(min * 60_000L, 1013.0)
        }
        assertThat(calc.trend()).isEqualTo(WeatherTrend.STABLE)
    }

    @Test
    fun `reset clears history`() {
        val calc = WeatherTrendCalculator()
        for (min in 0..60 step 10) {
            calc.add(min * 60_000L, 1010.0 + min / 30.0)
        }
        calc.reset()
        assertThat(calc.trend()).isEqualTo(WeatherTrend.UNKNOWN)
    }
}
