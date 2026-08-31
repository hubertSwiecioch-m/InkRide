package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class GeoDistanceTest {
    @Test
    fun `same point has zero distance`() {
        assertThat(haversineMeters(52.0, 21.0, 52.0, 21.0)).isEqualTo(0.0)
    }

    @Test
    fun `one hundredth of a degree of latitude is about 1112 meters`() {
        val distance = haversineMeters(52.0, 21.0, 52.01, 21.0)

        assertThat(distance).isCloseTo(1111.95, 1.0)
    }
}
