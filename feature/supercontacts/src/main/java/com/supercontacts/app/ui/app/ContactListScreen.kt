@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall", "MissingPermission", "NonObservableLocale")

package com.supercontacts.app.ui.app

import com.gernalix.personalhub.core.ui.HubFeedbackHost
import android.Manifest
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.supercontacts.app.R
import com.supercontacts.app.data.repository.ContactFieldType
import com.supercontacts.app.data.repository.ContactHomeSort
import com.supercontacts.app.data.repository.ContactHomeSortDirection
import com.supercontacts.app.data.repository.ContactHomeSortState
import com.supercontacts.app.data.repository.ContactSearchMatch
import com.supercontacts.app.data.repository.ContactSummary
import com.supercontacts.app.data.repository.ContactTag
import com.supercontacts.app.data.repository.CountryCatalog
import com.supercontacts.app.data.repository.GlobalContactInitiative
import com.supercontacts.app.data.repository.InitiativeType
import com.supercontacts.app.data.repository.SavedSearch
import com.supercontacts.app.ui.contacts.ContactsUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactListScreen(
    snackbarHostState: SnackbarHostState,
    uiState: ContactsUiState,
    onQueryChange: (String) -> Unit,
    onHomeSortCriterionChange: (ContactHomeSort) -> Unit,
    onHomeSortDirectionToggle: () -> Unit,
    onContactClick: (Long) -> Unit,
    onTagClick: (ContactTag) -> Unit,
    onTagFiltersChange: (List<ContactTag>) -> Unit,
    onClearTagFilter: () -> Unit,
    onSaveCurrentSearch: (String) -> Unit,
    onApplySavedSearch: (SavedSearch) -> Unit,
    onCopySavedSearchDeepLink: (SavedSearch) -> Unit,
    onDeleteSavedSearch: (SavedSearch) -> Unit,
    onBulkAddTag: (List<Long>, String) -> Unit,
    onBulkArchive: (List<Long>) -> Unit,
    onManualCallOverlayTest: () -> Unit,
    onScanAllMessagingLinks: () -> Unit,
    onInitiativeClick: (Long, InitiativeType, String) -> Unit,
    onNewContact: () -> Unit,
    onGlobalHistory: () -> Unit,
    onGlobalInitiatives: () -> Unit,
    onErrorDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSortDialog by rememberSaveable { mutableStateOf(false) }
    var showTagFilterDialog by rememberSaveable { mutableStateOf(false) }
    var showSaveSearchDialog by rememberSaveable { mutableStateOf(false) }
    var showSavedSearchesDialog by rememberSaveable { mutableStateOf(false) }
    var showBulkTagDialog by rememberSaveable { mutableStateOf(false) }
    var showBulkArchiveConfirm by rememberSaveable { mutableStateOf(false) }
    var savedSearchPendingDelete by remember { mutableStateOf<SavedSearch?>(null) }
    var selectedContactIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var distanceOrigin by remember { mutableStateOf<Location?>(null) }
    var isResolvingDistanceOrigin by rememberSaveable { mutableStateOf(false) }
    var distanceSortMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val hasDistanceData = uiState.contacts.any { it.addressLatitude != null && it.addressLongitude != null }
    val distancePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.any { it }) {
            isResolvingDistanceOrigin = true
            scope.launch {
                distanceOrigin = getCurrentLocationOnce(context)
                isResolvingDistanceOrigin = false
                if (distanceOrigin == null) {
                    distanceSortMessage = context.getString(R.string.distance_location_unavailable)
                } else {
                    distanceSortMessage = null
                    onHomeSortCriterionChange(ContactHomeSort.DISTANCE)
                }
            }
        } else {
            distanceSortMessage = context.getString(R.string.distance_missing_permission)
        }
    }
    fun selectHomeSort(sort: ContactHomeSort) {
        if (sort != ContactHomeSort.DISTANCE) {
            distanceSortMessage = null
            onHomeSortCriterionChange(sort)
            return
        }
        if (!hasDistanceData) {
            distanceSortMessage = context.getString(R.string.home_sort_distance_unavailable)
            return
        }
        if (!hasLocationPermission(context)) {
            distancePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }
        isResolvingDistanceOrigin = true
        scope.launch {
            distanceOrigin = getCurrentLocationOnce(context)
            isResolvingDistanceOrigin = false
            if (distanceOrigin == null) {
                distanceSortMessage = context.getString(R.string.distance_location_unavailable)
            } else {
                distanceSortMessage = null
                onHomeSortCriterionChange(ContactHomeSort.DISTANCE)
            }
        }
    }
    val displayedContacts = remember(uiState.contacts, uiState.homeSort, distanceOrigin) {
        if (uiState.homeSort.criterion == ContactHomeSort.DISTANCE && distanceOrigin != null) {
            uiState.contacts.sortedByDistance(distanceOrigin!!, uiState.homeSort.direction)
        } else {
            uiState.contacts
        }
    }
    val listState = rememberLazyListState()
    val displayedContactIds = remember(displayedContacts) { displayedContacts.map { it.id } }
    val selectedContactIdSet = remember(selectedContactIds) { selectedContactIds.toSet() }
    val selectedDisplayedCount = displayedContactIds.count { it in selectedContactIdSet }
    val allDisplayedSelected = displayedContactIds.isNotEmpty() && selectedDisplayedCount == displayedContactIds.size
    val showScrollTopButton by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 1 ||
                (listState.firstVisibleItemIndex == 1 && listState.firstVisibleItemScrollOffset > 0) ||
                listState.firstVisibleItemScrollOffset > 160
        }
    }
    LaunchedEffect(displayedContactIds) {
        selectedContactIds = selectedContactIds.filter { it in displayedContactIds }
    }
    LaunchedEffect(uiState.homeSort) {
        listState.requestScrollToItem(0)
        listState.scrollToItem(0)
    }

    Scaffold(
        snackbarHost = { HubFeedbackHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    EmojiToolbarButton(
                        emoji = "🔎",
                        contentDescription = stringResource(R.string.messaging_scan_all),
                        testTag = "home-action-scan-messaging",
                        onClick = onScanAllMessagingLinks,
                    )
                    EmojiToolbarButton(
                        emoji = "↕️",
                        contentDescription = stringResource(
                            R.string.home_sort_button,
                            homeSortLabel(uiState.homeSort.criterion),
                        ),
                        testTag = "home-action-sort",
                        onClick = { showSortDialog = true },
                    )
                    EmojiToolbarButton(
                        emoji = "🤝",
                        contentDescription = stringResource(R.string.history_calendar),
                        testTag = "home-action-initiatives",
                        onClick = onGlobalInitiatives,
                    )
                    EmojiToolbarButton(
                        emoji = "🕘",
                        contentDescription = stringResource(R.string.global_history),
                        testTag = "home-action-history",
                        onClick = onGlobalHistory,
                    )
                    EmojiToolbarButton(
                        emoji = "➕",
                        contentDescription = stringResource(R.string.new_contact),
                        testTag = "new-contact-action",
                        onClick = onNewContact,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ErrorMessage(uiState.errorMessage, onErrorDismiss)
            if (uiState.activeTagFilters.isNotEmpty()) {
                ActiveTagFilter(tags = uiState.activeTagFilters, onClear = onClearTagFilter)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.gernalix.personalhub.core.ui.HubSearchField(
                    value = uiState.searchQuery,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.search),
                    clearButtonTestTag = "home-filter-reset",
                )
                TagFilterButton(
                    activeCount = uiState.activeTagFilters.size,
                    onClick = { showTagFilterDialog = true },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_visible_contacts_count, displayedContacts.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home-visible-contact-count"),
                )
                TextButton(
                    onClick = { showSaveSearchDialog = true },
                    enabled = uiState.searchQuery.isNotBlank() || uiState.activeTagFilters.isNotEmpty(),
                    modifier = Modifier.testTag("home-save-search"),
                ) {
                    Text(stringResource(R.string.saved_search_save))
                }
                TextButton(
                    onClick = onManualCallOverlayTest,
                    modifier = Modifier.testTag("home-call-overlay-test"),
                ) {
                    Text(stringResource(R.string.call_overlay_test))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeSortStatus(
                    sort = uiState.homeSort,
                    onToggleDirection = onHomeSortDirectionToggle,
                    modifier = Modifier.weight(1f),
                )
                SavedSearchesEntry(
                    count = uiState.savedSearches.size,
                    onClick = { showSavedSearchesDialog = true },
                    modifier = Modifier.weight(1f),
                )
            }
            HomeBulkActionRow(
                selectedCount = selectedContactIds.size,
                allDisplayedSelected = allDisplayedSelected,
                displayedCount = displayedContacts.size,
                onToggleAll = {
                    selectedContactIds = if (allDisplayedSelected) {
                        emptyList()
                    } else {
                        displayedContactIds
                    }
                },
                onAddTag = { showBulkTagDialog = true },
                onArchive = { showBulkArchiveConfirm = true },
            )
            distanceSortMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isResolvingDistanceOrigin) {
                Text(
                    text = stringResource(R.string.distance_calculating),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (displayedContacts.isEmpty()) {
                EmptyContactsState(
                    isSearching = uiState.searchQuery.isNotBlank() || uiState.activeTagFilters.isNotEmpty(),
                    onNewContact = onNewContact,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("contacts-list"),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(displayedContacts, key = { contact -> contact.id }) { contact ->
                        ContactRow(
                            contact = contact,
                            selected = contact.id in selectedContactIdSet,
                            searchQuery = uiState.searchQuery,
                            homeSort = uiState.homeSort,
                            distanceOrigin = distanceOrigin,
                            onClick = {
                                if (selectedContactIds.isEmpty()) {
                                    onContactClick(contact.id)
                                } else {
                                    selectedContactIds = selectedContactIds.toggleId(contact.id)
                                }
                            },
                            onSelectionChange = {
                                selectedContactIds = selectedContactIds.toggleId(contact.id)
                            },
                            onTagClick = onTagClick,
                            onInitiativeClick = onInitiativeClick,
                        )
                    }
                }
            }
        }
        if (showScrollTopButton) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Button(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.testTag("home-scroll-top"),
                ) {
                    Text("↑")
                }
            }
        }
    }

    if (showSortDialog) {
        HomeSortDialog(
            selectedSort = uiState.homeSort,
            distanceEnabled = hasDistanceData,
            onDismiss = { showSortDialog = false },
            onSelect = { sort ->
                showSortDialog = false
                selectHomeSort(sort)
            },
        )
    }
    if (showTagFilterDialog) {
        HomeTagFilterDialog(
            availableTags = uiState.availableHomeTags,
            selectedTags = uiState.activeTagFilters,
            onDismiss = { showTagFilterDialog = false },
            onClear = {
                onClearTagFilter()
                showTagFilterDialog = false
            },
            onApply = { tags ->
                onTagFiltersChange(tags)
                showTagFilterDialog = false
            },
        )
    }
    if (showSaveSearchDialog) {
        SaveSearchDialog(
            onDismiss = { showSaveSearchDialog = false },
            onSave = { title ->
                onSaveCurrentSearch(title)
                showSaveSearchDialog = false
            },
        )
    }
    if (showBulkTagDialog) {
        BulkTagDialog(
            selectedCount = selectedContactIds.size,
            onDismiss = { showBulkTagDialog = false },
            onConfirm = { tagName ->
                onBulkAddTag(selectedContactIds, tagName)
                selectedContactIds = emptyList()
                showBulkTagDialog = false
            },
        )
    }
    if (showBulkArchiveConfirm) {
        BulkArchiveConfirmDialog(
            selectedCount = selectedContactIds.size,
            onDismiss = { showBulkArchiveConfirm = false },
            onConfirm = {
                onBulkArchive(selectedContactIds)
                selectedContactIds = emptyList()
                showBulkArchiveConfirm = false
            },
        )
    }
    if (showSavedSearchesDialog) {
        SavedSearchesDialog(
            searches = uiState.savedSearches,
            onDismiss = { showSavedSearchesDialog = false },
            onApply = { search ->
                onApplySavedSearch(search)
                showSavedSearchesDialog = false
            },
            onCopyDeepLink = onCopySavedSearchDeepLink,
            onDeleteRequest = { search -> savedSearchPendingDelete = search },
        )
    }
    savedSearchPendingDelete?.let { search ->
        DeleteSavedSearchDialog(
            search = search,
            onDismiss = { savedSearchPendingDelete = null },
            onConfirm = {
                onDeleteSavedSearch(search)
                savedSearchPendingDelete = null
            },
        )
    }
}

@Composable
internal fun EmojiToolbarButton(
    emoji: String,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .testTag(testTag)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
    ) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun HomeSortStatus(
    sort: ContactHomeSortState,
    onToggleDirection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                R.string.home_sort_status,
                homeSortLabel(sort.criterion),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onToggleDirection,
            modifier = Modifier.testTag("home-sort-direction-toggle"),
        ) {
            Text(
                stringResource(
                    if (sort.direction == ContactHomeSortDirection.ASC) {
                        R.string.sort_asc
                    } else {
                        R.string.sort_desc
                    },
                ),
            )
        }
    }
}

@Composable
private fun HomeBulkActionRow(
    selectedCount: Int,
    allDisplayedSelected: Boolean,
    displayedCount: Int,
    onToggleAll: () -> Unit,
    onAddTag: () -> Unit,
    onArchive: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home-bulk-actions"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onToggleAll,
            enabled = displayedCount > 0,
            modifier = Modifier.testTag("home-select-all-toggle"),
        ) {
            Text(
                stringResource(
                    if (allDisplayedSelected) {
                        R.string.bulk_deselect_all
                    } else {
                        R.string.bulk_select_all
                    },
                ),
            )
        }
        Text(
            text = stringResource(R.string.bulk_selected_count, selectedCount),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onAddTag,
            enabled = selectedCount > 0,
            modifier = Modifier.testTag("home-bulk-tag"),
        ) {
            Text(stringResource(R.string.bulk_add_tag))
        }
        TextButton(
            onClick = onArchive,
            enabled = selectedCount > 0,
            modifier = Modifier.testTag("home-bulk-archive"),
        ) {
            Text(stringResource(R.string.bulk_archive))
        }
    }
}

@Composable
private fun BulkTagDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var tagName by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(tagName) },
                enabled = selectedCount > 0 && tagName.trim().isNotBlank(),
                modifier = Modifier.testTag("bulk-tag-confirm"),
            ) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.bulk_add_tag_title, selectedCount)) },
        text = {
            OutlinedTextField(
                value = tagName,
                onValueChange = { tagName = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bulk-tag-input"),
                label = { Text(stringResource(R.string.tag)) },
                singleLine = true,
            )
        },
    )
}

@Composable
private fun BulkArchiveConfirmDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selectedCount > 0,
                modifier = Modifier.testTag("bulk-archive-confirm"),
            ) {
                Text(stringResource(R.string.bulk_archive))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.bulk_archive_confirm_title, selectedCount)) },
        text = { Text(stringResource(R.string.bulk_archive_confirm_body)) },
    )
}

@Composable
private fun HomeSortDialog(
    selectedSort: ContactHomeSortState,
    distanceEnabled: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ContactHomeSort) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.home_sort_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ContactHomeSort.values().forEach { sort ->
                    TextButton(
                        onClick = { onSelect(sort) },
                        enabled = sort != ContactHomeSort.DISTANCE || distanceEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home-sort-${sort.name.lowercase()}"),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(homeSortLabel(sort))
                            if (sort == selectedSort.criterion) {
                                Text("*")
                            }
                        }
                    }
                }
                if (!distanceEnabled) {
                    Text(
                        text = stringResource(R.string.home_sort_distance_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun homeSortLabel(sort: ContactHomeSort): String =
    stringResource(
        when (sort) {
            ContactHomeSort.NAME -> R.string.home_sort_name
            ContactHomeSort.UPDATED -> R.string.home_sort_updated
            ContactHomeSort.LAST_INTERACTION -> R.string.home_sort_last_interaction
            ContactHomeSort.OPEN_COUNT -> R.string.home_sort_open_count
            ContactHomeSort.CREATED -> R.string.home_sort_created
            ContactHomeSort.INITIATIVE_COUNT -> R.string.home_sort_initiative_count
            ContactHomeSort.DISTANCE -> R.string.home_sort_distance
            ContactHomeSort.COMPANY -> R.string.home_sort_company
        },
    )

@Composable
private fun InitiativeHomeSection(
    initiatives: List<GlobalContactInitiative>,
    onOpen: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.initiative),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onOpen) {
                    Text(stringResource(R.string.open))
                }
            }
            if (initiatives.isEmpty()) {
                Text(
                    text = stringResource(R.string.initiative_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                initiatives.forEach { item ->
                    InitiativePreviewRow(item)
                }
            }
        }
    }
}

@Composable
private fun InitiativePreviewRow(item: GlobalContactInitiative) {
    Text(
        text = initiativeEventText(
            contactName = item.contactDisplayName,
            type = item.initiative.initiativeType,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = initiativeColor(item.initiative.initiativeType),
    )
}

@Composable
private fun TagFilterButton(
    activeCount: Int,
    onClick: () -> Unit,
) {
    val buttonDescription = if (activeCount > 0) {
        stringResource(R.string.home_tag_filter_active_content_description, activeCount)
    } else {
        stringResource(R.string.home_tag_filter_content_description)
    }
    BadgedBox(
        badge = {
            if (activeCount > 0) {
                Badge {
                    Text(activeCount.toString())
                }
            }
        },
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .testTag("home-tag-filter-button")
                .semantics {
                    contentDescription = buttonDescription
                    role = Role.Button
                },
        ) {
            Text(
                text = "#",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeTagFilterDialog(
    availableTags: List<ContactTag>,
    selectedTags: List<ContactTag>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onApply: (List<ContactTag>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedIds by rememberSaveable(selectedTags) { mutableStateOf(selectedTags.map { it.id }) }
    val filteredTags = remember(availableTags, query) {
        val cleanedQuery = query.trim()
        availableTags
            .distinctBy { it.id }
            .filter { tag -> cleanedQuery.isBlank() || tag.name.contains(cleanedQuery, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_tag_filter_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                com.gernalix.personalhub.core.ui.HubSearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home-tag-filter-search"),
                    label = stringResource(R.string.home_tag_filter_search),
                )
                if (availableTags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_tag_filter_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (filteredTags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_tag_filter_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        filteredTags.forEach { tag ->
                            val selected = tag.id in selectedIds
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedIds = if (selected) {
                                        selectedIds - tag.id
                                    } else {
                                        (selectedIds + tag.id).distinct()
                                    }
                                },
                                label = { Text(tag.name) },
                                modifier = Modifier.testTag("home-tag-chip-${tag.id}"),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(availableTags.filter { it.id in selectedIds })
                },
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onClear,
                    enabled = selectedTags.isNotEmpty() || selectedIds.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.clear))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun SaveSearchDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSave(title) },
                enabled = title.trim().isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.saved_search_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("saved-search-title-input"),
                label = { Text(stringResource(R.string.saved_search_title_label)) },
                singleLine = true,
            )
        },
    )
}

@Composable
private fun SavedSearchesSection(
    searches: List<SavedSearch>,
    onApply: (SavedSearch) -> Unit,
    onCopyDeepLink: (SavedSearch) -> Unit,
    onDeleteRequest: (SavedSearch) -> Unit,
) {
    if (searches.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        searches.forEach { search ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("saved-search-${search.publicId}"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onApply(search) },
                    modifier = Modifier.weight(1f),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(search.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = savedSearchSummary(search),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = { onCopyDeepLink(search) },
                    modifier = Modifier.testTag("saved-search-copy-${search.publicId}"),
                ) {
                    Text("📎")
                }
                IconButton(
                    onClick = { onDeleteRequest(search) },
                    modifier = Modifier.testTag("saved-search-delete-${search.publicId}"),
                ) {
                    Text("🗑")
                }
            }
        }
    }
}

@Composable
private fun SavedSearchesEntry(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag("home-saved-searches-entry"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.saved_searches_entry))
            Text(stringResource(R.string.saved_searches_count, count))
        }
    }
}

@Composable
private fun SavedSearchesDialog(
    searches: List<SavedSearch>,
    onDismiss: () -> Unit,
    onApply: (SavedSearch) -> Unit,
    onCopyDeepLink: (SavedSearch) -> Unit,
    onDeleteRequest: (SavedSearch) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        title = { Text(stringResource(R.string.saved_searches)) },
        text = {
            if (searches.isEmpty()) {
                Text(stringResource(R.string.saved_searches_empty))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SavedSearchesSection(
                        searches = searches,
                        onApply = onApply,
                        onCopyDeepLink = onCopyDeepLink,
                        onDeleteRequest = onDeleteRequest,
                    )
                }
            }
        },
    )
}

@Composable
private fun DeleteSavedSearchDialog(
    search: SavedSearch,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.saved_search_delete_confirm_title)) },
        text = { Text(stringResource(R.string.saved_search_delete_confirm_body, search.title)) },
    )
}

@Composable
private fun savedSearchSummary(search: SavedSearch): String {
    val tags = search.tags.joinToString { it.name }
    return when {
        search.query.isNotBlank() && tags.isNotBlank() ->
            stringResource(R.string.saved_search_summary_query_tags, search.query, tags)
        search.query.isNotBlank() -> stringResource(R.string.saved_search_summary_query, search.query)
        tags.isNotBlank() -> stringResource(R.string.saved_search_summary_tags, tags)
        else -> stringResource(R.string.saved_search_summary_empty)
    }
}

@Composable
private fun ActiveTagFilter(
    tags: List<ContactTag>,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.filtered_by, tags.joinToString { it.name }),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.clear))
            }
        }
    }
}

private fun List<Long>.toggleId(id: Long): List<Long> =
    if (id in this) filterNot { it == id } else this + id

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactRow(
    contact: ContactSummary,
    selected: Boolean,
    searchQuery: String,
    homeSort: ContactHomeSortState,
    distanceOrigin: Location?,
    onClick: () -> Unit,
    onSelectionChange: () -> Unit,
    onTagClick: (ContactTag) -> Unit,
    onInitiativeClick: (Long, InitiativeType, String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("contact-row-${contact.displayName}")
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelectionChange() },
                    modifier = Modifier.testTag("contact-row-select-${contact.id}"),
                )
                ContactAvatar(
                    photoPath = contact.photoPath,
                    ownerId = contact.publicId,
                    displayName = contact.displayName,
                    size = 56.dp,
                    circular = false,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = buildContactTitleText(contact.displayName, searchQuery),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CountryCatalog.flagEmoji(contact.nationalityCountryCode)
                        .takeIf { it.isNotBlank() }
                        ?.let { flag ->
                            Text(
                                text = flag,
                                modifier = Modifier.testTag("contact-row-nationality-flag"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    val metadata = homeSortMetadata(contact, homeSort, distanceOrigin)
                    val visibleSearchMatch = contact.searchMatches.firstOrNull {
                        !it.isSameNameAsTitle(contact.displayName)
                    }
                    if (searchQuery.isNotBlank() && visibleSearchMatch != null) {
                        SearchMatchLine(
                            match = visibleSearchMatch,
                            searchQuery = searchQuery,
                        )
                    } else if (metadata != null) {
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        contact.subtitle?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                InitiativeButtons(
                    contactId = contact.id,
                    contactName = contact.displayName,
                    scope = "home",
                    onInitiativeClick = onInitiativeClick,
                )
            }
            if (contact.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    contact.tags.forEach { tag ->
                        AssistChip(
                            onClick = { onTagClick(tag) },
                            label = { Text(tag.name) },
                        )
                    }
                }
            }
        }
    }
}

private fun ContactSearchMatch.isSameNameAsTitle(displayName: String): Boolean =
    fieldType == ContactFieldType.Name && value.trim().equals(displayName.trim(), ignoreCase = true)

private fun buildContactTitleText(
    title: String,
    searchQuery: String,
) = buildAnnotatedString {
    val start = title.indexOf(searchQuery, ignoreCase = true)
    if (searchQuery.isBlank() || start < 0) {
        append(title)
        return@buildAnnotatedString
    }

    append(title.substring(0, start))
    withStyle(SpanStyle(background = Color.Yellow)) {
        append(title.substring(start, start + searchQuery.length))
    }
    append(title.substring(start + searchQuery.length))
}

@Composable
private fun SearchMatchLine(
    match: ContactSearchMatch,
    searchQuery: String,
) {
    Text(
        text = buildSearchMatchText(
            label = fieldLabel(match.fieldType),
            value = match.value,
            searchQuery = searchQuery,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun buildSearchMatchText(
    label: String,
    value: String,
    searchQuery: String,
) = buildAnnotatedString {
    append(label)
    append(": ")

    val start = value.indexOf(searchQuery, ignoreCase = true)
    if (searchQuery.isBlank() || start < 0) {
        append(value)
        return@buildAnnotatedString
    }

    append(value.substring(0, start))
    withStyle(SpanStyle(background = Color.Yellow)) {
        append(value.substring(start, start + searchQuery.length))
    }
    append(value.substring(start + searchQuery.length))
}

@Composable
private fun homeSortMetadata(
    contact: ContactSummary,
    sort: ContactHomeSortState,
    distanceOrigin: Location?,
): String? =
    when (sort.criterion) {
        ContactHomeSort.NAME -> null
        ContactHomeSort.UPDATED -> stringResource(R.string.home_metadata_modified, formatTimestamp(contact.updatedAt))
        ContactHomeSort.CREATED -> stringResource(R.string.home_metadata_created, formatTimestamp(contact.createdAt))
        ContactHomeSort.LAST_INTERACTION -> contact.lastInteractionAt?.let {
            stringResource(R.string.home_metadata_last_interaction, formatTimestamp(it))
        } ?: stringResource(R.string.home_metadata_last_interaction_missing)

        ContactHomeSort.COMPANY -> contact.company.takeIf { it.isNotBlank() }?.let {
            stringResource(R.string.home_metadata_company, it)
        } ?: stringResource(R.string.home_metadata_company_missing)

        ContactHomeSort.OPEN_COUNT -> stringResource(R.string.home_metadata_open_count, contact.openCount)
        ContactHomeSort.INITIATIVE_COUNT -> stringResource(
            R.string.home_metadata_initiative_count,
            contact.initiativeCount,
        )
        ContactHomeSort.DISTANCE -> distanceMetadata(contact, distanceOrigin)
    }

@Composable
private fun distanceMetadata(contact: ContactSummary, distanceOrigin: Location?): String =
    when {
        contact.addressLatitude == null || contact.addressLongitude == null ->
            stringResource(R.string.home_metadata_distance_missing)
        distanceOrigin == null -> stringResource(R.string.home_metadata_distance_unavailable)
        else -> {
            val contactLocation = Location("contact").apply {
                latitude = contact.addressLatitude
                longitude = contact.addressLongitude
            }
            val distanceKm = distanceOrigin.distanceTo(contactLocation) / 1000.0
            stringResource(R.string.home_metadata_distance_value, formatDistanceKm(distanceKm))
        }
    }

@Composable
private fun EmptyContactsState(
    isSearching: Boolean,
    onNewContact: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isSearching) {
            Text(stringResource(R.string.no_contacts_match))
        } else {
            Text(
                text = stringResource(R.string.no_contacts_yet),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.create_first_contact))
            Button(onClick = onNewContact) {
                Text(stringResource(R.string.new_contact))
            }
        }
    }
}
