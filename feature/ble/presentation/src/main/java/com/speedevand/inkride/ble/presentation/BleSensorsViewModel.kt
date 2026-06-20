package com.speedevand.inkride.ble.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.ble.BleScanner
import com.speedevand.inkride.core.domain.ble.BleSensorType
import com.speedevand.inkride.core.domain.onFailure
import com.speedevand.inkride.core.domain.onSuccess
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.presentation.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BleSensorsViewModel(
    private val scanner: BleScanner,
    private val userSettingsRepository: UserSettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BleSensorsState())
    val state = _state.asStateFlow()

    private val _events = Channel<BleSensorsEvent>()
    val events = _events.receiveAsFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            userSettingsRepository.observeSettings().collect { settings ->
                _state.update {
                    it.copy(
                        pairedHrmAddress = settings.pairedHrmAddress,
                        pairedCadenceAddress = settings.pairedCadenceAddress
                    )
                }
            }
        }
    }

    fun onAction(action: BleSensorsAction) {
        when (action) {
            is BleSensorsAction.OnScanClick -> startScan(action.type)
            BleSensorsAction.OnStopScanClick -> stopScan()
            is BleSensorsAction.OnDeviceClick -> pair(action.device.type, action.device.address)
            is BleSensorsAction.OnForgetClick -> pair(action.type, address = null)
            BleSensorsAction.OnBackClick -> viewModelScope.launch {
                _events.send(BleSensorsEvent.NavigateBack)
            }
        }
    }

    private fun startScan(type: BleSensorType) {
        scanner.available()
            .onFailure { error ->
                viewModelScope.launch { _events.send(BleSensorsEvent.ShowError(error.toUiText())) }
            }
            .onSuccess {
                scanJob?.cancel()
                _state.update { it.copy(scanningType = type, discovered = emptyList()) }
                scanJob = viewModelScope.launch {
                    scanner.scan(type).collect { device ->
                        _state.update { current ->
                            if (current.discovered.any { it.address == device.address }) current
                            else current.copy(discovered = current.discovered + device)
                        }
                    }
                }
            }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanningType = null, discovered = emptyList()) }
    }

    private fun pair(type: BleSensorType, address: String?) {
        stopScan()
        viewModelScope.launch {
            val settings = userSettingsRepository.observeSettings().first()
            val updated = when (type) {
                BleSensorType.HEART_RATE -> settings.copy(pairedHrmAddress = address)
                BleSensorType.CADENCE -> settings.copy(pairedCadenceAddress = address)
            }
            userSettingsRepository.save(updated).onFailure { error ->
                _events.send(BleSensorsEvent.ShowError(error.toUiText()))
            }
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}
