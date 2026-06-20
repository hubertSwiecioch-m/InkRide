package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.dashboard.presentation.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun Compass(
    bearing: Float?,
    modifier: Modifier = Modifier,
) {
    // Remove animation and use discrete steps (2 degrees) for E-Ink friendliness
    val currentBearing = bearing?.let { (it / 2f).roundToInt() * 2f } ?: 0f

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TextMMD(
            text = stringResource(R.string.dashboard_label_compass),
            style = DashboardTextStyles.caption,
            color = MaterialTheme.colorScheme.outline,
        )

        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            CompassRose(bearing = currentBearing)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextMMD(
                    text = "${(bearing ?: 0f).toInt()}°",
                    style = DashboardTextStyles.metricValue,
                )
                TextMMD(
                    text = getDirectionString(bearing ?: 0f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CompassRose(bearing: Float) {
    val color = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 - 12.dp.toPx()

        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(width = 2.dp.toPx()),
        )

        for (angle in 0 until 360 step 30) {
            if (angle % 90 == 0) continue
            rotate(angle.toFloat(), center) {
                drawLine(
                    color = color,
                    start = Offset(center.x, center.y - radius),
                    end = Offset(center.x, center.y - radius + 4.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }

        val directions =
            listOf(
                0 to "N",
                90 to "E",
                180 to "S",
                270 to "W",
            )

        directions.forEach { (angle, label) ->
            val rad = Math.toRadians((angle - 90).toDouble())
            val x = center.x + (radius - 28.dp.toPx()) * cos(rad).toFloat()
            val y = center.y + (radius - 28.dp.toPx()) * sin(rad).toFloat()

            drawContext.canvas.nativeCanvas.drawText(
                label,
                x,
                y + 6.dp.toPx(),
                android.graphics.Paint().apply {
                    this.color = color.toArgb()
                    this.textAlign = android.graphics.Paint.Align.CENTER
                    this.textSize = 16.sp.toPx()
                    this.isFakeBoldText = true
                },
            )
        }

        // Triangle at the edge (avoids obscuring the center bearing text).
        // Rotated by bearing so the pointer indicates heading on the fixed rose.
        rotate(degrees = bearing, pivot = center) {
            val markerPath =
                Path().apply {
                    moveTo(center.x, center.y - radius - 2.dp.toPx())
                    lineTo(center.x - 10.dp.toPx(), center.y - radius + 15.dp.toPx())
                    lineTo(center.x + 10.dp.toPx(), center.y - radius + 15.dp.toPx())
                    close()
                }
            drawPath(path = markerPath, color = color)
        }
    }
}

@Composable
private fun getDirectionString(bearing: Float): String {
    val normalized = (bearing + 360) % 360
    return when {
        normalized >= 337.5 || normalized < 22.5 -> "N"
        normalized >= 22.5 && normalized < 67.5 -> "NE"
        normalized >= 67.5 && normalized < 112.5 -> "E"
        normalized >= 112.5 && normalized < 157.5 -> "SE"
        normalized >= 157.5 && normalized < 202.5 -> "S"
        normalized >= 202.5 && normalized < 247.5 -> "SW"
        normalized >= 247.5 && normalized < 292.5 -> "W"
        normalized >= 292.5 && normalized < 337.5 -> "NW"
        else -> ""
    }
}
