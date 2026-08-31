// v356
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.multitimetracker.capsules.chains.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.chains.controller.ChainsCapsuleViewModel
import com.example.multitimetracker.model.ActiveChainRun
import com.example.multitimetracker.model.TaskChain
import com.example.multitimetracker.model.TaskChainStep
import com.example.multitimetracker.ui.components.AppTopBar
import com.example.multitimetracker.ui.components.ScreenEmptyStateCard
import com.example.multitimetracker.ui.components.ScreenHelpAction

@Composable
fun ChainsCapsuleUi(
    vm: ChainsCapsuleViewModel,
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    val activeRun: ActiveChainRun? = state.activeChainRun

    // stringResource() is @Composable, so compute any default labels here (in composable scope)
    // and pass plain Strings inside non-composable callbacks.
    val defaultChainName = stringResource(R.string.nuova_catena)

    val activeChains = remember(state.chains) { state.chains.filter { !it.isDeleted } }
    val deletedChains = remember(state.chains) { state.chains.filter { it.isDeleted } }

    var showAdd by remember { mutableStateOf(false) }
    var editingChain by remember { mutableStateOf<TaskChain?>(null) }

    if (showAdd) {
        ChainEditorDialog(
            title = stringResource(R.string.chain_add_title),
            allTags = state.tags.filter { !it.isDeleted && !it.isArchived },
            initialName = "",
            initialSteps = emptyList(),
            onSave = { name, steps ->
                val finalName = name.trim().ifBlank { defaultChainName }
                vm.addChain(finalName, steps)
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }

    val chainToEdit = editingChain
    if (chainToEdit != null) {
        ChainEditorDialog(
            title = stringResource(R.string.chain_edit_title),
            allTags = state.tags.filter { !it.isDeleted && !it.isArchived },
            initialName = chainToEdit.name,
            initialSteps = chainToEdit.steps,
            onSave = { name, steps ->
                vm.updateChain(chainToEdit.id, name, steps)
                editingChain = null
            },
            onDismiss = { editingChain = null }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.catene),
                actions = {
                    ScreenHelpAction(
                        title = stringResource(R.string.chains_intro_title),
                        body = stringResource(R.string.chains_intro_body)
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.aggiungi))
            }
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
            if (activeChains.isEmpty()) {
                item(key = "empty") {
                    ScreenEmptyStateCard(
                        title = stringResource(R.string.chains_empty_title),
                        body = stringResource(R.string.chains_empty_body)
                    )
                }
            } else {
                items(activeChains, key = { it.id }) { chain ->
                    ChainCard(
                        chain = chain,
                        isRunning = activeRun?.chainId == chain.id,
                        onStart = { vm.startChain(chain.id) },
                        onStop = { vm.stopChainRun() },
                        onEdit = { editingChain = chain },
                        onDelete = { vm.deleteChain(chain.id) }
                    )
                }
            }

            if (deletedChains.isNotEmpty()) {
                item(key = "trash_title") {
                    Text(
                        text = stringResource(R.string.chain_trash_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                }
                items(deletedChains, key = { "del:${it.id}" }) { chain ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(chain.name.ifBlank { "#${chain.id}" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = stringResource(R.string.chain_steps_count, chain.steps.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                IconButton(onClick = { vm.restoreChain(chain.id) }) {
                                    Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.ripristina))
                                }
                                IconButton(onClick = { vm.purgeChain(chain.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.elimina))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChainCard(
    chain: TaskChain,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = chain.name.ifBlank { "#${chain.id}" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.chain_steps_count, chain.steps.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (chain.steps.isNotEmpty()) {
                val preview = chain.steps.take(3).mapIndexed { idx, s ->
                    stringResource(R.string.chain_step_line, idx + 1, s.name.ifBlank { "-" })
                }
                for (line in preview) {
                    Text(text = line, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (isRunning) {
                    FilledTonalButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text(stringResource(R.string.stop_action))
                    }
                } else {
                    FilledTonalButton(onClick = onStart) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text(stringResource(R.string.avvia))
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.chain_edit_title))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.elimina))
                }
            }
        }
    }
}

@Composable
private fun ChainEditorDialog(
    title: String,
    allTags: List<com.example.multitimetracker.model.Tag>,
    initialName: String,
    initialSteps: List<TaskChainStep>,
    onSave: (String, List<TaskChainStep>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var steps by remember { mutableStateOf(initialSteps) }
    var editingStepIndex by remember { mutableStateOf<Int?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val idx = editingStepIndex
    if (idx != null && idx in steps.indices) {
        StepEditorDialog(
            title = stringResource(R.string.chain_step_edit),
            allTags = allTags,
            step = steps[idx],
            onSave = { updated ->
                steps = steps.toMutableList().also { it[idx] = updated }
                editingStepIndex = null
            },
            onDismiss = { editingStepIndex = null }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.titolo)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.chain_steps_count, steps.size),
                    style = MaterialTheme.typography.titleSmall
                )

                if (steps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_chains_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    for (i in steps.indices) {
                        val s = steps[i]
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "${i + 1}. ${s.name.trim().ifBlank { stringResource(R.string.chain_step_title) }}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = stringResource(R.string.chain_step_tags_count, s.tagIds.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(onClick = { editingStepIndex = i }) {
                                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.chain_step_edit))
                                    }
                                    IconButton(onClick = {
                                        steps = steps.toMutableList().also { it.removeAt(i) }
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.elimina))
                                    }
                                }
                            }
                        }
                    }
                }

                TextButton(
                    onClick = { steps = steps + TaskChainStep() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.chain_new_step))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving,
                onClick = {
                    if (isSaving) return@TextButton
                    isSaving = true
                    onSave(name.trim(), steps)
                }
            ) {
                Text(stringResource(R.string.salva))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.annulla))
            }
        }
    )
}

@Composable
private fun StepEditorDialog(
    title: String,
    allTags: List<com.example.multitimetracker.model.Tag>,
    step: TaskChainStep,
    onSave: (TaskChainStep) -> Unit,
    onDismiss: () -> Unit
) {
    var stepTitle by remember { mutableStateOf(step.name) }
    var link by remember { mutableStateOf(step.link) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(step.tagIds) }

    val candidates = remember(allTags, query) {
        val q = query.trim()
        if (q.isBlank()) allTags else allTags.filter { it.name.contains(q, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = stepTitle,
                    onValueChange = { stepTitle = it },
                    label = { Text(stringResource(R.string.chain_step_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text(stringResource(R.string.chain_step_link)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(text = stringResource(R.string.chain_step_tags), style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.cerca_tag)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(candidates, key = { it.id }) { t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = t.name.ifBlank { "#${t.id}" },
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            androidx.compose.material3.Checkbox(
                                checked = selected.contains(t.id),
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + t.id else selected - t.id
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        TaskChainStep(
                            name = stepTitle.trim(),
                            link = link.trim(),
                            tagIds = selected
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.salva))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.annulla)) }
        }
    )
}
