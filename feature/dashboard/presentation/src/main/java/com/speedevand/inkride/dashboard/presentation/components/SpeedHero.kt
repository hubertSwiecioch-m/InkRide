package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.dashboard.presentation.R

/**
 * The primary glanceable readout: current speed. The number scales with the
 * available width so it stays as large as possible on big displays without
 * clipping on small E-Ink panels. Caption-on-top keeps it visually consistent
 * with the supporting [MetricItem] grid below it.
 */
@Composable
fun SpeedHero(
    speed: String,
    unit: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TextMMD(
            text = stringResource(R.string.dashboard_label_speed),
            style = DashboardTextStyles.caption,
            color = MaterialTheme.colorScheme.outline,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // Scale the headline to the panel: large where there's room, smaller
            // on narrow screens. Bounds keep it readable without overflowing.
            // Speed updates on every GPS sample, so this is remembered by
            // maxWidth (constant for a fixed-size panel) to avoid recomputing
            // it on every tick.
            val heroSize = remember(maxWidth) { (maxWidth.value * 0.32f).coerceIn(56f, 88f).sp }
            TextMMD(
                text = speed,
                style = DashboardTextStyles.hero(heroSize),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        TextMMD(
            text = unit,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
