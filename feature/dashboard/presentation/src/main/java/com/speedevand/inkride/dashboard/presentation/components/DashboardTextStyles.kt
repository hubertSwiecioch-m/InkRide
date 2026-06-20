package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

/**
 * Shared dashboard text styles, all derived from the MMD (`eInkTypography`,
 * Lato) scale so the large glanceable numbers render in the design-system font
 * for crisp E-Ink output.
 *
 * The big readouts (speed, metric values, compass bearing) intentionally start
 * from `headlineLarge` — the largest Lato style MMD defines — and only override
 * size/weight. Using Material's undefined `display*` / `headlineMedium` styles
 * would silently fall back to the default (non-Lato) font.
 */
object DashboardTextStyles {
    /** Small uppercase caption above a value (e.g. "SPEED", "DISTANCE"). */
    val caption: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)

    /** Standard metric value in the grid. */
    val metricValue: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold)

    /** Unit suffix shown next to a value. */
    val unit: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.labelSmall

    /** The hero speed number, sized responsively by the caller. */
    @Composable
    @ReadOnlyComposable
    fun hero(fontSize: TextUnit): TextStyle =
        MaterialTheme.typography.headlineLarge.copy(
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight.Black,
        )
}
