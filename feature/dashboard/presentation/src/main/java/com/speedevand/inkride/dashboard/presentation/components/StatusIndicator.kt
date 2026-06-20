package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.dashboard.presentation.TrackingStatus

@Composable
fun StatusIndicator(status: TrackingStatus) {
    val isActive = status != TrackingStatus.IDLE
    val color =
        if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        }

    Row(
        horizontalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_SMALL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Filled dot while recording, hollow when paused/auto-paused/idle —
        // a static, E-Ink-friendly cue that distinguishes "live" at a glance.
        val dot =
            Modifier
                .size(10.dp)
                .clip(CircleShape)
        Box(
            modifier =
                if (status == TrackingStatus.TRACKING) {
                    dot.background(color)
                } else {
                    dot.border(2.dp, color, CircleShape)
                },
        )
        TextMMD(
            text =
                when (status) {
                    TrackingStatus.TRACKING -> stringResource(R.string.dashboard_status_recording)
                    TrackingStatus.PAUSED -> stringResource(R.string.dashboard_status_paused)
                    TrackingStatus.AUTO_PAUSED -> stringResource(R.string.dashboard_status_auto_paused)
                    TrackingStatus.IDLE -> stringResource(R.string.dashboard_status_ready)
                },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}
