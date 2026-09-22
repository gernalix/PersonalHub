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
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.HubFacetChips
import com.gernalix.personalhub.core.hubcontext.HubFacetPickerDialog

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
    var selectedFacets by mutableStateOf<List<HubEntitySummary>>(emptyList())

    suspend fun initialize(sessionId: Long) {
        if (!selectionInitialized) {
            val selection = HubContextRuntime.timerSelection(sessionId)
            peopleIds = selection.first
            placeId = selection.second
            selectionInitialized = true
        }
        selectedFacets = HubContextRuntime.timerFacets(sessionId)
    }

    fun select(values: List<HubEntitySummary>) {
        val onePlace = values.filter { it.ref.moduleId == "places" }.takeLast(1)
        selectedFacets = (values.filterNot { it.ref.moduleId == "places" } + onePlace).distinctBy { it.ref }
        peopleIds = selectedFacets.filter { it.ref.moduleId == "people" }.mapTo(linkedSetOf()) { it.ref.canonicalId }
        placeId = selectedFacets.firstOrNull { it.ref.moduleId == "places" }?.ref?.canonicalId
    }

    suspend fun save(sessionId: Long) = HubContextRuntime.saveTimerLinks(
        sessionId, peopleIds, placeId, selectedFacets.map(HubEntitySummary::ref),
    )

    companion object {
        val Saver = Saver<SessionContextEditorState, List<Any?>>(
            save = { listOf(it.peopleIds.toList(), it.placeId, it.personDraft, it.placeDraft) },
            restore = {
                @Suppress("UNCHECKED_CAST")
                SessionContextEditorState((it[0] as List<String>).toSet(), it[1] as String?, it[2] as String, it[3] as String, selectionInitialized = false)
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
    var pickerOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.hub_context_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            HubFacetChips(state.selectedFacets, onClick = { if (!readOnly) pickerOpen = true })
            OutlinedButton(onClick = { pickerOpen = true }, enabled = !readOnly) {
                Text("People, places, substances…")
            }
        }
    }
    if (pickerOpen) HubFacetPickerDialog(
        namespace = HubTagNamespaces.TIMER_NOW,
        selected = state.selectedFacets,
        onSelectedChange = state::select,
        onDismiss = { pickerOpen = false },
        allowedModules = setOf("people", "places", "substances"),
        allowTags = false,
    )
}
