package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.presentation.DesignConstants

@Composable
fun MetricRow(
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_LARGE),
        verticalAlignment = Alignment.Top,
        content = content
    )
}

@Composable
fun MetricItem(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_TINY / 2)
    ) {
        TextMMD(
            text = label.uppercase(),
            style = DashboardTextStyles.caption,
            color = MaterialTheme.colorScheme.outline
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            TextMMD(
                text = value,
                style = DashboardTextStyles.metricValue
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(DesignConstants.PADDING_TINY / 2))
                TextMMD(
                    text = unit,
                    style = DashboardTextStyles.unit,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = DesignConstants.PADDING_TINY)
                )
            }
        }
    }
}
