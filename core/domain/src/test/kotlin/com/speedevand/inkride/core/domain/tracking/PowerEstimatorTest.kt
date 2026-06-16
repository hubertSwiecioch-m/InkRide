package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isZero
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.UserSettings
import org.junit.jupiter.api.Test

class PowerEstimatorTest {

    private val estimator = PowerEstimator()
    private val defaultSettings = UserSettings(weightKg = 75, age = 30, bikeWeightKg = 10.0, bikeType = BikeType.ROAD)

    @Test
    fun `zero power at very low speed`() {
        val watts = estimator.estimateWatts(
            speedMps = 0.05,
            accelerationMps2 = 0.0,
            gradePercent = 0.0,
            userSettings = defaultSettings
        )
        assertThat(watts).isZero()
    }

    @Test
    fun `zero power at exactly zero speed`() {
        val watts = estimator.estimateWatts(
            speedMps = 0.0,
            accelerationMps2 = 0.0,
            gradePercent = 0.0,
            userSettings = defaultSettings
        )
        assertThat(watts).isZero()
    }

    @Test
    fun `positive power at riding speed`() {
        val watts = estimator.estimateWatts(
            speedMps = 8.0, // ~29 km/h
            accelerationMps2 = 0.0,
            gradePercent = 0.0,
            userSettings = defaultSettings
        )
        assertThat(watts).isGreaterThan(0)
    }

    @Test
    fun `road bike has lower power than mtb at same speed`() {
        val roadSettings = defaultSettings.copy(bikeType = BikeType.ROAD)
        val mtbSettings = defaultSettings.copy(bikeType = BikeType.MTB)

        val roadWatts = estimator.estimateWatts(8.0, 0.0, 0.0, roadSettings)
        val mtbWatts = estimator.estimateWatts(8.0, 0.0, 0.0, mtbSettings)

        assertThat(mtbWatts).isGreaterThan(roadWatts)
    }

    @Test
    fun `city bike power between road and mtb`() {
        val roadSettings = defaultSettings.copy(bikeType = BikeType.ROAD)
        val citySettings = defaultSettings.copy(bikeType = BikeType.CITY)
        val mtbSettings = defaultSettings.copy(bikeType = BikeType.MTB)

        val roadWatts = estimator.estimateWatts(8.0, 0.0, 0.0, roadSettings)
        val cityWatts = estimator.estimateWatts(8.0, 0.0, 0.0, citySettings)
        val mtbWatts = estimator.estimateWatts(8.0, 0.0, 0.0, mtbSettings)

        assertThat(cityWatts).isGreaterThan(roadWatts)
        assertThat(mtbWatts).isGreaterThan(cityWatts)
    }

    @Test
    fun `uphill adds gravity power`() {
        val flatWatts = estimator.estimateWatts(5.0, 0.0, 0.0, defaultSettings)
        val uphillWatts = estimator.estimateWatts(5.0, 0.0, 5.0, defaultSettings)

        assertThat(uphillWatts).isGreaterThan(flatWatts)
    }

    @Test
    fun `downhill reduces power from gravity`() {
        val flatWatts = estimator.estimateWatts(5.0, 0.0, 0.0, defaultSettings)
        val downhillWatts = estimator.estimateWatts(5.0, 0.0, -5.0, defaultSettings)

        // Gravity component is negative on downhill, reducing total
        assertThat(downhillWatts).isLessThan(flatWatts)
    }

    @Test
    fun `power never negative even on steep downhill with deceleration`() {
        val watts = estimator.estimateWatts(
            speedMps = 5.0,
            accelerationMps2 = -2.0,
            gradePercent = -20.0,
            userSettings = defaultSettings
        )
        // coerceAtLeast(0.0) ensures non-negative
        assertThat(watts).isEqualTo(0)
    }

    @Test
    fun `acceleration adds power`() {
        val steadyWatts = estimator.estimateWatts(5.0, 0.0, 0.0, defaultSettings)
        val acceleratingWatts = estimator.estimateWatts(5.0, 1.5, 0.0, defaultSettings)

        assertThat(acceleratingWatts).isGreaterThan(steadyWatts)
    }

    @Test
    fun `deceleration reduces total`() {
        val steadyWatts = estimator.estimateWatts(5.0, 0.0, 0.0, defaultSettings)
        val deceleratingWatts = estimator.estimateWatts(5.0, -1.5, 0.0, defaultSettings)

        assertThat(deceleratingWatts).isLessThan(steadyWatts)
    }

    @Test
    fun `power scales with rider weight`() {
        val lightSettings = defaultSettings.copy(weightKg = 60)
        val heavySettings = defaultSettings.copy(weightKg = 90)

        val lightWatts = estimator.estimateWatts(5.0, 0.0, 5.0, lightSettings)
        val heavyWatts = estimator.estimateWatts(5.0, 0.0, 5.0, heavySettings)

        assertThat(heavyWatts).isGreaterThan(lightWatts)
    }

    @Test
    fun `flat ground produces no gravity component`() {
        // On flat ground, the gravity power component is sin(atan(0)) * ... = 0
        val uphillWatts = estimator.estimateWatts(5.0, 0.0, 5.0, defaultSettings)
        val flatWatts = estimator.estimateWatts(5.0, 0.0, 0.0, defaultSettings)

        // With grade=0, the only power comes from rolling + air resistance (no gravity, no accel)
        // This should be less than uphill
        assertThat(flatWatts).isLessThan(uphillWatts)
    }
}
