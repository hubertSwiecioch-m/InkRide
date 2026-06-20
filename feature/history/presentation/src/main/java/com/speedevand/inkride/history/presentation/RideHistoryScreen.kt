package com.speedevand.inkride.history.presentation

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.snackbar.SnackbarDurationMMD
import com.mudita.mmd.components.snackbar.SnackbarHostMMD
import com.mudita.mmd.components.snackbar.SnackbarHostStateMMD
import com.mudita.mmd.components.snackbar.SnackbarResultMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.speedevand.inkride.core.presentation.ObserveAsEvents
import com.speedevand.inkride.core.presentation.verticalScrollbar
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun RideHistoryRoot(
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToLifetimeStats: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: RideHistoryViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostStateMMD() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.ride_history_deleted)
    val undoLabel = stringResource(R.string.ride_history_undo)

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is RideHistoryEvent.NavigateToDetail -> onNavigateToDetail(event.id)
            RideHistoryEvent.NavigateToLifetimeStats -> onNavigateToLifetimeStats()
            is RideHistoryEvent.ShowError -> {
                Toast.makeText(
                    context,
                    event.message.asString(context),
                    Toast.LENGTH_LONG
                ).show()
            }
            RideHistoryEvent.ShowUndoSnackbar -> {
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = deletedMessage,
                        actionLabel = undoLabel,
                        duration = SnackbarDurationMMD.Long
                    )
                    if (result == SnackbarResultMMD.ActionPerformed) {
                        viewModel.onAction(RideHistoryAction.OnUndoDelete)
                    }
                }
            }
        }
    }

    RideHistoryScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onAction = viewModel::onAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideHistoryScreen(
    state: RideHistoryState,
    snackbarHostState: SnackbarHostStateMMD,
    onAction: (RideHistoryAction) -> Unit
) {
    var showConfirmDeleteAll by remember { mutableStateOf(false) }

    if (showConfirmDeleteAll) {
        ModalBottomSheetMMD(
            onDismissRequest = { showConfirmDeleteAll = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextMMD(
                    text = stringResource(R.string.ride_history_delete_all_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextMMD(
                    text = stringResource(R.string.ride_history_delete_all_message),
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButtonMMD(
                        onClick = { showConfirmDeleteAll = false },
                        modifier = Modifier.weight(1f)
                    ) {
                        TextMMD(text = stringResource(R.string.history_action_cancel))
                    }
                    ButtonMMD(
                        onClick = {
                            onAction(RideHistoryAction.OnDeleteAll)
                            showConfirmDeleteAll = false
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
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = stringResource(R.string.ride_history_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { onAction(RideHistoryAction.OnLifetimeStatsClick) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = stringResource(R.string.ride_history_stats)
                        )
                    }
                    if (state.rides.isNotEmpty()) {
                        IconButton(onClick = { showConfirmDeleteAll = true }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.ride_history_delete_all)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHostMMD(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
        ) {
            if (state.rides.isEmpty() && !state.isLoading) {
                TextMMD(
                    text = stringResource(R.string.ride_history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 24.dp)
                )
            } else {
                val lazyListState = rememberLazyListState()
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .weight(1f)
                        .verticalScrollbar(lazyListState)
                ) {
                    items(state.rides, key = { it.id }) { ride ->
                        RideHistoryItem(
                            ride = ride,
                            onClick = { onAction(RideHistoryAction.OnRideClick(ride.id)) },
                            onDelete = { onAction(RideHistoryAction.OnDeleteRide(ride.id)) }
                        )
                        HorizontalDividerMMD()
                    }
                }
            }
        }
    }
}

@Composable
private fun RideHistoryItem(
    ride: RideRecordUi,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val separator = "  ·  "
            TextMMD(text = ride.formattedDate, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            TextMMD(text = "${ride.distanceKm}$separator${ride.movingTime}$separator${ride.averageSpeedKmh}", style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = onDelete) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.ride_history_cd_delete))
        }
    }
}

@Preview
@Composable
private fun RideHistoryScreenPreview() {
    InkRideTheme {
        RideHistoryScreen(
            state = RideHistoryState(
                isLoading = false,
                rides = listOf(
                    RideRecordUi(
                        id = 1L,
                        formattedDate = "14 Jun 2026, 08:32",
                        distanceKm = "18.42 km",
                        movingTime = "00:48:12",
                        averageSpeedKmh = "23.8 km/h"
                    ),
                    RideRecordUi(
                        id = 2L,
                        formattedDate = "12 Jun 2026, 17:05",
                        distanceKm = "42.10 km",
                        movingTime = "01:54:03",
                        averageSpeedKmh = "22.1 km/h"
                    )
                )
            ),
            snackbarHostState = remember { SnackbarHostStateMMD() },
            onAction = {}
        )
    }
}
