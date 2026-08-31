package com.speedevand.inkride.history.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A static, monochrome altitude-vs-distance line for a ride. Drawn once per
 * composition with no animation or gesture handling, consistent with the
 * app's E-Ink display constraint.
 */
@Composable
fun ElevationChart(
    chart: ElevationChartUi,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurface
    val minAltitudeM = chart.points.minOf { it.altitudeM }
    val maxAltitudeM = chart.points.maxOf { it.altitudeM }
    val altitudeRangeM = (maxAltitudeM - minAltitudeM).let { if (it > 0.0) it else 1.0 }
    val maxDistanceKm =
        chart.points
            .last()
            .distanceKm
            .let { if (it > 0.0) it else 1.0 }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(120.dp),
    ) {
        val path = Path()
        chart.points.forEachIndexed { index, point ->
            val x = (point.distanceKm / maxDistanceKm).toFloat() * size.width
            val y = size.height - ((point.altitudeM - minAltitudeM) / altitudeRangeM).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = 3.dp.toPx()))

        drawAltitudeLabel(chart.maxAltitudeLabel, chart.maxAltitudeDistanceFraction, color, nearTop = true)
        drawAltitudeLabel(chart.minAltitudeLabel, chart.minAltitudeDistanceFraction, color, nearTop = false)
    }
}

private fun DrawScope.drawAltitudeLabel(
    text: String,
    xFraction: Float,
    color: Color,
    nearTop: Boolean,
) {
    val labelMarginPx = 24.dp.toPx()
    val x = (xFraction * size.width).coerceIn(labelMarginPx, size.width - labelMarginPx)
    val y = if (nearTop) 14.dp.toPx() else size.height - 4.dp.toPx()
    drawContext.canvas.nativeCanvas.drawText(
        text,
        x,
        y,
        android.graphics.Paint().apply {
            this.color = color.toArgb()
            this.textAlign = android.graphics.Paint.Align.CENTER
            this.textSize = 12.sp.toPx()
        },
    )
}
