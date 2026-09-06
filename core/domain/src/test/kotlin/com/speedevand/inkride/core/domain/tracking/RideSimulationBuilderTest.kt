package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import kotlin.math.PI
import org.junit.jupiter.api.Test

class RideSimulationBuilderTest {
    @Test
    fun `flat phase produces correct sample count and ground truth distance`() {
        val ride =
            RideSimulationBuilder.build(
                listOf(SimPhase(name = "cruise", terrain = SimTerrain.FLAT, speedKmh = 18.0, durationMs = 10_000L)),
            )
        assertThat(ride.samples).hasSize(10)
        assertThat(ride.phases).hasSize(1)
        // 18 km/h = 5.0 m/s over 10s = 50m
        assertThat(ride.phases.single().distanceM).isCloseTo(50.0, 0.01)
    }

    @Test
    fun `climb phase produces correct altitude gain ground truth`() {
        val ride =
            RideSimulationBuilder.build(
                phases =
                    listOf(
                        SimPhase(name = "climb", terrain = SimTerrain.CLIMB, speedKmh = 12.0, gradePercent = 6.0, durationMs = 10_000L),
                    ),
                startAltitudeM = 100.0,
            )
        // 12 km/h = 3.3333 m/s over 10s = 33.333m distance; altitude change = 33.333 * 0.06 = 2.0m
        assertThat(ride.phases.single().altitudeChangeM).isCloseTo(2.0, 0.01)
        assertThat(ride.samples.last().altitudeFromBarometerM!!).isCloseTo(102.0, 0.01)
    }

    @Test
    fun `descent phase produces negative altitude change`() {
        val ride =
            RideSimulationBuilder.build(
                listOf(SimPhase(name = "descent", terrain = SimTerrain.DESCENT, speedKmh = 30.0, gradePercent = -6.0, durationMs = 10_000L)),
            )
        assertThat(ride.phases.single().altitudeChangeM).isLessThan(0.0)
    }

    @Test
    fun `stop phase produces zero distance and zero GPS speed samples`() {
        val ride =
            RideSimulationBuilder.build(
                listOf(SimPhase(name = "stop", terrain = SimTerrain.STOP, durationMs = 5000L)),
            )
        assertThat(ride.phases.single().distanceM).isEqualTo(0.0)
        ride.samples.forEach { sample -> assertThat(sample.speedFromGpsMps).isEqualTo(0.0) }
    }

    @Test
    fun `gps dropout phase omits location but ground truth still advances`() {
        val ride =
            RideSimulationBuilder.build(
                listOf(SimPhase(name = "tunnel", terrain = SimTerrain.FLAT, speedKmh = 20.0, durationMs = 5000L, gpsDropout = true)),
            )
        ride.samples.forEach { sample ->
            assertThat(sample.latitude).isNull()
            assertThat(sample.longitude).isNull()
        }
        assertThat(ride.phases.single().distanceM).isGreaterThan(0.0)
    }

    @Test
    fun `bounce injection adds two samples without affecting ground truth distance`() {
        val plainRide =
            RideSimulationBuilder.build(
                listOf(SimPhase(name = "cruise", terrain = SimTerrain.FLAT, speedKmh = 15.0, durationMs = 10_000L)),
            )
        val bouncedRide =
            RideSimulationBuilder.build(
                listOf(
                    SimPhase(
                        name = "cruise",
                        terrain = SimTerrain.FLAT,
                        speedKmh = 15.0,
                        durationMs = 10_000L,
                        bounce = BounceSpec(afterStepIndex = 4, jumpMeters = 33.0),
                    ),
                ),
            )
        assertThat(bouncedRide.samples).hasSize(plainRide.samples.size + 2)
        assertThat(bouncedRide.phases.single().distanceM).isCloseTo(plainRide.phases.single().distanceM, 0.01)

        val stepBeforeJump = bouncedRide.samples[4]
        val jumpSample = bouncedRide.samples[5]
        val jumpOffsetM = (jumpSample.latitude!! - stepBeforeJump.latitude!!) * PI / 180.0 * 6_371_000.0
        assertThat(jumpOffsetM).isCloseTo(33.0, 0.5)
    }
}
