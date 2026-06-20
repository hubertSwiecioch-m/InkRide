package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.UserSettings
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

class PowerEstimator {
    /**
     * Estimates power in Watts based on physical forces.
     *
     * P_total = P_rolling + P_air + P_gravity + P_accel
     *
     * ## Accuracy limitations (no power meter)
     *
     * Without an actual power meter this is a first-principles approximation.
     * Typical accuracy: ±30-60% depending on conditions.
     *
     * **Dominant error sources** (in order of impact):
     * 1. **Wind** — not measured. Headwind/tailwind directly adds/subtracts from
     *    effective airspeed. A 10 km/h headwind at 25 km/h riding speed increases
     *    air drag by ~70%. This is the single largest error source.
     * 2. **CdA (aerodynamic drag)** — depends on rider position, clothing,
     *    helmet, and body morphology. Our per-bike-type values assume a typical
     *    on-hoods position. A rider in the drops has ~20% lower CdA; sitting
     *    upright ~40% higher.
     * 3. **Crr (rolling resistance)** — varies with tire pressure, tire width,
     *    tread pattern, tube type (butyl/latex/tubeless), and road surface.
     *    Our values assume medium-pressure clinchers on average pavement.
     * 4. **Drivetrain losses** — chain, derailleur pulleys, bottom bracket
     *    friction (~2-5% total) are not modeled.
     *
     * @param speedMps current speed in meters per second
     * @param accelerationMps2 current acceleration in m/s² (EMA-smoothed)
     * @param gradePercent current slope in percent (e.g., 5.0 for 5% uphill)
     * @param userSettings user settings containing weight and bike type
     */
    fun estimateWatts(
        speedMps: Double,
        accelerationMps2: Double,
        gradePercent: Double,
        userSettings: UserSettings,
    ): Int {
        if (speedMps <= 0.1) return 0

        val totalMassKg = userSettings.weightKg + userSettings.bikeWeightKg
        val gravity = 9.81
        val airDensity = 1.225 // kg/m³ at sea level, 15°C

        val slopeAngleRad = atan(gradePercent / 100.0)

        // 1. Rolling Resistance
        // P_rolling = Crr × m × g × v × cos(θ)
        //
        // Crr values assume medium-pressure clinchers on average pavement.
        // Actual Crr ranges by setup:
        //   Road (23mm @100psi, smooth asphalt):   0.003-0.005
        //   Road (28mm @70psi, rough asphalt):     0.005-0.008
        //   Gravel (38mm @40psi):                  0.006-0.010
        //   MTB (2.2" @25psi, dirt):              0.008-0.015
        //   City (35mm @60psi, mixed surface):    0.006-0.010
        val crr =
            when (userSettings.bikeType) {
                BikeType.ROAD -> 0.005
                BikeType.MTB -> 0.012
                BikeType.CITY -> 0.008
            }
        val pRolling = crr * totalMassKg * gravity * speedMps * cos(slopeAngleRad)

        // 2. Air Resistance
        // P_air = 0.5 × ρ × CdA × v³
        //
        // CdA = drag coefficient × frontal area (m²). Typical values:
        //   Road (on hoods):   0.30-0.35
        //   Road (in drops):   0.25-0.30
        //   Road (aero bars):  0.20-0.25
        //   MTB (upright):     0.40-0.55
        //   City (upright):    0.35-0.50
        //
        // Our values assume typical on-hoods / moderate-upright position.
        // Wind is the dominant unmeasured variable here.
        val cda =
            when (userSettings.bikeType) {
                BikeType.ROAD -> 0.32
                BikeType.MTB -> 0.45
                BikeType.CITY -> 0.40
            }
        val pAir = 0.5 * cda * airDensity * (speedMps * speedMps * speedMps)

        // 3. Gravity
        // P_gravity = m × g × v × sin(θ)
        // Positive on uphills (adds to required power), negative on downhills.
        val pGravity = totalMassKg * gravity * speedMps * sin(slopeAngleRad)

        // 4. Acceleration
        // P_accel = m × a × v
        // Accounts for energy going into changing kinetic energy.
        val pAccel = totalMassKg * accelerationMps2 * speedMps

        val totalPower = pRolling + pAir + pGravity + pAccel

        // Drivetrain efficiency ~95% — multiply by 1/0.95 ≈ 1.05 to get power at crank.
        // Keeping it simple for now; this is within the noise of CdA uncertainty.
        return (totalPower * 1.05).coerceAtLeast(0.0).toInt()
    }

    companion object {
        /**
         * Estimated accuracy range for power estimation.
         * Displayed in UI to set user expectations.
         */
        const val ACCURACY_NOTE = "Estimated power (±30-60%). Use a power meter for precise data."
    }
}
