package com.gernalix.personalhub.core.hubcontext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubTagKinds
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One-field typed picker shared by feature modules for optional context facets and tags. */
@Composable
fun HubFacetPickerDialog(
    namespace: String,
    selected: List<HubEntitySummary>,
    onSelectedChange: (List<HubEntitySummary>) -> Unit,
    onDismiss: () -> Unit,
    allowedModules: Set<String> = emptySet(),
    allowTags: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<HubFacetSuggestion>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        results = HubContextRuntime.searchFacets(query, namespace, 50)
            .filter { (allowTags || !it.isTag) && (allowedModules.isEmpty() || it.isTag || it.summary.ref.moduleId in allowedModules) }
    }
    LaunchedEffect(query) {
        delay(150)
        runCatching { refresh() }.onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Context") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    selected.forEach { summary ->
                        AssistChip(
                            onClick = { onSelectedChange(selected - summary) },
                            label = { Text("${summary.attributes["icon"] ?: pickerIcon(summary.ref)} ${summary.label} ×") },
                        )
                    }
                }
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Search people, places, substances, tags…") },
                    singleLine = true,
                )
                error?.let { Text(it) }
                LazyColumn {
                    items(results.filter { candidate -> selected.none { it.ref == candidate.summary.ref } }, key = { it.summary.ref.toString() }) { candidate ->
                        TextButton(
                            onClick = { onSelectedChange(selected + candidate.summary) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("${candidate.icon} ${candidate.summary.label} · ${candidate.typeLabel}") }
                    }
                    if (allowTags && query.startsWith("#") && query.removePrefix("#").isNotBlank()) {
                        item {
                            TextButton(onClick = {
                                scope.launch {
                                    val created = HubContextRuntime.tags().create(
                                        namespace,
                                        query.removePrefix("#"),
                                        kind = if (namespace == HubTagNamespaces.SOLDI_CATEGORY) HubTagKinds.CATEGORY else HubTagKinds.FREE,
                                    ).let { it.tag ?: it.exactDuplicate }
                                    if (created != null) {
                                        onSelectedChange(selected + HubEntitySummary(
                                            HubEntityRef("tags", "tag", created.id), created.name, created.namespace,
                                            attributes = mapOf("icon" to (created.icon ?: "🏷"), "namespace" to created.namespace),
                                        ))
                                        query = ""
                                    }
                                }
                            }) { Text("🏷 Create ${query.removePrefix("#").trim()}") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun pickerIcon(ref: HubEntityRef) = when (ref.moduleId) {
    "people" -> "👤"
    "places" -> "📍"
    "substances" -> "💊"
    "tags" -> "🏷"
    else -> "•"
}

@Composable
fun HubFacetChips(
    values: List<HubEntitySummary>,
    modifier: Modifier = Modifier,
    maxVisible: Int = 3,
    onClick: (() -> Unit)? = null,
) {
    val visible = values.take(maxVisible.coerceAtLeast(0))
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.forEach { summary ->
            AssistChip(onClick = { onClick?.invoke() }, label = { Text("${summary.attributes["icon"] ?: pickerIcon(summary.ref)} ${summary.label}") })
        }
        val overflow = values.size - visible.size
        if (overflow > 0) AssistChip(onClick = { onClick?.invoke() }, label = { Text("+$overflow") })
    }
}
