package com.speedevand.inkride.history.presentation

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.speedevand.inkride.core.design_system.InkRideTheme
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.speedevand.inkride.core.presentation.ObserveAsEvents
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun RideDetailRoot(
    rideId: Long,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val exportShareTitle = stringResource(R.string.ride_detail_export_share_title)
    val viewModel: RideDetailViewModel = koinViewModel(
        key = rideId.toString(),
        parameters = { parametersOf(rideId) }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            RideDetailEvent.NavigateBack -> onNavigateBack()
            is RideDetailEvent.ShowError -> {
                Toast.makeText(
                    context,
                    event.message.asString(context),
                    Toast.LENGTH_LONG
                ).show()
            }
            is RideDetailEvent.ShareGpx -> {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(Intent.EXTRA_STREAM, event.uri)
                    // ClipData carries the URI grant to whichever target the chooser
                    // resolves, which is more reliable across OEMs than the flag alone.
                    clipData = ClipData.newRawUri(null, event.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(
                        shareIntent,
                        exportShareTitle
                    )
                )
            }
        }
    }

    RideDetailScreen(state = state, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailScreen(
    state: RideDetailState,
    onAction: (RideDetailAction) -> Unit
) {
    var showConfirmDelete by remember { mutableStateOf(value = false) }
    var showRouteMap by remember { mutableStateOf(value = false) }

    if (showRouteMap) {
        // The route map lives in a bottom sheet rather than inline: a MapView
        // inside the detail's verticalScroll fights the scroll for vertical
        // gestures and only gets a cramped slice of the screen. The sheet opens
        // fully expanded, giving a large, freely-pannable map.
        ModalBottomSheetMMD(
            onDismissRequest = { showRouteMap = false },
            sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TextMMD(
                    text = stringResource(R.string.ride_detail_section_route),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                RideRouteMap(
                    points = state.trackPoints,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
                )
            }
        }
    }

    if (showConfirmDelete) {
        ModalBottomSheetMMD(
            onDismissRequest = { showConfirmDelete = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextMMD(
                    text = stringResource(R.string.ride_detail_delete_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextMMD(
                    text = stringResource(R.string.ride_detail_delete_message),
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButtonMMD(
                        onClick = { showConfirmDelete = false },
                        modifier = Modifier.weight(1f)
                    ) {
                        TextMMD(text = stringResource(R.string.history_action_cancel))
                    }
                    ButtonMMD(
                        onClick = {
                            onAction(RideDetailAction.OnDeleteClick)
                            showConfirmDelete = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        TextMMD(text = stringResource(R.string.history_action_delete))
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = stringResource(R.string.ride_detail_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(RideDetailAction.OnBackClick) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ride_detail_cd_back)
                        )
                    }
                },
                actions = {
                    if (state.ride != null) {
                        IconButton(onClick = { onAction(RideDetailAction.OnExportGpxClick) }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.ride_detail_cd_export)
                            )
                        }
                        IconButton(onClick = { showConfirmDelete = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.ride_detail_cd_delete)
                            )
                        }
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        val ride = state.ride
        if (ride == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (!state.isLoading) {
                    TextMMD(text = stringResource(R.string.ride_detail_not_found))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RideDetailSection(title = stringResource(R.string.ride_detail_section_time)) {
                    DetailRow(label = stringResource(R.string.ride_detail_start), value = ride.formattedDate)
                    DetailRow(label = stringResource(R.string.ride_detail_end), value = ride.formattedEndDate)
                    DetailRow(label = stringResource(R.string.ride_detail_moving_time), value = ride.movingTime)
                    DetailRow(label = stringResource(R.string.ride_detail_session_time), value = ride.elapsedTime)
                }

                RideDetailSection(title = stringResource(R.string.ride_detail_section_performance)) {
                    DetailRow(label = stringResource(R.string.ride_detail_distance), value = ride.distanceKm)
                    DetailRow(label = stringResource(R.string.ride_detail_avg_speed), value = ride.averageSpeedKmh)
                    DetailRow(label = stringResource(R.string.ride_detail_max_speed), value = ride.maxSpeedKmh)
                }

                RideDetailSection(
                    title = stringResource(R.string.ride_detail_section_additional),
                    showDivider = state.trackPoints.isNotEmpty() || state.laps.isNotEmpty()
                ) {
                    DetailRow(label = stringResource(R.string.ride_detail_elevation_gain), value = ride.elevationGainM)
                    DetailRow(label = stringResource(R.string.ride_detail_calories), value = ride.caloriesKcal)
                    DetailRow(label = stringResource(R.string.ride_detail_avg_power), value = ride.averagePowerWatts)
                }

                if (state.trackPoints.isNotEmpty()) {
                    RideDetailSection(
                        title = stringResource(R.string.ride_detail_section_route),
                        showDivider = state.laps.isNotEmpty()
                    ) {
                        OutlinedButtonMMD(
                            onClick = { showRouteMap = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextMMD(text = stringResource(R.string.ride_detail_show_route))
                        }
                    }
                }

                if (state.laps.isNotEmpty()) {
                    RideDetailSection(
                        title = stringResource(R.string.ride_detail_section_laps),
                        showDivider = false
                    ) {
                        LapHeaderRow()
                        state.laps.forEach { lap -> LapRow(lap) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RideDetailSection(
    title: String,
    showDivider: Boolean = true,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextMMD(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        content()
        if (showDivider) {
            HorizontalDivider()
        }
    }
}

@Composable
private fun LapHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextMMD(
            text = stringResource(R.string.ride_detail_lap_number),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(0.8f)
        )
        TextMMD(
            text = stringResource(R.string.ride_detail_lap_distance),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1.4f)
        )
        TextMMD(
            text = stringResource(R.string.ride_detail_lap_time),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1.4f)
        )
        TextMMD(
            text = stringResource(R.string.ride_detail_lap_speed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1.4f)
        )
    }
}

@Composable
private fun LapRow(lap: RideLapUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextMMD(text = lap.lapNumber, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
        TextMMD(text = lap.distance, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1.4f))
        TextMMD(text = lap.time, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1.4f))
        TextMMD(text = lap.averageSpeed, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1.4f))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyLarge)
        TextMMD(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Preview
@Composable
private fun RideDetailScreenPreview() {
    InkRideTheme {
        RideDetailScreen(
            state = RideDetailState(
                isLoading = false,
                ride = RideDetailUi(
                    id = 1L,
                    formattedDate = "14 Jun 2026, 08:32",
                    formattedEndDate = "14 Jun 2026, 09:24",
                    distanceKm = "18.42 km",
                    movingTime = "00:48:12",
                    elapsedTime = "00:52:01",
                    averageSpeedKmh = "23.8 km/h",
                    maxSpeedKmh = "51.3 km/h",
                    elevationGainM = "312 m",
                    caloriesKcal = "640 kcal",
                    averagePowerWatts = "185 W"
                )
            ),
        ) { }
    }
}
