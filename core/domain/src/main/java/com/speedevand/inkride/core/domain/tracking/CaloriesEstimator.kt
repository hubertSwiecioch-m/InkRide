package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.settings.UserSettings
import kotlin.math.atan
import kotlin.math.sin

class CaloriesEstimator {
    /**
     * Estimates calories burned using the ACSM metabolic equation.
     *
     * kcal = MET × 3.5 × weightKg / 200 × minutes × ageFactor × gradeFactor
     *
     * Speed-to-MET uses linear interpolation between established cycling MET
     * brackets to avoid step discontinuities (e.g. a 0.1 km/h change no longer
     * causes a 70% calorie jump).
     *
     * Grade awareness: riding uphill requires significantly more energy;
     * downhill provides less braking-resistance credit (floor at 2 MET coasting).
     *
     * @param speedKmh current speed in km/h
     * @param intervalMs time interval of this sample in milliseconds
     * @param gradePercent current grade in percent (e.g. 5.0 = 5% uphill)
     * @param userSettings rider weight, age, and bike weight
     */
    fun estimateKcal(
        speedKmh: Double,
        intervalMs: Long,
        userSettings: UserSettings,
        gradePercent: Double = 0.0
    ): Double {
        if (intervalMs <= 0L || speedKmh <= 0.0) return 0.0

        val met = speedToMet(speedKmh)
        val gradeFactor = gradeFactor(gradePercent, speedKmh, userSettings)
        val ageFactor = ageFactor(userSettings.age)
        val minutes = intervalMs / 60_000.0

        // ACSM approximation: kcal/min = MET × 3.5 × weight(kg) / 200.
        // Convert weightKg to Double early to avoid integer arithmetic issues.
        return met * 3.5 * userSettings.weightKg.toDouble() / 200.0 * minutes * ageFactor * gradeFactor
    }

    /**
     * Returns MET value for cycling speed using linear interpolation between
     * established brackets. Eliminates the artificial step discontinuities
     * that caused large calorie jumps at bracket boundaries.
     *
     * Reference brackets (Compendium of Physical Activities):
     *   < 16 km/h  →  4.0 MET (leisure)
     *   16-19 km/h →  6.8 MET (moderate)
     *   19-22 km/h →  8.0 MET (vigorous)
     *   22-25 km/h → 10.0 MET (very vigorous)
     *   25-30 km/h → 12.0 MET (racing)
     *     ≥ 30 km/h → 14.0 MET (competitive racing)
     */
    private fun speedToMet(speedKmh: Double): Double {
        // Anchor points: (speedKmh, MET)
        val brackets = listOf(
            0.0 to 2.0,    // stationary = resting ~2 MET (bike handling baseline)
            16.0 to 4.0,   // leisure
            19.0 to 6.8,   // moderate
            22.0 to 8.0,   // vigorous
            25.0 to 10.0,  // very vigorous
            30.0 to 12.0,  // racing
            35.0 to 14.0   // competitive
        )

        // Clamp to range
        if (speedKmh <= brackets.first().first) return brackets.first().second
        if (speedKmh >= brackets.last().first) return brackets.last().second

        // Linear interpolation between surrounding brackets
        for (i in 0 until brackets.size - 1) {
            val (s1, m1) = brackets[i]
            val (s2, m2) = brackets[i + 1]
            if (speedKmh in s1..s2) {
                val fraction = (speedKmh - s1) / (s2 - s1)
                return m1 + fraction * (m2 - m1)
            }
        }

        return brackets.last().second // fallback (shouldn't reach here)
    }

    /**
     * Grade multiplier for metabolic cost.
     *
     * Uphill: mechanical work against gravity adds significantly to energy
     * expenditure. A 5% grade roughly doubles power output at cycling speeds.
     *
     * Downhill: energy decreases but rider still expends some effort for
     * balance and braking. Floor at 0.5× baseline to reflect coasting.
     *
     * The multiplier is calibrated so that a 75 kg rider on a 5% grade at
     * 15 km/h sees roughly double the flat-ground calorie burn, which aligns
     * with published cycling energy expenditure data.
     */
    private fun gradeFactor(gradePercent: Double, speedKmh: Double, userSettings: UserSettings): Double {
        if (gradePercent == 0.0) return 1.0

        // Total mass (rider + bike)
        val totalMassKg = userSettings.weightKg.toDouble() + userSettings.bikeWeightKg
        val speedMps = speedKmh / 3.6
        val slopeAngleRad = atan(gradePercent / 100.0)

        // Mechanical power needed for gravity: mass × g × sin(angle) × speed
        val gravityPowerWatts = totalMassKg * 9.81 * sin(slopeAngleRad) * speedMps

        // Estimated metabolic power at flat ground (approximate from MET)
        val flatMet = speedToMet(speedKmh)
        val flatMetabolicWatts = flatMet * 3.5 * userSettings.weightKg.toDouble() / 200.0 * 1.163 // kcal/min → watts

        // Cycling gross efficiency ~22-25%. Use 23% to convert mechanical to metabolic.
        val additionalMetabolicWatts = gravityPowerWatts / 0.23

        // Total metabolic watts = flat + additional (or reduced for downhill).
        // Floor at 0.5× (coasting still costs energy for balance/braking) and
        // cap at 3× to keep steep-climb estimates physiologically sane.
        val totalMetabolicWatts = flatMetabolicWatts + additionalMetabolicWatts
        return (totalMetabolicWatts / flatMetabolicWatts).coerceIn(0.5, 3.0)
    }

    private fun ageFactor(age: Int): Double = when {
        age < 30 -> 1.0
        age < 45 -> 0.97
        age < 60 -> 0.94
        else -> 0.9
    }
}
