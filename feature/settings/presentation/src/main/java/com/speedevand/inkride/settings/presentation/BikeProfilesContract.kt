package com.speedevand.inkride.settings.presentation

import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.presentation.UiText

/** A single profile row as shown in the list. */
data class BikeProfileUi(
    val id: Long,
    val name: String,
    val weight: String,
    val type: BikeType,
    val isActive: Boolean
)

sealed interface BikeProfilesAction {
    data class OnNameChange(val value: String) : BikeProfilesAction
    data class OnWeightChange(val value: String) : BikeProfilesAction
    data class OnTypeChange(val type: BikeType) : BikeProfilesAction
    data object OnSaveDraft : BikeProfilesAction
    data object OnCancelDraft : BikeProfilesAction
    data object OnAddNew : BikeProfilesAction
    data class OnEdit(val id: Long) : BikeProfilesAction
    data class OnDelete(val id: Long) : BikeProfilesAction
    data class OnSetActive(val id: Long) : BikeProfilesAction
    data object OnBackClick : BikeProfilesAction
}

sealed interface BikeProfilesEvent {
    data class ShowError(val message: UiText) : BikeProfilesEvent
    data object OnBack : BikeProfilesEvent
}

data class BikeProfilesState(
    val profiles: List<BikeProfileUi> = emptyList(),
    val units: MeasurementUnits = MeasurementUnits.METRIC,
    // Add/edit form. [isEditing] is true whenever the form is open; [editingId]
    // is null for a brand-new profile, set when editing an existing one.
    val isEditing: Boolean = false,
    val editingId: Long? = null,
    val draftName: String = "",
    val draftWeight: String = "",
    val draftType: BikeType = BikeType.ROAD,
    val draftNameError: Boolean = false,
    val draftWeightError: Boolean = false
) {
    val weightUnit: String get() = if (units == MeasurementUnits.IMPERIAL) "lbs" else "kg"
}
