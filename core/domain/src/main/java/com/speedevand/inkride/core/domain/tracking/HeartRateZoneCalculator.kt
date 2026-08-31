package com.speedevand.inkride.core.domain.tracking

/**
 * Heart-rate training zones derived from age alone (Tanaka formula), so no
 * new user-entered field (e.g. resting heart rate for a Karvonen/HRR
 * calculation) is required. Zone 1 is the lowest-intensity band, zone 5 the
 * highest.
 */
class HeartRateZoneCalculator {
    /** Age-predicted maximum heart rate (Tanaka, 2001): 208 − 0.7 × age. */
    fun maxHeartRateBpm(age: Int): Double = 208.0 - 0.7 * age

    /**
     * Zone (1-5) for [heartRateBpm] given [age], as a percentage-of-HRmax
     * band: Z1 <60%, Z2 60-70%, Z3 70-80%, Z4 80-90%, Z5 >=90%.
     */
    fun zoneFor(
        heartRateBpm: Int,
        age: Int,
    ): Int {
        val hrMax = maxHeartRateBpm(age)
        val percent = heartRateBpm / hrMax * 100.0
        return when {
            percent < 60.0 -> 1
            percent < 70.0 -> 2
            percent < 80.0 -> 3
            percent < 90.0 -> 4
            else -> 5
        }
    }
}
