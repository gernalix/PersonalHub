package com.example.multitimetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.gernalix.personalhub.contracts.database.HubCreateRequest
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import kotlinx.coroutines.launch

class SessionContextEditorState internal constructor(
    people: Set<String>,
    place: String?,
    personDraft: String,
    placeDraft: String,
    private var selectionInitialized: Boolean = false,
) {
    var peopleIds by mutableStateOf(people)
    var placeId by mutableStateOf(place)
    var personDraft by mutableStateOf(personDraft)
    var placeDraft by mutableStateOf(placeDraft)
    var peopleOptions by mutableStateOf<List<HubEntitySummary>>(emptyList())
    var placeOptions by mutableStateOf<List<HubEntitySummary>>(emptyList())

    suspend fun initialize(sessionId: Long) {
        if (!selectionInitialized) {
            val selection = HubContextRuntime.timerSelection(sessionId)
            peopleIds = selection.first
            placeId = selection.second
            selectionInitialized = true
        }
        refreshOptions()
    }

    suspend fun refreshOptions() {
        peopleOptions = HubContextRuntime.adapter("people", "person").search("", 8)
        placeOptions = HubContextRuntime.adapter("places", "place").search("", 8)
    }

    suspend fun createPerson() {
        HubContextRuntime.adapter("people", "person").create(HubCreateRequest(personDraft))?.let {
            peopleIds = peopleIds + it.ref.canonicalId
            personDraft = ""
            refreshOptions()
        }
    }

    suspend fun createPlace() {
        HubContextRuntime.adapter("places", "place").create(HubCreateRequest(placeDraft))?.let {
            placeId = it.ref.canonicalId
            placeDraft = ""
            refreshOptions()
        }
    }

    suspend fun save(sessionId: Long) = HubContextRuntime.saveTimerLinks(sessionId, peopleIds, placeId)

    companion object {
        val Saver = Saver<SessionContextEditorState, List<Any?>>(
            save = { listOf(it.peopleIds.toList(), it.placeId, it.personDraft, it.placeDraft) },
            restore = {
                @Suppress("UNCHECKED_CAST")
                SessionContextEditorState((it[0] as List<String>).toSet(), it[1] as String?, it[2] as String, it[3] as String, selectionInitialized = true)
            },
        )
    }
}

internal suspend fun saveContextForCreatedTimerSession(
    createdSession: com.example.multitimetracker.model.SessionUi,
    saveContext: suspend (Long) -> Unit,
) {
    require(createdSession.id > 0L) { "Timer session must be persisted before saving Context" }
    saveContext(createdSession.id)
}

@Composable
fun rememberSessionContextEditorState(sessionId: Long): SessionContextEditorState {
    val state = rememberSaveable(sessionId, saver = SessionContextEditorState.Saver) { SessionContextEditorState(emptySet(), null, "", "") }
    LaunchedEffect(sessionId) { state.initialize(sessionId) }
    return state
}

@Composable
fun SessionContextEditor(state: SessionContextEditorState, readOnly: Boolean) {
    val scope = rememberCoroutineScope()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.hub_context_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.hub_context_people), style = MaterialTheme.typography.labelMedium)
            state.peopleOptions.forEach { option ->
                FilterChip(
                    selected = option.ref.canonicalId in state.peopleIds,
                    onClick = { state.peopleIds = if (option.ref.canonicalId in state.peopleIds) state.peopleIds - option.ref.canonicalId else state.peopleIds + option.ref.canonicalId },
                    label = { Text(option.label) },
                    enabled = !readOnly,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(state.personDraft, { state.personDraft = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.hub_context_new_person_name)) }, singleLine = true, enabled = !readOnly)
                Button(onClick = { scope.launch { state.createPerson() } }, enabled = !readOnly && state.personDraft.isNotBlank()) { Text(stringResource(R.string.hub_context_new_person)) }
            }
            Text(stringResource(R.string.hub_context_place), style = MaterialTheme.typography.labelMedium)
            state.placeOptions.forEach { option ->
                FilterChip(
                    selected = option.ref.canonicalId == state.placeId,
                    onClick = { state.placeId = if (state.placeId == option.ref.canonicalId) null else option.ref.canonicalId },
                    label = { Text(option.label) },
                    enabled = !readOnly,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(state.placeDraft, { state.placeDraft = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.hub_context_new_place_name)) }, singleLine = true, enabled = !readOnly)
                Button(onClick = { scope.launch { state.createPlace() } }, enabled = !readOnly && state.placeDraft.isNotBlank()) { Text(stringResource(R.string.hub_context_new_place)) }
            }
            Text(stringResource(R.string.hub_context_place_radius_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
