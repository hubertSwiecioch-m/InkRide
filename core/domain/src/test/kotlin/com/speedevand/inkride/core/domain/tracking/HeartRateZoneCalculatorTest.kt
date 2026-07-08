package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class HeartRateZoneCalculatorTest {
    private val calculator = HeartRateZoneCalculator()

    @Test
    fun `maxHeartRateBpm uses the Tanaka formula`() {
        // 208 - 0.7 * 30 = 187
        assertThat(calculator.maxHeartRateBpm(age = 30)).isEqualTo(187.0)
        // 208 - 0.7 * 50 = 173
        assertThat(calculator.maxHeartRateBpm(age = 50)).isEqualTo(173.0)
    }

    @Test
    fun `zone 1 below 60 percent of HRmax`() {
        // HRmax(30) = 187; 59% = 110.33
        assertThat(calculator.zoneFor(heartRateBpm = 110, age = 30)).isEqualTo(1)
    }

    @Test
    fun `zone 2 between 60 and 70 percent`() {
        // 65% of 187 = 121.55
        assertThat(calculator.zoneFor(heartRateBpm = 122, age = 30)).isEqualTo(2)
    }

    @Test
    fun `zone 3 between 70 and 80 percent`() {
        // 75% of 187 = 140.25
        assertThat(calculator.zoneFor(heartRateBpm = 140, age = 30)).isEqualTo(3)
    }

    @Test
    fun `zone 4 between 80 and 90 percent`() {
        // 85% of 187 = 158.95
        assertThat(calculator.zoneFor(heartRateBpm = 159, age = 30)).isEqualTo(4)
    }

    @Test
    fun `zone 5 at or above 90 percent`() {
        // 90% of 187 = 168.3
        assertThat(calculator.zoneFor(heartRateBpm = 169, age = 30)).isEqualTo(5)
    }
}
