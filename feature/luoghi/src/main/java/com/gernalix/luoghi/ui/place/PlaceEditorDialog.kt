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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.ADDRESS_FIELD_TAG
import com.gernalix.luoghi.ADDRESS_SUGGESTIONS_TAG
import com.gernalix.luoghi.AddressAutocompleteState
import com.gernalix.luoghi.AddressMessage
import com.gernalix.luoghi.PlaceFormState
import com.gernalix.luoghi.R
import com.gernalix.luoghi.capsules.addressautocomplete.AddressSuggestion
import androidx.compose.ui.platform.testTag

@Composable
fun PlaceEditorDialog(
    form: PlaceFormState,
    addressState: AddressAutocompleteState,
    onAddressChange: (String) -> Unit,
    onAddressSuggestion: (AddressSuggestion) -> Unit,
    onNicknameChange: (String) -> Unit,
    onRadiusChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                PlaceEditor(
                    form = form,
                    addressState = addressState,
                    onAddressChange = onAddressChange,
                    onAddressSuggestion = onAddressSuggestion,
                    onNicknameChange = onNicknameChange,
                    onRadiusChange = onRadiusChange,
                    onNotesChange = onNotesChange,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = form.nickname.isNotBlank(), onClick = onSave) {
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
) {
    Column {
        OutlinedTextField(
            value = form.nickname,
            onValueChange = onNicknameChange,
            label = { Text(stringResource(R.string.nickname)) },
            modifier = Modifier.fillMaxWidth(),
        )
        AddressField(
            form = form,
            addressState = addressState,
            onAddressChange = onAddressChange,
            onAddressSuggestion = onAddressSuggestion,
        )
        OutlinedTextField(
            value = form.radiusM,
            onValueChange = onRadiusChange,
            label = { Text(stringResource(R.string.radius_m)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.notes,
            onValueChange = onNotesChange,
            label = { Text(stringResource(R.string.notes)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun AddressField(
    form: PlaceFormState,
    addressState: AddressAutocompleteState,
    onAddressChange: (String) -> Unit,
    onAddressSuggestion: (AddressSuggestion) -> Unit,
) {
    var localAddress by rememberSaveable(form.uuid, form.lat, form.lon, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(value = TextFieldValue(form.address))
    }
    var isFocused by remember { mutableStateOf(value = false) }
    LaunchedEffect(form.uuid, form.address, isFocused, addressState.suggestions.isEmpty()) {
        if (((!isFocused) || addressState.suggestions.isEmpty()) && (localAddress.text != form.address)) {
            localAddress = TextFieldValue(form.address, selection = TextRange(form.address.length))
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = localAddress,
            onValueChange = { value ->
                localAddress = value
                if (value.text != form.address) onAddressChange(value.text)
            },
            label = { Text(stringResource(R.string.address)) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .testTag(ADDRESS_FIELD_TAG),
            supportingText = {
                when {
                    addressState.message != null -> Text(addressMessageText(addressState.message))
                    addressState.loading -> Text(stringResource(R.string.address_suggestions_loading))
                }
            },
        )
        AddressSuggestionList(addressState.suggestions, onAddressSuggestion)
    }
}

@Composable
private fun addressMessageText(message: AddressMessage): String = when (message) {
    AddressMessage.CONFIG_MISSING -> stringResource(R.string.address_suggestions_config_missing)
    AddressMessage.SEARCH_TIMEOUT -> stringResource(R.string.address_suggestions_timeout)
    AddressMessage.SEARCH_FAILED -> stringResource(R.string.address_suggestions_failed)
    AddressMessage.RESOLUTION_TIMEOUT -> stringResource(R.string.address_resolution_timeout)
    AddressMessage.RESOLUTION_FAILED -> stringResource(R.string.address_resolution_failed)
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
