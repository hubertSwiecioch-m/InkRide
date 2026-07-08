package com.speedevand.inkride.core.domain.tracking

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance between two lat/lng points, in meters (haversine formula). */
fun haversineMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = (lat2 - lat1).toRadians()
    val dLon = (lon2 - lon1).toRadians()
    val a =
        sin(dLat / 2).pow(2) +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2).pow(2)
    return earthRadiusM * 2 * asin(min(1.0, sqrt(a)))
}

private fun Double.toRadians(): Double = this * PI / 180.0
