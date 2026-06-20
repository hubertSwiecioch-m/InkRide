package com.speedevand.inkride.history.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.speedevand.inkride.core.design_system.InkRideTheme
import com.speedevand.inkride.core.presentation.ObserveAsEvents
import org.koin.androidx.compose.koinViewModel

@Composable
fun LifetimeStatsRoot(
    onNavigateBack: () -> Unit,
    viewModel: LifetimeStatsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            LifetimeStatsEvent.NavigateBack -> onNavigateBack()
        }
    }

    LifetimeStatsScreen(state = state, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifetimeStatsScreen(
    state: LifetimeStatsState,
    onAction: (LifetimeStatsAction) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = stringResource(R.string.lifetime_stats_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(LifetimeStatsAction.OnBackClick) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.lifetime_stats_cd_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatRow(stringResource(R.string.lifetime_stats_total_rides), state.stats.totalRides)
            HorizontalDividerMMD()
            StatRow(stringResource(R.string.lifetime_stats_total_distance), state.stats.totalDistance)
            HorizontalDividerMMD()
            StatRow(stringResource(R.string.lifetime_stats_total_time), state.stats.totalMovingTime)
            HorizontalDividerMMD()
            StatRow(stringResource(R.string.lifetime_stats_total_elevation), state.stats.totalElevationGain)
            HorizontalDividerMMD()
            StatRow(stringResource(R.string.lifetime_stats_max_speed), state.stats.maxSpeed)
            HorizontalDividerMMD()
            StatRow(stringResource(R.string.lifetime_stats_total_calories), state.stats.totalCalories)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyLarge)
        TextMMD(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Preview
@Composable
private fun LifetimeStatsScreenPreview() {
    InkRideTheme {
        LifetimeStatsScreen(
            state = LifetimeStatsState(
                isLoading = false,
                stats = LifetimeStatsUi(
                    totalRides = "42",
                    totalDistance = "1284.5 km",
                    totalMovingTime = "63h 12m",
                    totalElevationGain = "18420 m",
                    maxSpeed = "61.3 km/h",
                    totalCalories = "48210 kcal"
                )
            ),
            onAction = {}
        )
    }
}
