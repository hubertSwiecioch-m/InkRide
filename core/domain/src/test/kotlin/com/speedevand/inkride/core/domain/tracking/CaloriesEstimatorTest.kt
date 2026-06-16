package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isZero
import com.speedevand.inkride.core.domain.settings.UserSettings
import org.junit.jupiter.api.Test

class CaloriesEstimatorTest {

    private val estimator = CaloriesEstimator()

    @Test
    fun `intensity increases calories`() {
        val settings = UserSettings(weightKg = 70, age = 30)

        val easy = estimator.estimateKcal(speedKmh = 15.0, intervalMs = 60_000L, userSettings = settings)
        val intense = estimator.estimateKcal(speedKmh = 28.0, intervalMs = 60_000L, userSettings = settings)

        assertThat(intense).isGreaterThan(easy)
    }

    @Test
    fun `zero interval returns zero`() {
        val settings = UserSettings(weightKg = 70, age = 30)
        val kcal = estimator.estimateKcal(speedKmh = 20.0, intervalMs = 0L, userSettings = settings)
        assertThat(kcal).isZero()
    }

    @Test
    fun `negative interval returns zero`() {
        val settings = UserSettings(weightKg = 70, age = 30)
        val kcal = estimator.estimateKcal(speedKmh = 20.0, intervalMs = -1000L, userSettings = settings)
        assertThat(kcal).isZero()
    }

    @Test
    fun `zero speed returns zero`() {
        val settings = UserSettings(weightKg = 70, age = 30)
        val kcal = estimator.estimateKcal(speedKmh = 0.0, intervalMs = 60_000L, userSettings = settings)
        assertThat(kcal).isZero()
    }

    // ── MET interpolation: values smoothly transition between brackets ──────

    @Test
    fun `MET is 4_0 at exactly 16 kmh bracket`() {
        val settings = UserSettings(weightKg = 70, age = 20)
        val kcal = estimator.estimateKcal(speedKmh = 16.0, intervalMs = 60_000L, userSettings = settings)
        assertThat(kcal).isEqualTo(4.0 * 3.5 * 70.0 / 200.0)
    }

    @Test
    fun `MET is 6_8 at exactly 19 kmh bracket`() {
        val settings = UserSettings(weightKg = 70, age = 20)
        val kcal = estimator.estimateKcal(speedKmh = 19.0, intervalMs = 60_000L, userSettings = settings)
        assertThat(kcal).isEqualTo(6.8 * 3.5 * 70.0 / 200.0)
    }

    @Test
    fun `MET is 8_0 at exactly 22 kmh bracket`() {
        val settings = UserSettings(weightKg = 70, age = 20)
        val kcal = estimator.estimateKcal(speedKmh = 22.0, intervalMs = 60_000L, userSettings = settings)
        assertThat(kcal).isEqualTo(8.0 * 3.5 * 70.0 / 200.0)
    }

    @Test
    fun `MET is 10_0 at exactly 25 kmh bracket`() {
        val settings = UserSettings(weightKg = 70, age = 20)
        val kcal = estimator.estimateKcal(speedKmh = 25.0, intervalMs = 60_000L, userSettings = settings)
        assertThat(kcal).isEqualTo(10.0 * 3.5 * 70.0 / 200.0)
    }

    @Test
    fun `MET interpolates between 6_8 and 8_0 at 20_5 kmh`() {
        // At 20.5 km/h: fraction = (20.5 - 19) / (22 - 19) = 1.5/3 = 0.5
        // MET = 6.8 + 0.5 * (8.0 - 6.8) = 6.8 + 0.6 = 7.4
        val expectedMet = 7.4
        val settings = UserSettings(weightKg = 70, age = 20)
        val kcal = estimator.estimateKcal(speedKmh = 20.5, intervalMs = 60_000L, userSettings = settings)
        assertThat(kcal).isEqualTo(expectedMet * 3.5 * 70.0 / 200.0)
    }

    @Test
    fun `MET interpolation is continuous across bracket boundary`() {
        val settings = UserSettings(weightKg = 70, age = 20)
        // Just below and just above 16 km/h boundary
        val justBelow = estimator.estimateKcal(speedKmh = 15.99, intervalMs = 60_000L, userSettings = settings)
        val justAbove = estimator.estimateKcal(speedKmh = 16.01, intervalMs = 60_000L, userSettings = settings)
        // Should not have a large jump — previously was 70% difference
        val ratio = justAbove / justBelow
        assertThat(ratio).isGreaterThan(0.95)
        assertThat(ratio).isLessThan(1.1)
    }

    // ── Age factor brackets ────────────────────────────────────────────────

    @Test
    fun `age factor is 1_0 for age under 30`() {
        val settings = UserSettings(weightKg = 70, age = 20)
        // At speed=16.0 (MET=4.0), ageFactor=1.0
        val kcal = estimator.estimateKcal(speedKmh = 16.0, intervalMs = 60_000L, userSettings = settings)
        assertThat(kcal).isEqualTo(4.0 * 3.5 * 70.0 / 200.0 * 1.0)
    }

    @Test
    fun `age factor is 0_97 for age 30 to 44`() {
        val settings = UserSettings(weightKg = 70, age = 35)
        val kcal = estimator.estimateKcal(speedKmh = 16.0, intervalMs = 60_000L, userSettings = settings)
        assertThat(kcal).isEqualTo(4.0 * 3.5 * 70.0 / 200.0 * 0.97)
    }

    @Test
    fun `age factor is 0_94 for age 45 to 59`() {
        val settings = UserSettings(weightKg = 70, age = 50)
        val kcal = estimator.estimateKcal(speedKmh = 16.0, intervalMs = 60_000L, userSettings = settings)
        assertThat(kcal).isEqualTo(4.0 * 3.5 * 70.0 / 200.0 * 0.94)
    }

    @Test
    fun `age factor is 0_9 for age 60 and above`() {
        val settings = UserSettings(weightKg = 70, age = 65)
        val kcal = estimator.estimateKcal(speedKmh = 16.0, intervalMs = 60_000L, userSettings = settings)
        assertThat(kcal).isEqualTo(4.0 * 3.5 * 70.0 / 200.0 * 0.9)
    }

    // ── Other ──────────────────────────────────────────────────────────────

    @Test
    fun `calories increase linearly with interval duration`() {
        val settings = UserSettings(weightKg = 70, age = 20)

        val oneMinute = estimator.estimateKcal(speedKmh = 22.0, intervalMs = 60_000L, userSettings = settings)
        val twoMinutes = estimator.estimateKcal(speedKmh = 22.0, intervalMs = 120_000L, userSettings = settings)

        assertThat(twoMinutes).isEqualTo(oneMinute * 2.0)
    }

    @Test
    fun `heavier rider burns more calories`() {
        val lightSettings = UserSettings(weightKg = 60, age = 30)
        val heavySettings = UserSettings(weightKg = 90, age = 30)

        val lightKcal = estimator.estimateKcal(speedKmh = 22.0, intervalMs = 60_000L, userSettings = lightSettings)
        val heavyKcal = estimator.estimateKcal(speedKmh = 22.0, intervalMs = 60_000L, userSettings = heavySettings)

        assertThat(heavyKcal).isGreaterThan(lightKcal)
    }

    // ── Grade awareness ────────────────────────────────────────────────────

    @Test
    fun `uphill burns more calories than flat ground`() {
        val settings = UserSettings(weightKg = 75, age = 30)
        val flat = estimator.estimateKcal(speedKmh = 15.0, intervalMs = 60_000L, userSettings = settings, gradePercent = 0.0)
        val uphill = estimator.estimateKcal(speedKmh = 15.0, intervalMs = 60_000L, userSettings = settings, gradePercent = 5.0)
        assertThat(uphill).isGreaterThan(flat)
    }

    @Test
    fun `downhill burns fewer calories than flat ground`() {
        val settings = UserSettings(weightKg = 75, age = 30)
        val flat = estimator.estimateKcal(speedKmh = 15.0, intervalMs = 60_000L, userSettings = settings, gradePercent = 0.0)
        val downhill = estimator.estimateKcal(speedKmh = 15.0, intervalMs = 60_000L, userSettings = settings, gradePercent = -5.0)
        assertThat(downhill).isLessThan(flat)
    }

    @Test
    fun `grade factor has a floor even on very steep downhill`() {
        val settings = UserSettings(weightKg = 75, age = 30)
        val flat = estimator.estimateKcal(speedKmh = 15.0, intervalMs = 60_000L, userSettings = settings, gradePercent = 0.0)
        val steepDownhill = estimator.estimateKcal(speedKmh = 15.0, intervalMs = 60_000L, userSettings = settings, gradePercent = -30.0)
        // Even on steep downhill, there's a minimum calorie burn (coasting + balance)
        assertThat(steepDownhill).isGreaterThan(0.0)
        assertThat(steepDownhill).isLessThan(flat)
    }
}
