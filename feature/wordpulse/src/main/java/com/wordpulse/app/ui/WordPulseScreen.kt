package com.wordpulse.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gernalix.personalhub.contracts.database.DataExplorerContract
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.wordpulse.app.BuildConfig
import com.wordpulse.app.data.SessionSummaryRow
import com.wordpulse.app.domain.BaselineComparison
import com.wordpulse.app.domain.ConsonantSpineMetrics
import com.wordpulse.app.domain.ConsonantSpineSegment
import com.wordpulse.app.domain.ConsonantSpineShift
import com.wordpulse.app.domain.CorrectionMetrics
import com.wordpulse.app.domain.CaptureTextValue
import com.wordpulse.app.domain.CreativeEpisode
import com.wordpulse.app.domain.CreativeEpisodeMetrics
import com.wordpulse.app.domain.DashboardMetrics
import com.wordpulse.app.domain.EntropyMetrics
import com.wordpulse.app.domain.EvolutionMetrics
import com.wordpulse.app.domain.EvolutionTransition
import com.wordpulse.app.domain.FamilyMigrationMetrics
import com.wordpulse.app.domain.FamilyMigrationSegment
import com.wordpulse.app.domain.FamilyMigrationTransition
import com.wordpulse.app.domain.FragmentLifecycle
import com.wordpulse.app.domain.FamilyMetrics
import com.wordpulse.app.domain.FragmentMetrics
import com.wordpulse.app.domain.FragmentTrend
import com.wordpulse.app.domain.GenerativePhaseMetrics
import com.wordpulse.app.domain.GenerativePhaseSegment
import com.wordpulse.app.domain.Insight
import com.wordpulse.app.domain.InsightTone
import com.wordpulse.app.domain.LanguageEpoch
import com.wordpulse.app.domain.LanguageEpochMetrics
import com.wordpulse.app.domain.LanguageShift
import com.wordpulse.app.domain.LengthBucket
import com.wordpulse.app.domain.LatentWordFamily
import com.wordpulse.app.domain.MemoryEchoLink
import com.wordpulse.app.domain.MemoryEchoMetrics
import com.wordpulse.app.domain.MemoryEchoSegment
import com.wordpulse.app.domain.MotifAttractor
import com.wordpulse.app.domain.MotifAttractorMetrics
import com.wordpulse.app.domain.MotifAttractorSegment
import com.wordpulse.app.domain.MotifEcologyMetrics
import com.wordpulse.app.domain.MotifEcologyStory
import com.wordpulse.app.domain.MotifRoute
import com.wordpulse.app.domain.MotifRouteMetrics
import com.wordpulse.app.domain.MotifRouteSegment
import com.wordpulse.app.domain.MutationGrammarMetrics
import com.wordpulse.app.domain.MutationGrammarSegment
import com.wordpulse.app.domain.MutationOperatorSummary
import com.wordpulse.app.domain.MutationOperatorTransition
import com.wordpulse.app.domain.PhonotacticLane
import com.wordpulse.app.domain.PhonotacticMetrics
import com.wordpulse.app.domain.PhonotacticRule
import com.wordpulse.app.domain.PhonotacticSkeleton
import com.wordpulse.app.domain.PhonotacticTransition
import com.wordpulse.app.domain.RankedFragment
import com.wordpulse.app.domain.RhythmMetrics
import com.wordpulse.app.domain.SearchMode
import com.wordpulse.app.domain.SearchSort
import com.wordpulse.app.domain.SessionContrastDimension
import com.wordpulse.app.domain.SessionContrastMetrics
import com.wordpulse.app.domain.SessionFingerprintDimension
import com.wordpulse.app.domain.SessionStorylineMarker
import com.wordpulse.app.domain.SessionStorylineMetrics
import com.wordpulse.app.domain.SessionStorylineSegment
import com.wordpulse.app.domain.SessionStorylineSignal
import com.wordpulse.app.domain.SimilarityMetrics
import com.wordpulse.app.domain.SoundShapeDriftMetrics
import com.wordpulse.app.domain.SoundShapeSegment
import com.wordpulse.app.domain.SoundShapeShift
import com.wordpulse.app.domain.TypingAlert
import com.wordpulse.app.domain.TypingAlertAction
import com.wordpulse.app.domain.VocabularyMetrics
import com.wordpulse.app.domain.VowelPaletteMetrics
import com.wordpulse.app.domain.VowelPaletteSegment
import com.wordpulse.app.domain.VowelPaletteShift
import com.wordpulse.app.domain.WordDetail
import com.wordpulse.app.domain.WordConstellation
import com.wordpulse.app.domain.WordConstellationEdge
import com.wordpulse.app.domain.WordConstellationMetrics
import com.wordpulse.app.domain.WordGenealogyMetrics
import com.wordpulse.app.domain.WordLineage
import com.wordpulse.app.domain.WordMetrics
import com.wordpulse.app.domain.WordSearchResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class WordPulseTab(val label: String) {
    Today("Today"),
    Explore("Explore"),
    Lab("Lab"),
    Sessions("Sessions"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WordPulseScreen(
    uiState: WordPulseUiState,
    captureFieldValue: CaptureTextValue,
    typingAlert: TypingAlert?,
    events: SharedFlow<WordPulseEvent>,
    snackbarHostState: SnackbarHostState,
    onCaptureValueChanged: (CaptureTextValue) -> Unit,
    onSubmitCurrent: () -> Unit,
    onTypingAlertAction: (TypingAlertAction) -> Unit,
    onDismissTypingAlert: (Long) -> Unit,
    onClearInput: () -> Unit,
    onStartNewSession: () -> Unit,
    onDeleteAllData: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onSearchModeChanged: (SearchMode) -> Unit,
    onSearchSortChanged: (SearchSort) -> Unit,
    onSelectWord: (String) -> Unit,
    onClearSelectedWord: () -> Unit,
    initialSessionId: String? = null,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(WordPulseTab.Today) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var textFieldPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(initialSessionId) {
        if (initialSessionId != null) selectedTab = WordPulseTab.Sessions
    }
    fun restoreTypingFocus() {
        scope.launch {
            if (textFieldPlaced) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        }
    }

    LaunchedEffect(textFieldPlaced) {
        if (textFieldPlaced) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    DisposableEffect(lifecycleOwner, textFieldPlaced) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && textFieldPlaced) {
                restoreTypingFocus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(events) {
        events.collectLatest { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                withDismissAction = false,
                duration = if (event.actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                restoreTypingFocus()
            }
        }
    }

    Scaffold(snackbarHost = {}) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Header(
                        currentSessionId = uiState.currentSessionId,
                        onStartNewSession = {
                            onStartNewSession()
                            restoreTypingFocus()
                        },
                        onClearInput = {
                            onClearInput()
                            restoreTypingFocus()
                        },
                        onDataExplorer = { context.startActivity(DataExplorerContract.intent(context.packageName, "word_entries")) },
                        onHistory = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    HubDeepLinkContract.moduleHistoryUri("wordpulse"),
                                ).setPackage(context.packageName),
                            )
                        },
                        onDeleteAllData = { showDeleteConfirmation = true },
                    )

                    TypingAlertHost(
                        alert = typingAlert,
                        onAction = onTypingAlertAction,
                        onDismiss = onDismissTypingAlert,
                        restoreFocus = ::restoreTypingFocus,
                    )

                    CaptureInputField(
                        value = captureFieldValue,
                        focusRequester = focusRequester,
                        onValueChange = onCaptureValueChanged,
                        onSubmit = onSubmitCurrent,
                        onPlaced = { textFieldPlaced = true },
                        restoreFocus = ::restoreTypingFocus,
                    )

                    PrimaryScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 0.dp) {
                        WordPulseTab.entries.forEach { tab ->
                            Tab(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                text = { Text(tab.label) },
                            )
                        }
                    }

                    when (selectedTab) {
                        WordPulseTab.Today -> TodayTab(uiState.metrics)
                        WordPulseTab.Explore -> ExploreTab(
                            searchQuery = uiState.searchQuery,
                            searchMode = uiState.searchMode,
                            searchSort = uiState.searchSort,
                            results = uiState.searchResults,
                            onSearchChanged = onSearchChanged,
                            onSearchModeChanged = onSearchModeChanged,
                            onSearchSortChanged = onSearchSortChanged,
                            onSelectWord = onSelectWord,
                        )
                        WordPulseTab.Lab -> LabTab(uiState.metrics)
                        WordPulseTab.Sessions -> SessionsTab(
                            sessions = uiState.sessions,
                            currentSessionId = uiState.currentSessionId,
                            initialSessionId = initialSessionId,
                        )
                    }
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .testTag("snackbar-host"),
                )
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete all data") },
            text = { Text("This removes every stored word and session from this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteAllData()
                        restoreTypingFocus()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    uiState.selectedWordDetail?.let { detail ->
        WordDetailDialog(detail = detail, onDismiss = onClearSelectedWord)
    }
}

@Composable
private fun Header(
    currentSessionId: String?,
    onStartNewSession: () -> Unit,
    onClearInput: () -> Unit,
    onDataExplorer: () -> Unit,
    onHistory: () -> Unit,
    onDeleteAllData: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "WordPulse",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = currentSessionId?.shortSessionLabel() ?: "Session starting",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onStartNewSession, modifier = Modifier.testTag("new-session-button")) {
                    Icon(Icons.Filled.Add, contentDescription = "Start new session")
                }
                IconButton(onClick = onClearInput, modifier = Modifier.testTag("clear-input-button")) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear current input")
                }
                IconButton(onClick = onDataExplorer, modifier = Modifier.testTag("data-explorer-button")) {
                    Icon(Icons.Filled.Storage, contentDescription = "Datasette")
                }
                IconButton(onClick = onHistory, modifier = Modifier.testTag("history-search-button")) {
                    Icon(Icons.Filled.History, contentDescription = "History and search")
                }
                IconButton(onClick = onDeleteAllData, modifier = Modifier.testTag("delete-all-data-button")) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete all data")
                }
            }
        }
    }
}

@Composable
private fun TodayTab(metrics: WordMetrics) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        DashboardGrid(metrics.dashboard)
        InsightStrip(metrics.insights)

        Section("Session Shape") {
            FrequencyBars(
                data = metrics.wordStats.lengthDistribution,
                label = { it.length.toString() },
                value = { it.count },
                modifier = Modifier.testTag("word-length-chart"),
            )
            Sparkline(
                values = metrics.timeline.map { it.elapsedSincePreviousMs?.toDouble() ?: 0.0 }.takeLast(80),
                label = "Rhythm",
            )
        }

        Section("Signals") {
            Meter("Flow", metrics.dashboard.flowScore / 100.0)
            Meter("Novelty", metrics.dashboard.noveltyScore / 100.0)
            Meter("Repetition risk", metrics.dashboard.repetitionScore / 100.0)
            MetricLine("Average length", metrics.dashboard.averageWordLength.formatNumber())
            MetricLine("Time since last word", metrics.dashboard.timeSinceLastWordMs.formatDuration())
            MetricLine("Session duration", metrics.dashboard.currentSessionDurationMs.formatDuration())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardGrid(dashboard: DashboardMetrics) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardTile("Session", dashboard.currentSessionWords.toString(), "words", "current-session-words-value")
        DashboardTile("Today", dashboard.wordsToday.toString(), "words", "total-submitted-value")
        DashboardTile("Streak", dashboard.currentStreak.toString(), "new")
        DashboardTile("WPM", dashboard.wordsPerMinute.formatNumber(), "pace")
        DashboardTile("Flow", dashboard.flowScore.rounded(), "score")
        DashboardTile("Novelty", dashboard.noveltyScore.rounded(), "score")
        DashboardTile("Repetition", dashboard.repetitionScore.rounded(), "risk")
        DashboardTile("Avg length", dashboard.averageWordLength.formatNumber(), "chars")
    }
}

@Composable
private fun DashboardTile(label: String, value: String, secondary: String, tag: String? = null) {
    Surface(
        modifier = Modifier
            .widthIn(min = 112.dp)
            .heightIn(min = 82.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                modifier = if (tag == null) Modifier else Modifier.testTag(tag),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(secondary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InsightStrip(insights: List<Insight>) {
    if (insights.isEmpty()) return
    Section("Interesting Now") {
        insights.forEach { insight ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = insight.toneColor(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(insight.title, style = MaterialTheme.typography.labelLarge)
                        Text(insight.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(insight.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExploreTab(
    searchQuery: String,
    searchMode: SearchMode,
    searchSort: SearchSort,
    results: List<WordSearchResult>,
    onSearchChanged: (String) -> Unit,
    onSearchModeChanged: (SearchMode) -> Unit,
    onSearchSortChanged: (SearchSort) -> Unit,
    onSelectWord: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChanged,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("word-search-input"),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search") },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchMode.entries.forEach { mode ->
                FilterChip(
                    selected = searchMode == mode,
                    onClick = { onSearchModeChanged(mode) },
                    label = { Text(mode.label()) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchSort.entries.forEach { sort ->
                FilterChip(
                    selected = searchSort == sort,
                    onClick = { onSearchSortChanged(sort) },
                    label = { Text(sort.label()) },
                )
            }
        }

        Section("Words") {
            if (results.isEmpty()) {
                EmptyText()
            } else {
                results.forEach { result ->
                    SearchResultRow(result, onSelectWord)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: WordSearchResult, onSelectWord: (String) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onSelectWord(result.normalizedWord) },
        headlineContent = { Text(result.sampleOriginalWord, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Text(
                "${result.occurrences} uses - latest ${result.latestOccurrenceUtcMs.formatTimestamp()}" +
                    (result.distance?.let { " - d=$it" } ?: ""),
            )
        },
        trailingContent = { Text("${result.length}") },
    )
}

@Composable
private fun LabTab(metrics: WordMetrics) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Section("Session Storyline") {
            SessionStorylineBlock(metrics.sessionStoryline, metrics.corrections)
        }
        if (metrics.disclosure.showSessionContrast) {
            Section("Session Contrast") {
                SessionContrastBlock(metrics.sessionContrast)
            }
        }
        if (metrics.disclosure.showMotifRoutes) {
            Section("Motif Routes") {
                MotifRouteBlock(metrics.motifRoutes)
            }
        }
        if (metrics.disclosure.showMotifAttractors) {
            Section("Motif Attractors") {
                MotifAttractorBlock(metrics.motifAttractors)
            }
        }
        if (metrics.disclosure.showMemoryEchoes) {
            Section("Memory Echoes") {
                MemoryEchoBlock(metrics.memoryEchoes)
            }
        }
        if (metrics.disclosure.showConstellations) {
            Section("Similarity Constellation") {
                WordConstellationBlock(metrics.constellations)
            }
        }
        if (metrics.disclosure.showFamilyMigrations) {
            Section("Family Migrations") {
                FamilyMigrationBlock(metrics.familyMigrations)
            }
        }
        if (metrics.disclosure.showPhonotactics) {
            Section("Phonotactic Map") {
                PhonotacticBlock(metrics.phonotactics)
            }
        }
        if (metrics.disclosure.showConsonantSpine) {
            Section("Consonant Spine") {
                ConsonantSpineBlock(metrics.consonantSpine)
            }
        }
        if (metrics.disclosure.showVowelPalette) {
            Section("Vowel Palette") {
                VowelPaletteBlock(metrics.vowelPalette)
            }
        }
        if (metrics.disclosure.showSoundShapeDrift) {
            Section("Sound Shape Drift") {
                SoundShapeDriftBlock(metrics.soundShapeDrift)
            }
        }
        if (metrics.disclosure.showGenealogy) {
            Section("Word Genealogy") {
                GenealogyBlock(metrics.genealogy)
            }
        }
        if (metrics.disclosure.showEvolution) {
            Section("Evolution") {
                EvolutionBlock(metrics.evolution)
            }
        }
        if (metrics.disclosure.showSimilarity) {
            Section("Similarity") {
                SimilarityBlock(metrics.similarity)
            }
        }
        if (metrics.disclosure.showFragments) {
            Section("Fragments") {
                FragmentBlock(metrics.fragments)
            }
        }
        if (metrics.disclosure.showFamilies) {
            Section("Families") {
                FamilyBlock(metrics.families)
            }
        }
        if (metrics.disclosure.showBaselines) {
            Section("Baselines") {
                BaselineBlock(metrics.baselines)
            }
        }
    }
}

@Composable
private fun SessionStorylineBlock(
    storyline: SessionStorylineMetrics,
    corrections: CorrectionMetrics,
) {
    Text(
        text = storyline.headline,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = storyline.narrative,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SessionStorylineStrip(storyline.segments)
    SessionStorylineSignals(storyline.evidence)
    CorrectionEvidence(corrections)
    SessionStorylineMarkers(storyline.turningPoints)
    SessionStorylineRows(storyline.segments)
}

@Composable
private fun CorrectionEvidence(corrections: CorrectionMetrics) {
    if (corrections.totalCorrections == 0) return
    MetricLine(
        label = "Corrections",
        value = "${corrections.currentSessionCorrections} this session / ${corrections.totalCorrections} total",
    )
    MetricLine(
        label = "Correction pressure",
        value = corrections.currentSessionCorrectionRatePercentage.formatPercent(),
    )
    MetricLine(
        label = "Correction latency",
        value = listOf(
            "last ${corrections.lastCorrectionLatencyMs.formatDuration()}",
            "median ${corrections.medianCorrectionLatencyMs.formatDuration()}",
        ).joinToString(" - "),
    )
}

@Composable
private fun SessionStorylineStrip(segments: List<SessionStorylineSegment>) {
    if (segments.isEmpty()) {
        EmptyText()
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight((segment.endOrdinal - segment.startOrdinal + 1).toFloat().coerceAtLeast(1f))
                    .height(30.dp)
                    .background(
                        phaseColor(segment.phaseLabel)
                            .copy(alpha = (segment.intensity / 100.0).toFloat().coerceIn(0.38f, 1f)),
                    ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionStorylineSignals(signals: List<SessionStorylineSignal>) {
    if (signals.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        signals.forEach { signal ->
            Surface(
                color = signal.tone.storylineToneColor(),
                shape = RoundedCornerShape(6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(signal.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(signal.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(signal.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SessionStorylineMarkers(markers: List<SessionStorylineMarker>) {
    if (markers.isEmpty()) return
    Text("Turning points", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    markers.forEach { marker ->
        MetricLine(
            label = "word ${marker.ordinal}",
            value = "${marker.label} - ${marker.detail}",
        )
    }
}

@Composable
private fun SessionStorylineRows(segments: List<SessionStorylineSegment>) {
    if (segments.isEmpty()) return
    Text("Session windows", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    segments.take(8).forEach { segment ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${segment.startOrdinal}-${segment.endOrdinal}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(segment.phaseLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            StorylineSignalBand(segment)
            Text(
                text = listOfNotNull(
                    segment.dominantMotif?.let { "motif $it" },
                    segment.episodeLabel,
                    segment.epochLabel,
                ).joinToString(" - ").ifBlank { "open field" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${segment.reading} - pace ${segment.paceWordsPerMinute.formatNumber()} wpm - drift ${segment.averageEditDistance.formatNullableNumber()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StorylineSignalBand(segment: SessionStorylineSegment) {
    val drift = ((segment.averageEditDistance ?: 0.0) / 8.0 * 100.0).coerceIn(0.0, 100.0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(segment.noveltyPercentage.toFloat().coerceAtLeast(4f))
                .height(8.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            modifier = Modifier
                .weight(segment.repetitionPercentage.toFloat().coerceAtLeast(4f))
                .height(8.dp)
                .background(MaterialTheme.colorScheme.error),
        )
        Box(
            modifier = Modifier
                .weight(drift.toFloat().coerceAtLeast(4f))
                .height(8.dp)
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

@Composable
private fun EntropyBlock(entropy: EntropyMetrics) {
    MetricLine("Lexical entropy", entropy.lexicalEntropyBits.formatNullableNumber())
    MetricLine("Character entropy", entropy.characterEntropyBits.formatNullableNumber())
    MetricLine("Fragment entropy", entropy.fragmentEntropyBits.formatNullableNumber())
    MetricLine("Dictionary compression", entropy.compressionRatio.formatNullableNumber())
}

@Composable
private fun VocabularyBlock(vocabulary: VocabularyMetrics) {
    MetricLine("Vocabulary growth", vocabulary.vocabularyGrowthRate.formatPercent())
    MetricLine("Novelty decay", vocabulary.noveltyDecayPercentage.formatNullablePercent())
    MetricLine("Longest novelty streak", vocabulary.longestNoveltyStreak.toString())
    MetricLine("Longest duplicate streak", vocabulary.longestDuplicateStreak.toString())
    MetricLine("Family diversity", vocabulary.wordFamilyDiversity.toString())
}

@Composable
private fun GenerativePhaseBlock(phases: GenerativePhaseMetrics) {
    MetricLine("Dominant phase", phases.dominantLabel ?: "Pending")
    MetricLine("Phase switches", phases.phaseSwitchCount.toString())
    PhaseStrip(phases.segments)
    phases.segments.take(8).forEach { segment ->
        MetricLine(
            label = "${segment.startOrdinal}-${segment.endOrdinal}",
            value = "${segment.label} - ${segment.dominantFragment ?: "open"} - drift ${segment.averageEditDistance.formatNullableNumber()}",
        )
    }
    FingerprintBars(phases.fingerprint)
}

@Composable
private fun WordConstellationBlock(constellations: WordConstellationMetrics) {
    MetricLine("Connected words", constellations.connectedWordPercentage?.formatPercent() ?: "Pending")
    MetricLine("Densest family", constellations.densestConstellationLabel ?: "Pending")
    constellations.constellations.take(5).forEach { constellation ->
        WordConstellationRow(constellation)
    }
    WordConstellationEdges(constellations.edges)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordConstellationRow(constellation: WordConstellation) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(constellation.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "density ${constellation.densityPercentage.formatPercent()}, d=${constellation.averageDistance.formatNumber()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            constellation.words.forEachIndexed { index, word ->
                AssistChip(
                    onClick = {},
                    label = { Text(if (index == 0) "* $word" else word) },
                )
            }
        }
    }
}

@Composable
private fun WordConstellationEdges(edges: List<WordConstellationEdge>) {
    if (edges.isEmpty()) return
    Text("Nearest edges", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    edges.take(8).forEach { edge ->
        MetricLine(
            label = "${edge.fromWord} -> ${edge.toWord}",
            value = "d=${edge.distance} - ${edge.relationship}",
        )
    }
}

@Composable
private fun FamilyMigrationBlock(migrations: FamilyMigrationMetrics) {
    MetricLine("Dominant path", migrations.dominantPath ?: "Pending")
    migrations.narrative?.let { narrative -> MetricLine("Reading", narrative) }
    FamilyMigrationStrip(migrations.segments)
    FamilyMigrationTransitionBars(migrations.transitions)
    FamilyMigrationFamilyRows(migrations.families)
}

@Composable
private fun FamilyMigrationStrip(segments: List<FamilyMigrationSegment>) {
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight((segment.endOrdinal - segment.startOrdinal + 1).toFloat())
                    .height(24.dp)
                    .background(
                        familyMigrationColor(segment.dominantFamily).copy(
                            alpha = (segment.sharePercentage / 100.0).toFloat().coerceIn(0.35f, 1f),
                        ),
                    ),
            )
        }
    }
    segments.take(8).forEach { segment ->
        MetricLine(
            label = "${segment.startOrdinal}-${segment.endOrdinal}",
            value = "${segment.dominantFamily} ${segment.sharePercentage.formatPercent()}",
        )
    }
}

@Composable
private fun FamilyMigrationTransitionBars(transitions: List<FamilyMigrationTransition>) {
    if (transitions.isEmpty()) return
    Text("Migration map", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val maxCount = transitions.maxOf { it.count }.coerceAtLeast(1)
    transitions.take(6).forEach { transition ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${transition.fromFamily} -> ${transition.toFamily}", style = MaterialTheme.typography.bodyMedium)
                Text(transition.relationship, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(transition.count.toFloat() / maxCount.toFloat())
                        .height(10.dp)
                        .background(familyMigrationColor(transition.toFamily)),
                )
            }
            Text(
                "${transition.sharePercentage.formatPercent()} near word ${transition.boundaryOrdinal} - " +
                    transition.examples.joinToString(limit = 2),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FamilyMigrationFamilyRows(families: List<LatentWordFamily>) {
    if (families.isEmpty()) return
    Text("Latent families", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    families.take(4).forEach { family ->
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(family.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${family.occupancyPercentage.formatPercent()} - d=${family.averageDistance.formatNumber()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                family.words.take(8).forEach { word ->
                    AssistChip(onClick = {}, label = { Text(word) })
                }
            }
        }
    }
}

@Composable
private fun CreativeEpisodeBlock(episodes: CreativeEpisodeMetrics) {
    episodes.highlightedEpisode?.let { episode ->
        MetricLine(
            label = "Most revealing",
            value = "${episode.label} at ${episode.startOrdinal}-${episode.endOrdinal}",
        )
    }
    MetricLine("Creative pressure", episodes.creativePressure?.formatPercent() ?: "Pending")
    MetricLine("Repetition pressure", episodes.repetitivePressure?.formatPercent() ?: "Pending")
    CreativeEpisodeStrip(episodes.episodes)
    episodes.episodes.take(8).forEach { episode ->
        CreativeEpisodeRow(episode)
    }
}

@Composable
private fun CreativeEpisodeStrip(episodes: List<CreativeEpisode>) {
    if (episodes.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        episodes.forEach { episode ->
            Box(
                modifier = Modifier
                    .weight((episode.endOrdinal - episode.startOrdinal + 1).toFloat())
                    .height(24.dp)
                    .background(creativeEpisodeColor(episode.label).copy(alpha = (episode.intensity / 100.0).toFloat().coerceIn(0.35f, 1f))),
            )
        }
    }
}

@Composable
private fun CreativeEpisodeRow(episode: CreativeEpisode) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "${episode.startOrdinal}-${episode.endOrdinal}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(episode.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        EpisodeSignalBand(episode)
        Text(
            text = "${episode.dominantMotif ?: "open"} - ${episode.interpretation} - drift ${episode.averageEditDistance.formatNullableNumber()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EpisodeSignalBand(episode: CreativeEpisode) {
    val drift = ((episode.averageEditDistance ?: 0.0) / 8.0).coerceIn(0.0, 1.0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(episode.noveltyPercentage.toFloat().coerceAtLeast(4f))
                .height(8.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            modifier = Modifier
                .weight(episode.repetitionPercentage.toFloat().coerceAtLeast(4f))
                .height(8.dp)
                .background(MaterialTheme.colorScheme.error),
        )
        Box(
            modifier = Modifier
                .weight((drift * 100.0).toFloat().coerceAtLeast(4f))
                .height(8.dp)
                .background(MaterialTheme.colorScheme.secondary),
        )
    }
}

@Composable
private fun MotifRouteBlock(motifRoutes: MotifRouteMetrics) {
    MetricLine("Dominant route", motifRoutes.dominantRouteLabel ?: "Pending")
    MetricLine("Handoffs", motifRoutes.handoffCount.toString())
    MetricLine("Loop pressure", motifRoutes.loopPercentage?.formatPercent() ?: "Pending")
    MotifRouteStrip(motifRoutes.segments)
    MotifRouteBars(motifRoutes.routes)
}

@Composable
private fun MotifRouteStrip(segments: List<MotifRouteSegment>) {
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight((segment.endOrdinal - segment.startOrdinal + 1).toFloat())
                    .height(24.dp)
                    .background(motifRouteColor(segment.dominantMotif).copy(alpha = (segment.sharePercentage / 100.0).toFloat().coerceIn(0.35f, 1f))),
            )
        }
    }
    segments.take(8).forEach { segment ->
        MetricLine(
            label = "${segment.startOrdinal}-${segment.endOrdinal}",
            value = "${segment.dominantMotif} ${segment.sharePercentage.formatPercent()}",
        )
    }
}

@Composable
private fun MotifRouteBars(routes: List<MotifRoute>) {
    if (routes.isEmpty()) return
    Text("Route map", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val maxCount = routes.maxOf { it.count }.coerceAtLeast(1)
    routes.take(6).forEach { route ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${route.fromMotif} -> ${route.toMotif}", style = MaterialTheme.typography.bodyMedium)
                Text(route.relationship, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(route.count.toFloat() / maxCount.toFloat())
                        .height(10.dp)
                        .background(motifRouteColor(route.toMotif)),
                )
            }
            Text(
                "${route.sharePercentage.formatPercent()} - ${route.examples.joinToString(limit = 2)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MotifAttractorBlock(attractors: MotifAttractorMetrics) {
    attractors.strongestAttractor?.let { strongest ->
        MetricLine("Strongest pull", "${strongest.motif} - ${strongest.interpretation}")
    }
    attractors.narrative?.let { narrative ->
        MetricLine("Reading", narrative)
    }
    MotifAttractorStrip(attractors.timeline)
    MotifAttractorBars(attractors.attractors)
}

@Composable
private fun MotifAttractorStrip(segments: List<MotifAttractorSegment>) {
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight((segment.endOrdinal - segment.startOrdinal + 1).toFloat())
                    .height(24.dp)
                    .background(motifRouteColor(segment.dominantMotif).copy(alpha = (segment.sharePercentage / 100.0).toFloat().coerceIn(0.35f, 1f))),
            )
        }
    }
    segments.take(8).forEach { segment ->
        MetricLine(
            label = "${segment.startOrdinal}-${segment.endOrdinal}",
            value = "${segment.dominantMotif} ${segment.sharePercentage.formatPercent()}",
        )
    }
}

@Composable
private fun MotifAttractorBars(attractors: List<MotifAttractor>) {
    if (attractors.isEmpty()) return
    Text("Pull strength", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    attractors.take(6).forEach { attractor ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(attractor.motif, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(attractor.interpretation, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((attractor.pullStrength / 100.0).coerceIn(0.0, 1.0).toFloat())
                        .height(10.dp)
                        .background(motifRouteColor(attractor.motif)),
                )
            }
            Text(
                "${attractor.occupancyPercentage.formatPercent()} occupancy - ${attractor.returnCount} returns - " +
                    "longest ${attractor.longestRun} - entry drift ${attractor.averageEntryDrift.formatNullableNumber()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                attractor.examples.joinToString(limit = 4),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MemoryEchoBlock(echoes: MemoryEchoMetrics) {
    echoes.strongestEcho?.let { echo ->
        MetricLine("Strongest echo", "${echo.previousWord} -> ${echo.currentWord}")
        MetricLine("Echo gap", "${echo.gap} words - ${echo.relationship}")
    }
    MetricLine("Echo density", echoes.echoDensityPercentage?.formatPercent() ?: "Pending")
    echoes.narrative?.let { narrative ->
        MetricLine("Reading", narrative)
    }
    MemoryEchoStrip(echoes.segments)
    MemoryEchoRows(echoes.links)
}

@Composable
private fun MemoryEchoStrip(segments: List<MemoryEchoSegment>) {
    if (segments.isEmpty()) return
    val maxEchoes = segments.maxOf { it.echoCount }.coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            val alpha = if (segment.echoCount == 0) {
                0.18f
            } else {
                (0.35f + 0.65f * segment.echoCount.toFloat() / maxEchoes.toFloat()).coerceIn(0.35f, 1f)
            }
            Box(
                modifier = Modifier
                    .weight((segment.endOrdinal - segment.startOrdinal + 1).toFloat())
                    .height(24.dp)
                    .background(
                        if (segment.echoCount == 0) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = alpha)
                        },
                    ),
            )
        }
    }
    segments.take(8).forEach { segment ->
        MetricLine(
            label = "${segment.startOrdinal}-${segment.endOrdinal}",
            value = "${segment.echoCount} echoes, strength ${segment.averageStrength.formatNumber()}",
        )
    }
}

@Composable
private fun MemoryEchoRows(links: List<MemoryEchoLink>) {
    if (links.isEmpty()) return
    Text("Echo links", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    links.takeLast(8).forEach { echo ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${echo.previousOrdinal} -> ${echo.currentOrdinal}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(echo.relationship, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((echo.strength / 100.0).coerceIn(0.0, 1.0).toFloat())
                        .height(10.dp)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
            Text(
                "${echo.previousWord} -> ${echo.currentWord} - gap ${echo.gap}, d=${echo.distance}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionContrastBlock(contrast: SessionContrastMetrics) {
    MetricLine("Compared sessions", contrast.historicalSessionsCompared.toString())
    MetricLine("Nearest prior", contrast.nearestSessionLabel ?: "Pending")
    MetricLine("Nearest similarity", contrast.similarityPercentage?.formatPercent() ?: "Pending")
    contrast.strongestDifference?.let { difference ->
        MetricLine("Main difference", "${difference.label} - ${difference.interpretation}")
    }
    contrast.dimensions.take(7).forEach { dimension ->
        SessionContrastBar(dimension)
    }
}

@Composable
private fun SessionContrastBar(dimension: SessionContrastDimension) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dimension.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${dimension.deltaPoints.signedNumber()} - ${dimension.interpretation}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((dimension.historicalValue / 100.0).coerceIn(0.0, 1.0).toFloat())
                    .height(18.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth((dimension.currentValue / 100.0).coerceIn(0.0, 1.0).toFloat())
                    .height(10.dp)
                    .align(Alignment.CenterStart)
                    .background(
                        if (dimension.deltaPoints >= 0.0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    ),
            )
        }
        Text(
            "current ${dimension.currentValue.formatNumber()}, usual ${dimension.historicalValue.formatNumber()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhonotacticBlock(phonotactics: PhonotacticMetrics) {
    MetricLine("Dominant rule", phonotactics.dominantRule ?: "Pending")
    PhonotacticRulebook(phonotactics.rules)
    phonotactics.lanes.forEach { lane ->
        PhonotacticLaneBlock(lane)
    }
    PhonotacticTransitionBars(phonotactics.transitions)
    PhonotacticSkeletonRows(phonotactics.skeletons)
}

@Composable
private fun PhonotacticRulebook(rules: List<PhonotacticRule>) {
    if (rules.isEmpty()) return
    Text("Candidate rulebook", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    rules.take(6).forEach { rule ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${rule.scope} ${rule.pattern}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    rule.confidencePercentage.formatPercent(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((rule.confidencePercentage / 100.0).coerceIn(0.0, 1.0).toFloat())
                        .height(10.dp)
                        .background(phonotacticRuleColor(rule.scope)),
                )
            }
            Text(
                "${rule.interpretation} - ${rule.exceptionCount} exceptions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "examples ${rule.examples.joinToString(limit = 3)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rule.exceptions.isNotEmpty()) {
                Text(
                    "breaks ${rule.exceptions.joinToString(limit = 3)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PhonotacticLaneBlock(lane: PhonotacticLane) {
    Text(lane.role, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    lane.motifs.take(4).forEach { motif ->
        Meter("${lane.role} ${motif.motif}", motif.coveragePercentage / 100.0)
        Text(
            motif.examples.joinToString(limit = 3),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhonotacticTransitionBars(transitions: List<PhonotacticTransition>) {
    if (transitions.isEmpty()) return
    Text("Sound transitions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val maxCoverage = transitions.maxOf { it.coveragePercentage }.coerceAtLeast(1.0)
    transitions.take(6).forEach { transition ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${transition.from} -> ${transition.to}", style = MaterialTheme.typography.bodyMedium)
                Text(transition.coveragePercentage.formatPercent(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((transition.coveragePercentage / maxCoverage).coerceIn(0.0, 1.0).toFloat())
                        .height(10.dp)
                        .background(MaterialTheme.colorScheme.secondary),
                )
            }
            Text(
                transition.examples.joinToString(limit = 3),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhonotacticSkeletonRows(skeletons: List<PhonotacticSkeleton>) {
    if (skeletons.isEmpty()) return
    Text("Sound skeletons", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        skeletons.forEach { skeleton ->
            AssistChip(
                onClick = {},
                label = { Text("${skeleton.pattern} ${skeleton.coveragePercentage.formatPercent()}") },
            )
        }
    }
}

@Composable
private fun ConsonantSpineBlock(spine: ConsonantSpineMetrics) {
    MetricLine("Dominant spine", spine.dominantSpine ?: "Pending")
    MetricLine("Strongest spine shift", spine.strongestShift?.relationship ?: "Pending")
    spine.narrative?.let { narrative -> MetricLine("Reading", narrative) }
    ConsonantSpineStrip(spine.segments)
    ConsonantSpineShiftBars(spine.shifts)
    spine.segments.take(8).forEach { segment ->
        MetricLine(
            label = "${segment.startOrdinal}-${segment.endOrdinal}",
            value = "${segment.dominantSpine} ${segment.sharePercentage.formatPercent()} - vowels ${segment.averageVowelSlots.formatNumber()}",
        )
        Text(
            segment.examples.joinToString(limit = 4),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConsonantSpineStrip(segments: List<ConsonantSpineSegment>) {
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight((segment.endOrdinal - segment.startOrdinal + 1).toFloat())
                    .height(24.dp)
                    .background(
                        consonantSpineColor(segment.dominantSpine).copy(
                            alpha = (segment.sharePercentage / 100.0).toFloat().coerceIn(0.35f, 1f),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun ConsonantSpineShiftBars(shifts: List<ConsonantSpineShift>) {
    if (shifts.isEmpty()) return
    Text("Spine boundaries", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    shifts.take(6).forEach { shift ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${shift.fromSpine} -> ${shift.toSpine}", style = MaterialTheme.typography.bodyMedium)
                Text(shift.relationship, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((shift.intensity / 100.0).coerceIn(0.0, 1.0).toFloat())
                        .height(10.dp)
                        .background(consonantSpineColor(shift.toSpine)),
                )
            }
            Text(
                "near word ${shift.boundaryOrdinal} - intensity ${shift.intensity.formatNumber()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VowelPaletteBlock(palette: VowelPaletteMetrics) {
    MetricLine("Dominant palette", palette.dominantPalette ?: "Pending")
    MetricLine("Strongest color shift", palette.strongestShift?.relationship ?: "Pending")
    palette.narrative?.let { narrative -> MetricLine("Reading", narrative) }
    VowelPaletteHeatmap(palette.segments)
    VowelPaletteShiftBars(palette.shifts)
    palette.segments.take(8).forEach { segment ->
        MetricLine(
            label = "${segment.startOrdinal}-${segment.endOrdinal}",
            value = "${segment.paletteLabel} /${segment.dominantVowel}/ ${segment.dominantSharePercentage.formatPercent()}",
        )
        Text(
            segment.examples.joinToString(limit = 4),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VowelPaletteHeatmap(segments: List<VowelPaletteSegment>) {
    if (segments.isEmpty()) return
    Text("Vowel heatmap", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(modifier = Modifier.width(58.dp))
        listOf("a", "e", "i", "o", "u").forEach { vowel ->
            Text(
                text = vowel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    segments.forEach { segment ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${segment.startOrdinal}-${segment.endOrdinal}",
                modifier = Modifier.width(58.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            segment.shares.forEach { share ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            vowelColor(share.vowel).copy(
                                alpha = (0.16f + (share.percentage / 100.0).toFloat() * 0.84f).coerceIn(0.16f, 1f),
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun VowelPaletteShiftBars(shifts: List<VowelPaletteShift>) {
    if (shifts.isEmpty()) return
    Text("Palette boundaries", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    shifts.take(6).forEach { shift ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${shift.fromPalette} -> ${shift.toPalette}", style = MaterialTheme.typography.bodyMedium)
                Text(shift.relationship, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((shift.intensity / 100.0).coerceIn(0.0, 1.0).toFloat())
                        .height(10.dp)
                        .background(vowelPaletteColor(shift.toPalette)),
                )
            }
            Text(
                "near word ${shift.boundaryOrdinal} - intensity ${shift.intensity.formatNumber()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SoundShapeDriftBlock(drift: SoundShapeDriftMetrics) {
    MetricLine("Dominant shape", drift.dominantShape ?: "Pending")
    MetricLine("Shape stability", drift.shapeStabilityPercentage?.formatPercent() ?: "Pending")
    drift.narrative?.let { narrative -> MetricLine("Reading", narrative) }
    SoundShapeStrip(drift.segments)
    SoundShapeSegmentRows(drift.segments)
    SoundShapeShiftBars(drift.shifts)
}

@Composable
private fun SoundShapeStrip(segments: List<SoundShapeSegment>) {
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight((segment.endOrdinal - segment.startOrdinal + 1).toFloat())
                    .height(24.dp)
                    .background(
                        soundShapeColor(segment.dominantSkeleton).copy(
                            alpha = (segment.sharePercentage / 100.0).toFloat().coerceIn(0.35f, 1f),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun SoundShapeSegmentRows(segments: List<SoundShapeSegment>) {
    segments.take(8).forEach { segment ->
        MetricLine(
            label = "${segment.startOrdinal}-${segment.endOrdinal}",
            value = "${segment.dominantSkeleton} ${segment.sharePercentage.formatPercent()} - len ${segment.averageLength.formatNumber()}",
        )
        Text(
            segment.examples.joinToString(limit = 4),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SoundShapeShiftBars(shifts: List<SoundShapeShift>) {
    if (shifts.isEmpty()) return
    Text("Shape boundaries", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    shifts.take(6).forEach { shift ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${shift.fromSkeleton} -> ${shift.toSkeleton}", style = MaterialTheme.typography.bodyMedium)
                Text(shift.relationship, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((shift.intensity / 100.0).coerceIn(0.0, 1.0).toFloat())
                        .height(10.dp)
                        .background(soundShapeColor(shift.toSkeleton)),
                )
            }
            Text(
                "near word ${shift.boundaryOrdinal} - intensity ${shift.intensity.formatNumber()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LanguageEpochBlock(languageEpochs: LanguageEpochMetrics) {
    MetricLine("Strongest shift", languageEpochs.strongestShift?.label ?: "Pending")
    MetricLine("Average shift", languageEpochs.averageShiftIntensity?.formatNumber() ?: "Pending")
    LanguageEpochStrip(languageEpochs.epochs)
    languageEpochs.strongestShift?.let { shift ->
        MetricLine("Boundary", "word ${shift.boundaryOrdinal} - ${shift.motifChange}")
    }
    LanguageShiftBars(languageEpochs.shifts)
    languageEpochs.epochs.take(8).forEach { epoch ->
        MetricLine(
            label = "${epoch.startOrdinal}-${epoch.endOrdinal}",
            value = "${epoch.label} - ${epoch.dominantMotif ?: "open"} ${epoch.dominantMotifSharePercentage.formatPercent()}",
        )
    }
}

@Composable
private fun LanguageEpochStrip(epochs: List<LanguageEpoch>) {
    if (epochs.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        epochs.forEach { epoch ->
            Box(
                modifier = Modifier
                    .weight((epoch.endOrdinal - epoch.startOrdinal + 1).toFloat())
                    .height(24.dp)
                    .background(epochColor(epoch.label).copy(alpha = (epoch.intensity / 100.0).toFloat().coerceIn(0.35f, 1f))),
            )
        }
    }
}

@Composable
private fun LanguageShiftBars(shifts: List<LanguageShift>) {
    if (shifts.isEmpty()) return
    Text("Shift map", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    shifts.take(5).forEach { shift ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "word ${shift.boundaryOrdinal}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(shift.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((shift.intensity / 100.0).coerceIn(0.0, 1.0).toFloat())
                        .height(10.dp)
                        .background(shiftColor(shift.label)),
                )
            }
            Text(
                "${shift.fromLabel} -> ${shift.toLabel} (${shift.motifChange})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GenealogyBlock(genealogy: WordGenealogyMetrics) {
    MetricLine("Ancestry continuity", genealogy.ancestryContinuityPercentage?.formatPercent() ?: "Pending")
    MetricLine("Roots", genealogy.rootCount.toString())
    MetricLine("Main branching word", genealogy.branchingWord ?: "Pending")
    genealogy.lineages.take(6).forEach { lineage ->
        LineageStrip(lineage)
    }
    if (genealogy.links.isNotEmpty()) {
        Text("Recent births", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        genealogy.links.takeLast(8).forEach { link ->
            MetricLine(
                label = "${link.parentWord} -> ${link.childWord}",
                value = "d=${link.distance} - ${link.relationship}",
            )
        }
    }
}

@Composable
private fun LineageStrip(lineage: WordLineage) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(lineage.rootWord, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "depth ${lineage.depth}, d=${lineage.averageParentDistance.formatNumber()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            lineage.words.forEachIndexed { index, _ ->
                val color = if (index == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(18.dp)
                        .background(color.copy(alpha = (0.35f + index * 0.05f).coerceAtMost(0.95f))),
                )
            }
        }
        Text(
            text = lineage.words.joinToString(" -> ", limit = 8),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhaseStrip(segments: List<GenerativePhaseSegment>) {
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(5.dp)),
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight((segment.endOrdinal - segment.startOrdinal + 1).toFloat())
                    .height(22.dp)
                    .background(phaseColor(segment.label).copy(alpha = (segment.intensity / 100.0).toFloat().coerceIn(0.35f, 1f))),
            )
        }
    }
}

@Composable
private fun FingerprintBars(fingerprint: List<SessionFingerprintDimension>) {
    if (fingerprint.isEmpty()) return
    Text("Session fingerprint", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    fingerprint.forEach { dimension ->
        Meter(dimension.label, dimension.value / 100.0)
        Text(dimension.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EvolutionBlock(evolution: EvolutionMetrics) {
    MetricLine("Dominant transition", evolution.dominantTransitionLabel ?: "Pending")
    MetricLine("Continuity", evolution.continuityPercentage?.formatPercent() ?: "Pending")
    MetricLine("Average drift", evolution.averageDriftDistance.formatNullableNumber())
    MutationGrammarBlock(evolution.mutationGrammar)
    FrequencyBars(
        data = evolution.transitions,
        label = { it.label },
        value = { it.count },
    )
    EvolutionExamples(evolution.transitions)
}

@Composable
private fun MutationGrammarBlock(grammar: MutationGrammarMetrics) {
    if (grammar.operators.isEmpty()) return
    Text("Mutation Grammar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    MetricLine("Dominant operator", grammar.dominantOperator ?: "Pending")
    grammar.dominantChoreography?.let { choreography ->
        MetricLine("Dominant choreography", choreography)
    }
    grammar.narrative?.let { narrative -> MetricLine("Reading", narrative) }
    MutationGrammarStrip(grammar.segments)
    MutationChoreographyBars(grammar.choreography)
    MutationOperatorBars(grammar.operators)
}

@Composable
private fun MutationGrammarStrip(segments: List<MutationGrammarSegment>) {
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight((segment.endOrdinal - segment.startOrdinal + 1).toFloat().coerceAtLeast(1f))
                    .height(24.dp)
                    .background(
                        mutationOperatorColor(segment.dominantOperator)
                            .copy(alpha = (segment.sharePercentage / 100.0).toFloat().coerceIn(0.35f, 1f)),
                    ),
            )
        }
    }
    segments.take(8).forEach { segment ->
        MetricLine(
            label = "${segment.startOrdinal}-${segment.endOrdinal}",
            value = "${segment.dominantOperator} ${segment.sharePercentage.formatPercent()} - ${segment.example}",
        )
    }
}

@Composable
private fun MutationChoreographyBars(choreography: List<MutationOperatorTransition>) {
    if (choreography.isEmpty()) return
    Text("Operator Choreography", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val maxCount = choreography.maxOf { it.count }.coerceAtLeast(1)
    choreography.take(5).forEach { transition ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${transition.fromOperator} -> ${transition.toOperator}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    transition.sharePercentage.formatPercent(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(transition.count.toFloat() / maxCount.toFloat())
                        .height(10.dp)
                        .background(mutationOperatorColor(transition.toOperator)),
                )
            }
            Text(
                transition.relationship,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                transition.examples.joinToString(limit = 1),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MutationOperatorBars(operators: List<MutationOperatorSummary>) {
    if (operators.isEmpty()) return
    Text("Operators", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val maxCount = operators.maxOf { it.count }.coerceAtLeast(1)
    operators.take(6).forEach { operator ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(operator.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(operator.sharePercentage.formatPercent(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(operator.count.toFloat() / maxCount.toFloat())
                        .height(10.dp)
                        .background(mutationOperatorColor(operator.label)),
                )
            }
            Text(
                "${operator.interpretation} - d=${operator.averageEditDistance.formatNumber()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                operator.examples.joinToString(limit = 2),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EvolutionExamples(transitions: List<EvolutionTransition>) {
    transitions.take(4).forEach { transition ->
        MetricLine(
            label = transition.label,
            value = "${transition.sharePercentage.formatPercent()} - ${transition.examples.joinToString(limit = 2)}",
        )
    }
}

@Composable
private fun RhythmBlock(rhythm: RhythmMetrics) {
    MetricLine("Session density", rhythm.sessionDensity.formatNumber())
    MetricLine("Bursts", rhythm.burstCount.toString())
    MetricLine("Rhythm stability", rhythm.rhythmStability.formatNullablePercent())
    MetricLine("Evolution speed", rhythm.wordEvolutionSpeed.formatNullableNumber())
    MetricLine("Average recurrence", rhythm.averageRecurrenceLatencyMs.formatDuration())
    MetricLine("Circadian peak", rhythm.circadianPeakHour?.let { "$it:00" } ?: "Pending")
    MetricLine("Weekly peak", rhythm.weeklyPeakDay ?: "Pending")
}

@Composable
private fun SimilarityBlock(similarity: SimilarityMetrics) {
    MetricLine("Average similarity", similarity.averageSimilarity.formatSimilarity())
    MetricLine("Median similarity", similarity.medianSimilarity.formatSimilarity())
    MetricLine("Average edit distance", similarity.averageEditDistance.formatNullableNumber())
    MetricLine("Median edit distance", similarity.medianEditDistance.formatNullableNumber())
    FrequencyBars(
        data = similarity.distribution,
        label = { it.label },
        value = { it.count },
    )
}

@Composable
private fun FragmentBlock(fragments: FragmentMetrics) {
    FragmentLifecycleHeatmap(fragments.lifecycles)
    MotifEcologyBlock(fragments.ecology)
    RankedFragmentRows("Useful", fragments.usefulFragments)
    RankedFragmentRows("New", fragments.newFragments)
    RankedFragmentRows("Growing", fragments.growingFragments)
    RankedFragmentRows("Declining", fragments.decliningFragments)
    RankedFragmentRows("Stable", fragments.stableFragments)
}

@Composable
private fun FragmentLifecycleHeatmap(lifecycles: List<FragmentLifecycle>) {
    if (lifecycles.isEmpty()) return
    Text("Motif flow", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val maxCount = lifecycles.flatMap { it.windowCounts }.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lifecycles.take(10).forEach { lifecycle ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = lifecycle.fragment,
                    modifier = Modifier.widthIn(min = 44.dp, max = 72.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    lifecycle.windowCounts.forEach { count ->
                        val alpha = if (count == 0) 0.18f else (0.24f + 0.76f * count.toFloat() / maxCount.toFloat())
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(16.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (count == 0) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = alpha.coerceIn(0.24f, 1f))
                                    },
                                ),
                        )
                    }
                }
                Text(
                    text = lifecycle.trend.shortLabel(),
                    modifier = Modifier.widthIn(min = 42.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MotifEcologyBlock(ecology: MotifEcologyMetrics) {
    if (ecology.stories.isEmpty()) return
    Text("Motif Ecology", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ecology.dominantStory?.let { story ->
        MetricLine("Dominant life", "${story.fragment} - ${story.status}")
    }
    ecology.narrative?.let { narrative ->
        MetricLine("Reading", narrative)
    }
    ecology.stories.take(6).forEach { story ->
        MotifEcologyRow(story)
    }
}

@Composable
private fun MotifEcologyRow(story: MotifEcologyStory) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(story.fragment, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(story.status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        val maxCount = story.windowCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            story.windowCounts.forEachIndexed { index, count ->
                val alpha = if (count == 0) {
                    0.16f
                } else {
                    (0.28f + 0.72f * count.toFloat() / maxCount.toFloat()).coerceIn(0.28f, 1f)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(18.dp)
                        .background(
                            if (count == 0) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                motifEcologyColor(story.status).copy(alpha = alpha)
                            },
                        ),
                ) {
                    if (index == story.birthWindow || index == story.peakWindow || index == story.extinctionWindow) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)),
                        )
                    }
                }
            }
        }
        Text(
            story.interpretation,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (story.examples.isNotEmpty()) {
            Text(
                "examples ${story.examples.joinToString(limit = 4)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RankedFragmentRows(label: String, fragments: List<RankedFragment>) {
    if (fragments.isEmpty()) return
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fragments.forEach { fragment ->
            AssistChip(
                onClick = {},
                label = { Text("${fragment.fragment} ${fragment.count}") },
            )
        }
    }
}

@Composable
private fun FamilyBlock(families: FamilyMetrics) {
    Meter("Recurring family score", families.recurringFamilyScore / 100.0)
    families.families.forEach { family ->
        MetricLine(family.label, "${family.totalOccurrences} uses - ${family.words.joinToString(limit = 4)}")
    }
}

@Composable
private fun BaselineBlock(baselines: List<BaselineComparison>) {
    baselines.forEach { baseline ->
        Text(baseline.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        MetricLine("Today", baseline.todayValue.formatNumber())
        MetricLine("7-day", "${baseline.sevenDayAverage.formatNullableNumber()} (${baseline.sevenDayDeviationPercentage.formatNullablePercent()})")
        MetricLine("30-day", "${baseline.thirtyDayAverage.formatNullableNumber()} (${baseline.thirtyDayDeviationPercentage.formatNullablePercent()})")
        MetricLine("All-time", "${baseline.allTimeAverage.formatNullableNumber()} (${baseline.allTimeDeviationPercentage.formatNullablePercent()})")
    }
}

@Composable
private fun SessionsTab(
    sessions: List<SessionSummaryRow>,
    currentSessionId: String?,
    initialSessionId: String? = null,
) {
    var relatedSessionId by rememberSaveable { mutableStateOf(initialSessionId) }
    LaunchedEffect(initialSessionId) { if (initialSessionId != null) relatedSessionId = initialSessionId }
    Section("Session History") {
        if (sessions.isEmpty()) {
            EmptyText()
        } else {
            sessions.forEach { session ->
                ListItem(
                    modifier = Modifier.semantics {
                        if (relatedSessionId == session.id) contentDescription = "hub-detail-wordpulse/word_session/${session.id}"
                    },
                    headlineContent = {
                        Text(
                            text = if (session.id == currentSessionId) {
                                "${session.id.shortSessionLabel()} active"
                            } else {
                                session.id.shortSessionLabel()
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    supportingContent = {
                        Column {
                            Text("${session.wordCount} words, ${session.uniqueCount} unique - ${session.startedAtUtcMs.formatTimestamp()}")
                            TextButton(onClick = { relatedSessionId = session.id }) { Text("Related contexts") }
                        }
                    },
                )
                if (relatedSessionId == session.id) {
                    com.gernalix.personalhub.core.hubcontext.HubContextLinks(
                        com.gernalix.personalhub.contracts.database.HubEntityRef("wordpulse", "word_session", session.id),
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WordDetailDialog(detail: WordDetail, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text(detail.sampleOriginalWord) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricLine("Total occurrences", detail.totalOccurrences.toString())
                MetricLine("First occurrence", detail.firstOccurrenceUtcMs.formatTimestamp())
                MetricLine("Latest occurrence", detail.latestOccurrenceUtcMs.formatTimestamp())
                MetricLine("Sessions", detail.sessionIds.size.toString())
                MetricLine("Average recurrence", detail.averageIntervalBetweenOccurrencesMs.formatDuration())
                MetricLine("Longest recurrence", detail.longestIntervalBetweenOccurrencesMs.formatDuration())
                MetricLine("Shortest recurrence", detail.shortestIntervalBetweenOccurrencesMs.formatDuration())
                MetricLine("Prefix", detail.prefix)
                MetricLine("Suffix", detail.suffix)
                MetricLine("Fragments", detail.detectedFragments.joinToString(limit = 10))
                Text("Nearest", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                detail.closestSimilarWords.forEach { neighbor ->
                    MetricLine(neighbor.sampleOriginalWord, "d=${neighbor.distance}, ${neighbor.similarity.formatSimilarity()}")
                }
            }
        },
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        HorizontalDivider()
        content()
    }
}

@Composable
private fun MetricLine(label: String, value: String, tag: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            modifier = Modifier
                .weight(1f)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Meter(label: String, value: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text((value * 100.0).rounded(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.coerceIn(0.0, 1.0).toFloat())
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun <T> FrequencyBars(
    data: List<T>,
    label: (T) -> String,
    value: (T) -> Int,
    modifier: Modifier = Modifier,
) {
    if (data.isEmpty()) {
        EmptyText()
        return
    }
    val max = data.maxOf(value).coerceAtLeast(1)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        data.take(24).forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label(item), modifier = Modifier.widthIn(min = 38.dp), style = MaterialTheme.typography.labelMedium)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(value(item).toFloat() / max.toFloat())
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                }
                Text(value(item).toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, label: String) {
    if (values.size < 3) return
    val max = values.maxOrNull()?.takeIf { it > 0.0 } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            values.takeLast(64).forEach { value ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((6 + (value / max * 38)).dp)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                )
            }
        }
    }
}

@Composable
private fun EmptyText() {
    Text(
        text = "No data",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Insight.toneColor(): Color =
    when (tone) {
        InsightTone.Positive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        InsightTone.Attention -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        InsightTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

@Composable
private fun InsightTone.storylineToneColor(): Color =
    when (this) {
        InsightTone.Positive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        InsightTone.Attention -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        InsightTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }

private fun SearchMode.label(): String =
    name.replaceFirstChar { it.titlecase(Locale.US) }

private fun SearchSort.label(): String =
    name.replaceFirstChar { it.titlecase(Locale.US) }

private fun FragmentTrend.shortLabel(): String =
    when (this) {
        FragmentTrend.New -> "new"
        FragmentTrend.Growing -> "up"
        FragmentTrend.Declining -> "down"
        FragmentTrend.Stable -> "flat"
    }

@Composable
private fun phaseColor(label: String): Color =
    when (label) {
        "Motif lock" -> MaterialTheme.colorScheme.tertiary
        "Family drift" -> MaterialTheme.colorScheme.secondary
        "Repetition loop" -> MaterialTheme.colorScheme.error
        "Wide exploration",
        "Open exploration" -> MaterialTheme.colorScheme.primary
        "Jumping" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun epochColor(label: String): Color =
    when {
        label == "Loop epoch" -> MaterialTheme.colorScheme.error
        label == "Exploration epoch" || label == "Open epoch" -> MaterialTheme.colorScheme.primary
        label == "Bridge epoch" -> MaterialTheme.colorScheme.secondary
        label.endsWith("epoch") && label.length > " epoch".length -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun shiftColor(label: String): Color =
    when (label) {
        "Motif replacement" -> MaterialTheme.colorScheme.tertiary
        "Loop onset",
        "Exploration collapse" -> MaterialTheme.colorScheme.error
        "Loop release",
        "Exploration burst" -> MaterialTheme.colorScheme.primary
        "Drift jump",
        "Drift settling" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun creativeEpisodeColor(label: String): Color =
    when (label) {
        "Invention burst",
        "Open drift" -> MaterialTheme.colorScheme.primary
        "Mutation run",
        "Wild crossing" -> MaterialTheme.colorScheme.secondary
        "Motif forging",
        "Motif orbit" -> MaterialTheme.colorScheme.tertiary
        "Fixation pocket",
        "Settled groove" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun motifEcologyColor(status: String): Color =
    when (status) {
        "Late birth",
        "Takeover",
        "Bloom" -> MaterialTheme.colorScheme.primary
        "Extinction",
        "Fade" -> MaterialTheme.colorScheme.error
        "Resurgence" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

@Composable
private fun mutationOperatorColor(label: String): Color =
    when (label) {
        "Suffix growth",
        "Prefix growth" -> MaterialTheme.colorScheme.primary
        "Suffix pruning",
        "Prefix pruning" -> MaterialTheme.colorScheme.error
        "Vowel recoloring",
        "Sound substitution" -> MaterialTheme.colorScheme.tertiary
        "Opening swap",
        "Ending swap",
        "Frame mutation" -> MaterialTheme.colorScheme.secondary
        "Repeat" -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.errorContainer
    }

@Composable
private fun motifRouteColor(label: String): Color =
    when {
        label == "open" -> MaterialTheme.colorScheme.outline
        label.length >= 4 -> MaterialTheme.colorScheme.tertiary
        label.firstOrNull() in setOf('v', 'l') -> MaterialTheme.colorScheme.primary
        label.firstOrNull() in setOf('s', 'c') -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }

@Composable
private fun familyMigrationColor(label: String): Color =
    when {
        label == "open" -> MaterialTheme.colorScheme.outline
        label.lowercase(Locale.US).firstOrNull() in setOf('v', 'l') -> MaterialTheme.colorScheme.primary
        label.lowercase(Locale.US).firstOrNull() in setOf('s', 'c') -> MaterialTheme.colorScheme.secondary
        label.lowercase(Locale.US).firstOrNull() in setOf('b', 'r') -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

@Composable
private fun soundShapeColor(shape: String): Color =
    when {
        shape.startsWith("CC") && shape.endsWith("CV") -> MaterialTheme.colorScheme.tertiary
        shape.startsWith("CC") -> MaterialTheme.colorScheme.primary
        shape.endsWith("CV") -> MaterialTheme.colorScheme.secondary
        shape.length >= 8 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun phonotacticRuleColor(scope: String): Color =
    when (scope) {
        "Onset" -> MaterialTheme.colorScheme.primary
        "Core" -> MaterialTheme.colorScheme.tertiary
        "Coda" -> MaterialTheme.colorScheme.secondary
        "Shape" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun consonantSpineColor(spine: String): Color =
    when {
        spine.length >= 5 -> MaterialTheme.colorScheme.error
        spine.firstOrNull() in setOf('m', 'n') -> MaterialTheme.colorScheme.primary
        spine.firstOrNull() in setOf('v', 'l') -> MaterialTheme.colorScheme.secondary
        spine.firstOrNull() in setOf('s', 'c') -> MaterialTheme.colorScheme.tertiary
        spine.firstOrNull() in setOf('b', 'r', 't') -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun vowelColor(vowel: String): Color =
    when (vowel) {
        "a" -> MaterialTheme.colorScheme.tertiary
        "e" -> MaterialTheme.colorScheme.primary
        "i" -> MaterialTheme.colorScheme.secondary
        "o" -> MaterialTheme.colorScheme.errorContainer
        "u" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun vowelPaletteColor(label: String): Color =
    when (label) {
        "front-bright",
        "high-i",
        "clear-e" -> MaterialTheme.colorScheme.primary
        "open-a" -> MaterialTheme.colorScheme.tertiary
        "round-back",
        "deep-o",
        "dark-u" -> MaterialTheme.colorScheme.error
        "mixed wash" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }

private fun String.shortSessionLabel(): String =
    if (length <= 8) this else "Session ${take(8)}"

private fun Long.formatTimestamp(): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(this))

private fun Long.formatTime(): String =
    DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(this))

private fun Long?.formatDuration(): String =
    this?.let { millis ->
        when {
            millis < 1_000L -> "$millis ms"
            millis < 60_000L -> String.format(Locale.US, "%.1f s", millis / 1_000.0)
            else -> String.format(Locale.US, "%.1f min", millis / 60_000.0)
        }
    } ?: "Pending"

private fun Double?.formatDuration(): String =
    this?.let { millis ->
        when {
            millis < 1_000.0 -> String.format(Locale.US, "%.0f ms", millis)
            millis < 60_000.0 -> String.format(Locale.US, "%.1f s", millis / 1_000.0)
            else -> String.format(Locale.US, "%.1f min", millis / 60_000.0)
        }
    } ?: "Pending"

private fun Double.formatNumber(): String = String.format(Locale.US, "%.1f", this)

private fun Double?.formatNullableNumber(): String = this?.formatNumber() ?: "Pending"

private fun Double.formatPercent(): String = String.format(Locale.US, "%.1f%%", this)

private fun Double?.formatNullablePercent(): String = this?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "Pending"

private fun Double?.formatSimilarity(): String = this?.let { String.format(Locale.US, "%.1f%%", it * 100.0) } ?: "Pending"

private fun Double?.formatSimilarityShort(): String = this?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "first"

private fun Double.rounded(): String = String.format(Locale.US, "%.0f", this)

private fun Double.signedNumber(): String = String.format(Locale.US, "%+.0f", this)
