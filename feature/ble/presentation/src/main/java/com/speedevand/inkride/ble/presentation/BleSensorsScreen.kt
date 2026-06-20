package com.speedevand.inkride.ble.presentation

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.speedevand.inkride.core.design_system.InkRideTheme
import com.speedevand.inkride.core.domain.ble.BleDevice
import com.speedevand.inkride.core.domain.ble.BleSensorType
import com.speedevand.inkride.core.presentation.ObserveAsEvents
import org.koin.androidx.compose.koinViewModel

@Composable
fun BleSensorsRoot(
    onNavigateBack: () -> Unit,
    viewModel: BleSensorsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ),
            )
        }
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            BleSensorsEvent.NavigateBack -> {
                onNavigateBack()
            }

            is BleSensorsEvent.ShowError -> {
                Toast.makeText(context, event.message.asString(context), Toast.LENGTH_LONG).show()
            }
        }
    }

    BleSensorsScreen(state = state, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleSensorsScreen(
    state: BleSensorsState,
    onAction: (BleSensorsAction) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = stringResource(R.string.ble_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(BleSensorsAction.OnBackClick) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ble_cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SensorSection(
                title = stringResource(R.string.ble_section_heart_rate),
                type = BleSensorType.HEART_RATE,
                pairedAddress = state.pairedHrmAddress,
                state = state,
                onAction = onAction,
            )
            HorizontalDividerMMD()
            SensorSection(
                title = stringResource(R.string.ble_section_cadence),
                type = BleSensorType.CADENCE,
                pairedAddress = state.pairedCadenceAddress,
                state = state,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun SensorSection(
    title: String,
    type: BleSensorType,
    pairedAddress: String?,
    state: BleSensorsState,
    onAction: (BleSensorsAction) -> Unit,
) {
    val isScanning = state.scanningType == type
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextMMD(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )

        if (pairedAddress != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextMMD(
                    text = stringResource(R.string.ble_paired_to, pairedAddress),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButtonMMD(onClick = { onAction(BleSensorsAction.OnForgetClick(type)) }) {
                    TextMMD(text = stringResource(R.string.ble_action_forget))
                }
            }
        } else {
            TextMMD(
                text = stringResource(R.string.ble_none_paired),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (isScanning) {
            ButtonMMD(
                onClick = { onAction(BleSensorsAction.OnStopScanClick) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextMMD(text = stringResource(R.string.ble_action_stop_scan))
            }
            if (state.discovered.isEmpty()) {
                TextMMD(
                    text = stringResource(R.string.ble_scanning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                state.discovered.forEach { device ->
                    DiscoveredDeviceRow(device = device, onClick = { onAction(BleSensorsAction.OnDeviceClick(device)) })
                }
            }
        } else {
            OutlinedButtonMMD(
                onClick = { onAction(BleSensorsAction.OnScanClick(type)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextMMD(text = stringResource(R.string.ble_action_scan))
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(
    device: BleDevice,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            TextMMD(
                text = device.name ?: stringResource(R.string.ble_unknown_device),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            TextMMD(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Preview
@Composable
private fun BleSensorsScreenPreview() {
    InkRideTheme {
        BleSensorsScreen(
            state =
                BleSensorsState(
                    pairedHrmAddress = "C4:2F:90:11:22:33",
                    scanningType = BleSensorType.CADENCE,
                    discovered =
                        listOf(
                            BleDevice("AA:BB:CC:DD:EE:FF", "Wahoo CADENCE", BleSensorType.CADENCE),
                        ),
                ),
            onAction = {},
        )
    }
}
