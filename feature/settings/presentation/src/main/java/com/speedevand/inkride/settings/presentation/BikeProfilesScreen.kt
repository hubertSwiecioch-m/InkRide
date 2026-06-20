package com.speedevand.inkride.settings.presentation

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.presentation.ObserveAsEvents
import org.koin.androidx.compose.koinViewModel

@Composable
fun BikeProfilesRoot(
    onNavigateBack: () -> Unit,
    viewModel: BikeProfilesViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            BikeProfilesEvent.OnBack -> onNavigateBack()
            is BikeProfilesEvent.ShowError ->
                Toast.makeText(context, event.message.asString(context), Toast.LENGTH_LONG).show()
        }
    }

    BikeProfilesScreen(state = state, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BikeProfilesScreen(
    state: BikeProfilesState,
    onAction: (BikeProfilesAction) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = stringResource(R.string.bike_profiles_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(BikeProfilesAction.OnBackClick) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.bike_profiles_cd_back)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.profiles.isEmpty()) {
                TextMMD(
                    text = stringResource(R.string.bike_profiles_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            state.profiles.forEach { profile ->
                ProfileRow(profile = profile, weightUnit = state.weightUnit, onAction = onAction)
                HorizontalDividerMMD()
            }

            if (state.isEditing) {
                ProfileEditor(state = state, onAction = onAction)
            } else {
                ButtonMMD(onClick = { onAction(BikeProfilesAction.OnAddNew) }) {
                    TextMMD(text = stringResource(R.string.bike_profiles_add))
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: BikeProfileUi,
    weightUnit: String,
    onAction: (BikeProfilesAction) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAction(BikeProfilesAction.OnSetActive(profile.id)) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButtonMMD(
            selected = profile.isActive,
            onClick = { onAction(BikeProfilesAction.OnSetActive(profile.id)) }
        )
        Column(modifier = Modifier.weight(1f)) {
            TextMMD(
                text = profile.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            TextMMD(
                text = "${profile.weight} $weightUnit · ${stringResource(profile.type.labelRes())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        OutlinedButtonMMD(onClick = { onAction(BikeProfilesAction.OnEdit(profile.id)) }) {
            TextMMD(text = stringResource(R.string.bike_profiles_edit))
        }
        OutlinedButtonMMD(onClick = { onAction(BikeProfilesAction.OnDelete(profile.id)) }) {
            TextMMD(text = stringResource(R.string.bike_profiles_delete))
        }
    }
}

@Composable
private fun ProfileEditor(
    state: BikeProfilesState,
    onAction: (BikeProfilesAction) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextMMD(
            text = stringResource(
                if (state.editingId == null) R.string.bike_profiles_new else R.string.bike_profiles_editing
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        TextFieldMMD(
            value = state.draftName,
            onValueChange = { onAction(BikeProfilesAction.OnNameChange(it)) },
            label = { TextMMD(stringResource(R.string.bike_profiles_name)) },
            isError = state.draftNameError,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        TextFieldMMD(
            value = state.draftWeight,
            onValueChange = { onAction(BikeProfilesAction.OnWeightChange(it)) },
            label = { TextMMD(stringResource(R.string.bike_profiles_weight)) },
            suffix = { TextMMD(text = state.weightUnit, modifier = Modifier.padding(end = 4.dp)) },
            isError = state.draftWeightError,
            modifier = Modifier.fillMaxWidth(0.5f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true
        )

        BikeType.entries.forEach { type ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAction(BikeProfilesAction.OnTypeChange(type)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextMMD(text = stringResource(type.labelRes()), style = MaterialTheme.typography.bodyLarge)
                RadioButtonMMD(
                    selected = state.draftType == type,
                    onClick = { onAction(BikeProfilesAction.OnTypeChange(type)) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonMMD(onClick = { onAction(BikeProfilesAction.OnSaveDraft) }) {
                TextMMD(text = stringResource(R.string.bike_profiles_save))
            }
            OutlinedButtonMMD(onClick = { onAction(BikeProfilesAction.OnCancelDraft) }) {
                TextMMD(text = stringResource(R.string.bike_profiles_cancel))
            }
        }
    }
}

private fun BikeType.labelRes(): Int = when (this) {
    BikeType.ROAD -> R.string.settings_bike_type_road
    BikeType.MTB -> R.string.settings_bike_type_mtb
    BikeType.CITY -> R.string.settings_bike_type_city
}
