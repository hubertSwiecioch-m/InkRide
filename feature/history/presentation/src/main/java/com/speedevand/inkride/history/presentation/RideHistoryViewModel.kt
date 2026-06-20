package com.speedevand.inkride.history.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.onFailure
import com.speedevand.inkride.core.domain.onSuccess
import com.speedevand.inkride.core.presentation.toUiText
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RideHistoryViewModel(
    private val rideHistoryRepository: RideHistoryRepository,
    private val userSettingsRepository: UserSettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RideHistoryState())
    val state = _state.asStateFlow()

    private val _events = Channel<RideHistoryEvent>()
    val events = _events.receiveAsFlow()

    private var recentlyDeletedRide: RideRecord? = null

    init {
        viewModelScope.launch {
            combine(
                rideHistoryRepository.observeAll(),
                userSettingsRepository.observeSettings()
            ) { rides, settings ->
                rides.map { r -> r.toUi(settings.units) }
            }.collect { uiRides ->
                _state.update {
                    it.copy(rides = uiRides, isLoading = false)
                }
            }
        }
    }

    fun onAction(action: RideHistoryAction) {
        when (action) {
            is RideHistoryAction.OnRideClick -> viewModelScope.launch {
                _events.send(RideHistoryEvent.NavigateToDetail(action.id))
            }
            is RideHistoryAction.OnDeleteRide -> viewModelScope.launch {
                rideHistoryRepository.getById(action.id).onSuccess { ride ->
                    recentlyDeletedRide = ride
                    rideHistoryRepository.deleteById(action.id).onSuccess {
                        _events.send(RideHistoryEvent.ShowUndoSnackbar)
                    }.onFailure { error ->
                        _events.send(RideHistoryEvent.ShowError(error.toUiText()))
                    }
                }.onFailure { error ->
                    _events.send(RideHistoryEvent.ShowError(error.toUiText()))
                }
            }
            RideHistoryAction.OnUndoDelete -> viewModelScope.launch {
                recentlyDeletedRide?.let { ride ->
                    rideHistoryRepository.save(ride).onFailure { error ->
                        _events.send(RideHistoryEvent.ShowError(error.toUiText()))
                    }
                    recentlyDeletedRide = null
                }
            }
            RideHistoryAction.OnDeleteAll -> viewModelScope.launch {
                rideHistoryRepository.deleteAll().onFailure { error ->
                    _events.send(RideHistoryEvent.ShowError(error.toUiText()))
                }
            }
            RideHistoryAction.OnLifetimeStatsClick -> viewModelScope.launch {
                _events.send(RideHistoryEvent.NavigateToLifetimeStats)
            }
        }
    }
}
