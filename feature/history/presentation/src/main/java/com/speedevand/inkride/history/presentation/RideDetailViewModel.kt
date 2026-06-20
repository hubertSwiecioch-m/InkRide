package com.speedevand.inkride.history.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.onFailure
import com.speedevand.inkride.core.domain.onSuccess
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.presentation.UiText
import com.speedevand.inkride.core.presentation.toUiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RideDetailViewModel(
    private val rideId: Long,
    private val rideHistoryRepository: RideHistoryRepository,
    private val lapRepository: RideLapRepository,
    private val trackPointRepository: RideTrackPointRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val gpxExporter: GpxExporter,
) : ViewModel() {
    private val _state = MutableStateFlow(RideDetailState())
    val state = _state.asStateFlow()

    private val _events = Channel<RideDetailEvent>()
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            rideHistoryRepository
                .getById(rideId)
                .onSuccess { ride ->
                    var laps = emptyList<com.speedevand.inkride.core.domain.tracking.LapRecord>()
                    lapRepository
                        .getLaps(rideId)
                        .onSuccess { laps = it }
                        .onFailure { error -> _events.send(RideDetailEvent.ShowError(error.toUiText())) }
                    val trackPoints =
                        trackPointRepository
                            .getPoints(rideId)
                            .let { result ->
                                when (result) {
                                    is com.speedevand.inkride.core.domain.Result.Success -> {
                                        result.data.map { point -> TrackPointUi(point.latitude, point.longitude) }
                                    }

                                    is com.speedevand.inkride.core.domain.Result.Error -> {
                                        emptyList()
                                    }
                                }
                            }
                    userSettingsRepository.observeSettings().collect { settings ->
                        _state.update {
                            it.copy(
                                ride = ride.toDetailUi(settings.units),
                                laps = laps.map { lap -> lap.toLapUi(settings.units) },
                                trackPoints = trackPoints,
                                isLoading = false,
                            )
                        }
                    }
                }.onFailure { error ->
                    _state.update { it.copy(isLoading = false) }
                    _events.send(RideDetailEvent.ShowError(error.toUiText()))
                }
        }
    }

    fun onAction(action: RideDetailAction) {
        when (action) {
            RideDetailAction.OnDeleteClick -> {
                viewModelScope.launch {
                    rideHistoryRepository.deleteById(rideId)
                    _events.send(RideDetailEvent.NavigateBack)
                }
            }

            RideDetailAction.OnBackClick -> {
                viewModelScope.launch {
                    _events.send(RideDetailEvent.NavigateBack)
                }
            }

            RideDetailAction.OnExportGpxClick -> {
                viewModelScope.launch {
                    gpxExporter
                        .export(rideId)
                        .onSuccess { uri -> _events.send(RideDetailEvent.ShareGpx(uri)) }
                        .onFailure { error ->
                            val message =
                                when (error) {
                                    GpxExportError.NO_TRACK -> {
                                        UiText.StringResource(R.string.ride_detail_export_no_track)
                                    }

                                    GpxExportError.FAILED -> {
                                        UiText.StringResource(R.string.ride_detail_export_failed)
                                    }
                                }
                            _events.send(RideDetailEvent.ShowError(message))
                        }
                }
            }
        }
    }
}
