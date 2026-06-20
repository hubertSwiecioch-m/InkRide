package com.speedevand.inkride.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.onFailure
import com.speedevand.inkride.core.domain.onSuccess
import com.speedevand.inkride.core.domain.settings.BikeProfile
import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.presentation.toUiText
import com.speedevand.inkride.settings.presentation.SettingsConstants.WEIGHT_FACTOR_LBS
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class BikeProfilesViewModel(
    private val bikeProfileRepository: BikeProfileRepository,
    private val userSettingsRepository: UserSettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BikeProfilesState())
    val state = _state.asStateFlow()

    private val _events = Channel<BikeProfilesEvent>()
    val events = _events.receiveAsFlow()

    // Latest settings snapshot so set-active / delete can edit the active id
    // without re-querying. Bike weight/type on this object are the *resolved*
    // values; only [UserSettings.activeBikeProfileId] is written back.
    private var currentSettings: UserSettings = UserSettings(weightKg = 75, age = 30)

    init {
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            combine(
                bikeProfileRepository.observeProfiles(),
                userSettingsRepository.observeSettings()
            ) { profiles, settings ->
                currentSettings = settings
                val factor = if (settings.units == MeasurementUnits.IMPERIAL) WEIGHT_FACTOR_LBS else 1.0
                profiles.map { profile ->
                    BikeProfileUi(
                        id = profile.id,
                        name = profile.name,
                        weight = String.format(Locale.ROOT, "%.1f", profile.weightKg * factor),
                        type = profile.type,
                        isActive = profile.id == settings.activeBikeProfileId
                    )
                } to settings.units
            }.collect { (profiles, units) ->
                _state.update { it.copy(profiles = profiles, units = units) }
            }
        }
    }

    fun onAction(action: BikeProfilesAction) {
        when (action) {
            is BikeProfilesAction.OnNameChange ->
                _state.update { it.copy(draftName = action.value, draftNameError = false) }
            is BikeProfilesAction.OnWeightChange ->
                _state.update {
                    it.copy(
                        draftWeight = action.value.filter { c -> c.isDigit() || c == '.' },
                        draftWeightError = false
                    )
                }
            is BikeProfilesAction.OnTypeChange ->
                _state.update { it.copy(draftType = action.type) }
            BikeProfilesAction.OnAddNew ->
                _state.update {
                    it.copy(
                        isEditing = true,
                        editingId = null,
                        draftName = "",
                        draftWeight = "",
                        draftType = BikeType.ROAD,
                        draftNameError = false,
                        draftWeightError = false
                    )
                }
            is BikeProfilesAction.OnEdit -> {
                val profile = _state.value.profiles.firstOrNull { it.id == action.id } ?: return
                _state.update {
                    it.copy(
                        isEditing = true,
                        editingId = profile.id,
                        draftName = profile.name,
                        draftWeight = profile.weight,
                        draftType = profile.type,
                        draftNameError = false,
                        draftWeightError = false
                    )
                }
            }
            BikeProfilesAction.OnCancelDraft -> clearDraft()
            BikeProfilesAction.OnSaveDraft -> saveDraft()
            is BikeProfilesAction.OnDelete -> deleteProfile(action.id)
            is BikeProfilesAction.OnSetActive -> setActive(action.id)
            BikeProfilesAction.OnBackClick -> viewModelScope.launch {
                _events.send(BikeProfilesEvent.OnBack)
            }
        }
    }

    private fun saveDraft() {
        val current = _state.value
        val nameValid = current.draftName.isNotBlank()
        val weightValue = current.draftWeight.toDoubleOrNull()
        val weightValid = weightValue != null && weightValue > 0.0
        if (!nameValid || !weightValid) {
            _state.update { it.copy(draftNameError = !nameValid, draftWeightError = !weightValid) }
            return
        }

        val factor = if (current.units == MeasurementUnits.IMPERIAL) WEIGHT_FACTOR_LBS else 1.0
        val weightKg = weightValue!! / factor
        val noActiveBefore = currentSettings.activeBikeProfileId == null
        val profile = BikeProfile(
            id = current.editingId ?: 0L,
            name = current.draftName.trim(),
            weightKg = weightKg,
            type = current.draftType
        )

        viewModelScope.launch {
            bikeProfileRepository.upsert(profile)
                .onSuccess { newId ->
                    // First profile (or none active yet) becomes the active one.
                    if (current.editingId == null && noActiveBefore) {
                        persistActiveId(newId)
                    }
                    clearDraft()
                }
                .onFailure { error -> _events.send(BikeProfilesEvent.ShowError(error.toUiText())) }
        }
    }

    private fun deleteProfile(id: Long) {
        viewModelScope.launch {
            bikeProfileRepository.delete(id)
                .onSuccess {
                    // If the deleted profile was active, fall back to another
                    // remaining profile (or none → flat defaults).
                    if (currentSettings.activeBikeProfileId == id) {
                        val next = _state.value.profiles.firstOrNull { it.id != id }?.id
                        persistActiveId(next)
                    }
                }
                .onFailure { error -> _events.send(BikeProfilesEvent.ShowError(error.toUiText())) }
        }
    }

    private fun setActive(id: Long) {
        viewModelScope.launch { persistActiveId(id) }
    }

    private suspend fun persistActiveId(id: Long?) {
        userSettingsRepository.save(currentSettings.copy(activeBikeProfileId = id))
            .onFailure { error -> _events.send(BikeProfilesEvent.ShowError(error.toUiText())) }
    }

    private fun clearDraft() {
        _state.update {
            it.copy(
                isEditing = false,
                editingId = null,
                draftName = "",
                draftWeight = "",
                draftType = BikeType.ROAD,
                draftNameError = false,
                draftWeightError = false
            )
        }
    }
}
