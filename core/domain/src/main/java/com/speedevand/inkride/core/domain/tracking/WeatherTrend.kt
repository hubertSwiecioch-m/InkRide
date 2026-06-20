package com.speedevand.inkride.core.domain.tracking

/**
 * Coarse local weather hint derived from the barometric pressure trend over the
 * last hour. No internet or weather API is involved — this is purely the
 * device's own barometer.
 *
 * - [RISING]  — pressure climbing: weather generally improving (clearing/drier).
 * - [FALLING] — pressure dropping: weather generally worsening (clouds/rain).
 * - [STABLE]  — pressure roughly steady: no strong short-term change.
 * - [UNKNOWN] — not enough barometer history yet (or no barometer) to classify.
 */
enum class WeatherTrend {
    RISING,
    FALLING,
    STABLE,
    UNKNOWN,
}
