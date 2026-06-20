package com.speedevand.inkride.history.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.history.LifetimeStatsRepository
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class LifetimeStatsViewModel(
    private val lifetimeStatsRepository: LifetimeStatsRepository,
    private val userSettingsRepository: UserSettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LifetimeStatsState())
    val state = _state.asStateFlow()

    private val _events = Channel<LifetimeStatsEvent>()
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(
                lifetimeStatsRepository.observeLifetimeStats(),
                userSettingsRepository.observeSettings(),
            ) { stats, settings ->
                stats.toUi(settings.units)
            }.collect { ui ->
                _state.value = LifetimeStatsState(stats = ui, isLoading = false)
            }
        }
    }

    fun onAction(action: LifetimeStatsAction) {
        when (action) {
            LifetimeStatsAction.OnBackClick -> {
                viewModelScope.launch {
                    _events.send(LifetimeStatsEvent.NavigateBack)
                }
            }
        }
    }
}
