package com.gernalix.luoghi.ui.place

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.ADDRESS_FIELD_TAG
import com.gernalix.luoghi.ADDRESS_SUGGESTIONS_TAG
import com.gernalix.luoghi.AddressAutocompleteState
import com.gernalix.luoghi.AddressMessage
import com.gernalix.personalhub.core.ui.HubSearchField
import com.gernalix.luoghi.PlaceLocationSource
import com.gernalix.luoghi.PlaceFormState
import com.gernalix.luoghi.R
import com.gernalix.luoghi.capsules.addressautocomplete.AddressSuggestion
import androidx.compose.ui.platform.testTag
import com.gernalix.personalhub.core.ui.photo.rememberHubPhotoPicker
import com.gernalix.personalhub.contracts.database.SinceWhenTimestampSource
import com.gernalix.personalhub.core.ui.SinceWhenCreationControl

@Composable
fun PlaceEditorDialog(
    form: PlaceFormState,
    addressState: AddressAutocompleteState,
    onAddressChange: (String) -> Unit,
    onAddressSuggestion: (AddressSuggestion) -> Unit,
    onNicknameChange: (String) -> Unit,
    onRadiusChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onPhotoChange: (String) -> Unit = {},
    onSave: (createSinceWhen: Boolean, selectedSourceId: String?) -> Unit,
    onDismiss: () -> Unit,
    onTagsChange: (String) -> Unit = {},
    onUseCurrentLocation: () -> Unit = {},
    onUseAddressLocation: () -> Unit = {},
) {
    val pickPhoto = rememberHubPhotoPicker { onPhotoChange(it.reference) }
    val addedAt = remember(form.uuid) { System.currentTimeMillis() }
    var createSinceWhen by remember(form.uuid) { mutableStateOf(false) }
    var saving by remember(form.uuid) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (form.uuid == null) stringResource(R.string.new_place) else stringResource(R.string.edit_place))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (form.uuid == null) {
                    TextButton(onClick = onUseCurrentLocation, enabled = !addressState.loading) {
                        Text(stringResource(R.string.place_use_current_location))
                    }
                    TextButton(onClick = onUseAddressLocation, enabled = !addressState.loading) {
                        Text(stringResource(R.string.place_use_address_location))
                    }
                }
                Text(stringResource(when (form.locationSource) {
                    PlaceLocationSource.SAVED -> R.string.place_saved_location_preserved
                    PlaceLocationSource.CURRENT_LOCATION -> R.string.place_current_location_selected
                    PlaceLocationSource.ADDRESS -> R.string.place_address_location_help
                }), style = MaterialTheme.typography.bodySmall)
                PlaceEditor(
                    form = form,
                    addressState = addressState,
                    onAddressChange = onAddressChange,
                    onAddressSuggestion = onAddressSuggestion,
                    onNicknameChange = onNicknameChange,
                    onRadiusChange = onRadiusChange,
                    onNotesChange = onNotesChange,
                    onPickPhoto = pickPhoto,
                    onRemovePhoto = { onPhotoChange("") },
                    onTagsChange = onTagsChange,
                )
                if (form.uuid == null) {
                    SinceWhenCreationControl(
                        timestampSources = listOf(SinceWhenTimestampSource(
                            "added_at",
                            stringResource(R.string.since_when_added_to_ph),
                            addedAt,
                            true,
                        )),
                        enabled = createSinceWhen,
                        selectedSourceId = "added_at",
                        saving = saving,
                        onEnabledChange = { createSinceWhen = it },
                        onSourceSelected = {},
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = form.nickname.isNotBlank() && !saving && !addressState.loading, onClick = {
                saving = true
                onSave(createSinceWhen, "added_at")
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun PlaceEditor(
    form: PlaceFormState,
    addressState: AddressAutocompleteState,
    onAddressChange: (String) -> Unit,
    onAddressSuggestion: (AddressSuggestion) -> Unit,
    onNicknameChange: (String) -> Unit,
    onRadiusChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onTagsChange: (String) -> Unit = {},
) {
    Column {
        HubSearchField(
            value = form.nickname,
            onValueChange = onNicknameChange,
            label = stringResource(R.string.nickname),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        AddressField(
            form = form,
            addressState = addressState,
            onAddressChange = onAddressChange,
            onAddressSuggestion = onAddressSuggestion,
        )
        HubSearchField(
            value = form.radiusM,
            onValueChange = onRadiusChange,
            label = stringResource(R.string.radius_m),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        HubSearchField(
            value = form.notes,
            onValueChange = onNotesChange,
            label = stringResource(R.string.notes),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onPickPhoto) {
            Text(stringResource(if (form.photoUri.isBlank()) R.string.place_photo_add else R.string.place_photo_replace))
        }
        if (form.photoUri.isNotBlank()) {
            TextButton(onClick = onRemovePhoto) { Text(stringResource(R.string.place_photo_remove)) }
        }
        HubSearchField(
            value = form.tagsText,
            onValueChange = onTagsChange,
            label = stringResource(R.string.place_tags),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.place_tags_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AddressField(
    form: PlaceFormState,
    addressState: AddressAutocompleteState,
    onAddressChange: (String) -> Unit,
    onAddressSuggestion: (AddressSuggestion) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HubSearchField(
            value = form.address,
            onValueChange = onAddressChange,
            label = stringResource(R.string.address),
            enabled = addressState.message != AddressMessage.LOCATION_LOADING,
            modifier = Modifier.fillMaxWidth().testTag(ADDRESS_FIELD_TAG),
        )
        when {
            addressState.message != null -> Text(addressMessageText(addressState.message), style = MaterialTheme.typography.bodySmall)
            addressState.loading -> Text(stringResource(R.string.address_suggestions_loading), style = MaterialTheme.typography.bodySmall)
        }
        if (!addressState.loading) AddressSuggestionList(addressState.suggestions, onAddressSuggestion)
    }
}

@Composable
private fun addressMessageText(message: AddressMessage): String = when (message) {
    AddressMessage.CONFIG_MISSING -> stringResource(R.string.address_suggestions_config_missing)
    AddressMessage.SEARCH_TIMEOUT -> stringResource(R.string.address_suggestions_timeout)
    AddressMessage.SEARCH_FAILED -> stringResource(R.string.address_suggestions_failed)
    AddressMessage.RESOLUTION_TIMEOUT -> stringResource(R.string.address_resolution_timeout)
    AddressMessage.RESOLUTION_FAILED -> stringResource(R.string.address_resolution_failed)
    AddressMessage.LOCATION_LOADING -> stringResource(R.string.place_location_loading)
    AddressMessage.LOCATION_PERMISSION_MISSING -> stringResource(R.string.place_location_permission_missing)
    AddressMessage.LOCATION_UNAVAILABLE -> stringResource(R.string.place_location_unavailable)
}

@Composable
private fun AddressSuggestionList(
    suggestions: List<AddressSuggestion>,
    onAddressSuggestion: (AddressSuggestion) -> Unit,
) {
    if (suggestions.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .testTag(ADDRESS_SUGGESTIONS_TAG),
    ) {
        suggestions.forEach { suggestion ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddressSuggestion(suggestion) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(suggestion.primaryText, fontWeight = FontWeight.SemiBold)
                if (suggestion.secondaryText.isNotBlank()) Text(suggestion.secondaryText)
            }
        }
    }
}
