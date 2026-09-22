@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall", "MissingPermission", "NonObservableLocale")

package com.supercontacts.app.ui.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import com.supercontacts.app.CallOverlayPermission
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.supercontacts.app.R
import com.supercontacts.app.data.repository.AddressSuggestion
import com.supercontacts.app.data.repository.AppContainer
import com.supercontacts.app.data.repository.ContactDetail
import com.supercontacts.app.data.repository.ContactDeepLink
import com.gernalix.personalhub.contracts.database.DataExplorerContract
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.hubcontext.HubContextLinks
import com.supercontacts.app.data.repository.ContactDuplicateCandidate
import com.supercontacts.app.data.repository.ContactDuplicateReason
import com.supercontacts.app.data.repository.ContactEvent
import com.supercontacts.app.data.repository.ContactEventType
import com.supercontacts.app.data.repository.ContactFieldDescriptor
import com.supercontacts.app.data.repository.ContactFieldSuggestion
import com.supercontacts.app.data.repository.ContactFieldTimestamp
import com.supercontacts.app.data.repository.ContactFieldType
import com.supercontacts.app.data.repository.ContactHomeSort
import com.supercontacts.app.data.repository.ContactHomeSortDirection
import com.supercontacts.app.data.repository.ContactHomeSortState
import com.supercontacts.app.data.repository.ContactInitiative
import com.supercontacts.app.data.repository.ContactInput
import com.supercontacts.app.data.repository.ContactMessagingLink
import com.gernalix.personalhub.core.ui.photo.rememberHubPhotoPicker
import com.supercontacts.app.data.repository.ContactSearchMatch
import com.supercontacts.app.data.repository.ContactStats
import com.supercontacts.app.data.repository.ContactSummary
import com.supercontacts.app.data.repository.ContactTag
import com.supercontacts.app.data.repository.CountryCatalog
import com.supercontacts.app.data.repository.CountryOption
import com.supercontacts.app.data.repository.GlobalContactEvent
import com.supercontacts.app.data.repository.GlobalContactInitiative
import com.supercontacts.app.data.repository.HistoryCalendarDaySummary
import com.supercontacts.app.data.repository.HistoryCalendarState
import com.supercontacts.app.data.repository.HistoryDateRange
import com.supercontacts.app.data.repository.HistoryRangeDetails
import com.supercontacts.app.data.repository.InitiativeCalendarState
import com.supercontacts.app.data.repository.InitiativeDayDetails
import com.supercontacts.app.data.repository.InitiativeDaySummary
import com.supercontacts.app.data.repository.InitiativeType
import com.supercontacts.app.data.repository.MessagingLinkVerificationStatus
import com.supercontacts.app.data.repository.MessagingPlatform
import com.supercontacts.app.data.repository.ResolvedAddress
import com.supercontacts.app.data.repository.SavedSearch
import com.supercontacts.app.ui.contacts.ContactTimeFormatter
import com.supercontacts.app.ui.contacts.ContactsUiState
import com.supercontacts.app.ui.contacts.ContactsViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class CallOverlaySignal(
    val sequence: Long,
    val phoneNumber: String?,
    val isManualTest: Boolean = false,
)

@Composable
fun SuperContactsApp(
    launchIntent: Intent? = null,
    callOverlaySignal: CallOverlaySignal? = null,
    onCallPermissionsReady: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val viewModel: ContactsViewModel = viewModel(
        factory = ContactsViewModel.Factory(
            repository = AppContainer.contactsRepository(context),
            homePreferencesStore = AppContainer.homePreferencesStore(context),
            addressAutocompleteRepository = AppContainer.addressAutocompleteRepository(context),
            contentResolver = context.contentResolver,
        ),
    )
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val appScope = rememberCoroutineScope()
    var activeCallOverlay by remember { mutableStateOf<CallOverlaySignal?>(null) }
    var activeCallContactId by remember { mutableStateOf<Long?>(null) }
    var showOverlayPermissionRequest by rememberSaveable { mutableStateOf(false) }
    var restrictedSettingsPrimerCompleted by rememberSaveable { mutableStateOf(false) }
    val restrictedSettingsPrimerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        restrictedSettingsPrimerCompleted = true
        showOverlayPermissionRequest = !CallOverlayPermission.canDrawOverlays(context)
    }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.any { it }) {
            onCallPermissionsReady()
            showOverlayPermissionRequest = !CallOverlayPermission.canDrawOverlays(context)
        }
    }

    var selectedContactId by rememberSaveable { mutableStateOf<Long?>(null) }
    var isCreating by rememberSaveable { mutableStateOf(false) }
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var isViewingHistory by rememberSaveable { mutableStateOf(false) }
    var isViewingGlobalHistory by rememberSaveable { mutableStateOf(false) }
    var isViewingContactInitiatives by rememberSaveable { mutableStateOf(false) }
    var isViewingGlobalInitiatives by rememberSaveable { mutableStateOf(false) }
    var timestampEditEvent by remember { mutableStateOf<ContactEvent?>(null) }
    var handledDeepLink by rememberSaveable { mutableStateOf<String?>(null) }

    val openContact: (Long) -> Unit = { contactId ->
        selectedContactId = contactId
        isCreating = false
        isEditing = false
        isViewingHistory = false
        isViewingGlobalHistory = false
        isViewingContactInitiatives = false
        isViewingGlobalInitiatives = false
        viewModel.recordContactOpen(contactId)
    }

    LaunchedEffect(Unit) {
        if (hasCallStatePermission(context)) {
            onCallPermissionsReady()
            showOverlayPermissionRequest = !CallOverlayPermission.canDrawOverlays(context)
        } else {
            callPermissionLauncher.launch(callPermissions())
        }
    }

    LaunchedEffect(callOverlaySignal?.sequence) {
        val signal = callOverlaySignal ?: return@LaunchedEffect
        activeCallOverlay = signal
        activeCallContactId = null
        val number = signal.phoneNumber.orEmpty()
        if (number.isNotBlank()) {
            viewModel.findContactIdByPhone(number) { contactId ->
                activeCallContactId = contactId
            }
        }
    }

    if (showOverlayPermissionRequest) {
        val requiresRestrictedSettingsPrimer =
            !restrictedSettingsPrimerCompleted &&
                CallOverlayPermission.requiresRestrictedSettingsPrimer(context)
        AlertDialog(
            onDismissRequest = { showOverlayPermissionRequest = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOverlayPermissionRequest = false
                        if (requiresRestrictedSettingsPrimer) {
                            restrictedSettingsPrimerLauncher.launch(
                                CallOverlayPermission.appInfoIntent(context),
                            )
                        } else {
                            context.startActivity(CallOverlayPermission.settingsIntent(context))
                        }
                    },
                    modifier = Modifier.testTag(
                        if (requiresRestrictedSettingsPrimer) {
                            "call-overlay-permission-open-app-info"
                        } else {
                            "call-overlay-permission-open-settings"
                        },
                    ),
                ) {
                    Text(
                        stringResource(
                            if (requiresRestrictedSettingsPrimer) {
                                R.string.call_overlay_permission_open_app_info
                            } else {
                                R.string.call_overlay_permission_open_settings
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPermissionRequest = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = {
                Text(
                    stringResource(
                        if (requiresRestrictedSettingsPrimer) {
                            R.string.call_overlay_permission_restricted_title
                        } else {
                            R.string.call_overlay_permission_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (requiresRestrictedSettingsPrimer) {
                            R.string.call_overlay_permission_restricted_body
                        } else {
                            R.string.call_overlay_permission_body
                        },
                    ),
                )
            },
        )
    }

    val pickDetailPhoto = rememberHubPhotoPicker { selected ->
        val contactId = selectedContactId ?: return@rememberHubPhotoPicker
        viewModel.saveContactPhotoForContact(contactId, Uri.parse(selected.reference)) { path ->
            viewModel.updateContactPhoto(contactId, path)
        }
    }

    LaunchedEffect(selectedContactId) {
        val contactId = selectedContactId
        if (contactId == null) {
            viewModel.clearDetail()
            viewModel.clearContactStats()
            viewModel.clearHistory()
            viewModel.clearContactInitiatives()
        } else {
            viewModel.observeContact(contactId)
            viewModel.observeContactStats(contactId)
        }
    }

    LaunchedEffect(selectedContactId, isViewingHistory) {
        val contactId = selectedContactId
        if (contactId != null && isViewingHistory) {
            viewModel.observeContactHistory(contactId)
        } else {
            viewModel.clearHistory()
        }
    }

    LaunchedEffect(selectedContactId, isViewingContactInitiatives) {
        val contactId = selectedContactId
        if (contactId != null && isViewingContactInitiatives) {
            viewModel.observeContactInitiatives(contactId)
        } else {
            viewModel.clearContactInitiatives()
        }
    }

    LaunchedEffect(isViewingGlobalHistory) {
        if (isViewingGlobalHistory) {
            viewModel.observeGlobalHistory()
        } else {
            viewModel.clearGlobalHistory()
        }
    }

    LaunchedEffect(isViewingGlobalInitiatives) {
        if (isViewingGlobalInitiatives) {
            viewModel.observeHistoryCalendar()
            viewModel.observeHistoryRange()
        } else {
            viewModel.clearHistoryCalendar()
            viewModel.clearSelectedInitiativeDay()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initiativeUndoRequests.collect { request ->
            val result = snackbarHostState.showSnackbar(
                message = context.getString(
                    when (request.initiativeType) {
                        InitiativeType.SELF -> R.string.initiative_saved_self_dynamic
                        InitiativeType.OTHER -> R.string.initiative_saved_other_dynamic
                    },
                    request.contactName,
                ),
                actionLabel = context.getString(R.string.undo),
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoInitiative(request.initiativeId)
            }
        }
    }

    LaunchedEffect(launchIntent?.dataString) {
        val dataString = launchIntent?.dataString ?: return@LaunchedEffect
        val deepLinkGeneration = dataString
        if (handledDeepLink == deepLinkGeneration) return@LaunchedEffect
        handledDeepLink = deepLinkGeneration
        val savedSearchPublicId = ContactDeepLink.parseSavedSearch(dataString)
        if (savedSearchPublicId != null) {
            viewModel.findSavedSearchByPublicId(savedSearchPublicId) { savedSearch ->
                if (savedSearch == null) {
                    appScope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.deep_link_search_missing))
                    }
                } else {
                    viewModel.applySavedSearch(savedSearch)
                    selectedContactId = null
                    isCreating = false
                    isEditing = false
                    isViewingHistory = false
                    isViewingGlobalHistory = false
                    isViewingContactInitiatives = false
                    isViewingGlobalInitiatives = false
                }
            }
            return@LaunchedEffect
        }
        val publicId = ContactDeepLink.parse(dataString)
        if (publicId == null) {
            snackbarHostState.showSnackbar(context.getString(R.string.deep_link_invalid))
            return@LaunchedEffect
        }
        viewModel.findContactIdByPublicId(publicId) { contactId ->
            if (contactId == null) {
                viewModel.clearDetail()
                selectedContactId = null
                isCreating = false
                isEditing = false
                isViewingHistory = false
                isViewingGlobalHistory = false
                isViewingContactInitiatives = false
                isViewingGlobalInitiatives = false
                viewModel.clearError()
                appScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.deep_link_contact_missing))
                }
            } else {
                openContact(contactId)
            }
        }
    }

    val linkedPlaceId = uiState.detail?.addressPlaceId
    LaunchedEffect(selectedContactId, isEditing, linkedPlaceId) {
        linkedPlaceId?.let(viewModel::resolveLinkedPlace)
    }

    when {
        isViewingGlobalInitiatives -> HistoryCalendarScreen(
            snackbarHostState = snackbarHostState,
            calendarState = uiState.historyCalendar,
            selectedRange = uiState.selectedHistoryRange,
            rangeDetails = uiState.historyRangeDetails,
            includeContact = uiState.historyIncludeContact,
            includeField = uiState.historyIncludeField,
            includeInitiative = uiState.historyIncludeInitiative,
            onPreviousMonth = viewModel::previousHistoryMonth,
            onNextMonth = viewModel::nextHistoryMonth,
            onDayClick = viewModel::selectHistoryDate,
            onIncludeContactChange = viewModel::setHistoryIncludeContact,
            onIncludeFieldChange = viewModel::setHistoryIncludeField,
            onIncludeInitiativeChange = viewModel::setHistoryIncludeInitiative,
            onEventClick = { event ->
                event.contactId.takeIf { it > 0L }?.let(openContact)
            },
            onTimestampClick = { event -> timestampEditEvent = event },
            onBack = { isViewingGlobalInitiatives = false },
        )

        isViewingGlobalHistory -> GlobalHistoryScreen(
            snackbarHostState = snackbarHostState,
            events = uiState.globalHistoryEvents,
            isAscending = uiState.globalHistoryAscending,
            includeContact = uiState.historyIncludeContact,
            includeField = uiState.historyIncludeField,
            includeInitiative = uiState.historyIncludeInitiative,
            onToggleSort = viewModel::toggleGlobalHistorySort,
            onIncludeContactChange = viewModel::setHistoryIncludeContact,
            onIncludeFieldChange = viewModel::setHistoryIncludeField,
            onIncludeInitiativeChange = viewModel::setHistoryIncludeInitiative,
            onEventClick = { event ->
                event.contactId.takeIf { it > 0L }?.let(openContact)
            },
            onTimestampClick = { event -> timestampEditEvent = event },
            onBack = { isViewingGlobalHistory = false },
        )

        isCreating -> ContactEditScreen(
            snackbarHostState = snackbarHostState,
            title = stringResource(R.string.new_contact),
            initialInput = emptyContactInput(),
            fieldDescriptors = emptyMap(),
            isSaving = uiState.isSaving,
            errorMessage = uiState.errorMessage,
            addressSuggestions = uiState.addressSuggestions,
            addressAutocompleteMessage = uiState.addressAutocompleteMessage,
            linkedPlace = null,
            fieldSuggestions = uiState.fieldSuggestions,
            duplicateCandidates = uiState.duplicateCandidates,
            onErrorDismiss = viewModel::clearError,
            onAddressQueryChange = viewModel::searchAddressSuggestions,
            onAddressSuggestionSelected = viewModel::resolveAddressSuggestion,
            onClearAddressSuggestions = viewModel::clearAddressSuggestions,
            onFieldQueryChange = viewModel::searchFieldSuggestions,
            onClearFieldSuggestions = viewModel::clearFieldSuggestions,
            onDuplicateQueryChange = { input -> viewModel.searchDuplicateCandidates(input, null) },
            onClearDuplicateCandidates = viewModel::clearDuplicateCandidates,
            onStrongDuplicateCheck = { input, onResult ->
                viewModel.findStrongDuplicateBeforeSave(input, null, onResult)
            },
            onDuplicateCandidateClick = { contactId ->
                viewModel.clearDuplicateCandidates()
                openContact(contactId)
            },
            onSavePhoto = viewModel::saveContactPhoto,
            onDeleteUnusedPhoto = viewModel::deleteUnusedContactPhoto,
            onFieldDescriptionChange = viewModel::updateFieldDescription,
            onSave = { input ->
                viewModel.createContact(input) { contactId ->
                    openContact(contactId)
                }
            },
            onCancel = {
                isCreating = false
                viewModel.clearAddressSuggestions()
                ContactFieldType.initialTypes.forEach(viewModel::clearFieldSuggestions)
                viewModel.clearError()
            },
        )

        isEditing && selectedContactId != null -> {
            val detail = uiState.detail
            if (detail == null) {
                LoadingScreen()
            } else {
                ContactEditScreen(
                    snackbarHostState = snackbarHostState,
                    title = stringResource(R.string.edit_contact),
                    initialInput = detail.toInput(),
                    fieldDescriptors = detail.fieldDescriptors,
                    isSaving = uiState.isSaving,
                    errorMessage = uiState.errorMessage,
                    addressSuggestions = uiState.addressSuggestions,
                    addressAutocompleteMessage = uiState.addressAutocompleteMessage,
                    linkedPlace = uiState.linkedPlace.takeIf { uiState.linkedPlaceId == detail.addressPlaceId },
                    fieldSuggestions = uiState.fieldSuggestions,
                    duplicateCandidates = uiState.duplicateCandidates,
                    onErrorDismiss = viewModel::clearError,
                    onAddressQueryChange = viewModel::searchAddressSuggestions,
                    onAddressSuggestionSelected = viewModel::resolveAddressSuggestion,
                    onClearAddressSuggestions = viewModel::clearAddressSuggestions,
                    onFieldQueryChange = viewModel::searchFieldSuggestions,
                    onClearFieldSuggestions = viewModel::clearFieldSuggestions,
                    onDuplicateQueryChange = { input ->
                        viewModel.searchDuplicateCandidates(input, detail.id)
                    },
                    onClearDuplicateCandidates = viewModel::clearDuplicateCandidates,
                    onStrongDuplicateCheck = { input, onResult ->
                        viewModel.findStrongDuplicateBeforeSave(input, detail.id, onResult)
                    },
                    onDuplicateCandidateClick = { contactId ->
                        viewModel.clearDuplicateCandidates()
                        openContact(contactId)
                    },
                    onSavePhoto = viewModel::saveContactPhoto,
                    onDeleteUnusedPhoto = viewModel::deleteUnusedContactPhoto,
                    onFieldDescriptionChange = viewModel::updateFieldDescription,
                    onSave = { input ->
                        viewModel.updateContact(detail.id, input) {
                            isEditing = false
                        }
                    },
                    onCancel = {
                        isEditing = false
                        viewModel.clearAddressSuggestions()
                        ContactFieldType.initialTypes.forEach(viewModel::clearFieldSuggestions)
                        viewModel.clearError()
                    },
                )
            }
        }

        selectedContactId != null && isViewingContactInitiatives -> ContactInitiativeScreen(
            snackbarHostState = snackbarHostState,
            detail = uiState.detail,
            initiatives = uiState.contactInitiatives,
            isAscending = uiState.contactInitiativeAscending,
            onToggleSort = viewModel::toggleContactInitiativeSort,
            onBack = { isViewingContactInitiatives = false },
            onInitiativeClick = viewModel::recordInitiative,
            onDeleteInitiative = viewModel::deleteInitiative,
        )

        selectedContactId != null && isViewingHistory -> ContactHistoryScreen(
            snackbarHostState = snackbarHostState,
            detail = uiState.detail,
            events = uiState.historyEvents,
            includeContact = uiState.historyIncludeContact,
            includeField = uiState.historyIncludeField,
            includeInitiative = uiState.historyIncludeInitiative,
            onIncludeContactChange = viewModel::setHistoryIncludeContact,
            onIncludeFieldChange = viewModel::setHistoryIncludeField,
            onIncludeInitiativeChange = viewModel::setHistoryIncludeInitiative,
            onTimestampClick = { event -> timestampEditEvent = event },
            onBack = { isViewingHistory = false },
        )

        selectedContactId != null -> ContactDetailScreen(
            snackbarHostState = snackbarHostState,
            detail = uiState.detail,
            linkedPlace = uiState.linkedPlace.takeIf { uiState.linkedPlaceId == uiState.detail?.addressPlaceId },
            linkedPlaceMessage = uiState.addressAutocompleteMessage,
            contactStats = uiState.contactStats,
            showAddedEdited = uiState.showAddedEdited,
            errorMessage = uiState.errorMessage,
            onBack = {
                selectedContactId = null
                isEditing = false
                isViewingHistory = false
                isViewingGlobalHistory = false
                isViewingContactInitiatives = false
                isViewingGlobalInitiatives = false
                viewModel.clearAddressSuggestions()
                viewModel.clearError()
            },
            onEdit = {
                selectedContactId?.let { contactId ->
                    viewModel.ensureFieldDescriptionTargets(contactId) {
                        isEditing = true
                    }
                }
            },
            onHistory = { isViewingHistory = true },
            onInitiativeHistory = { isViewingContactInitiatives = true },
            onInitiativeClick = viewModel::recordInitiative,
            onFieldOpen = { contactId, fieldType -> viewModel.recordFieldOpen(contactId, fieldType) },
            onFieldDescriptionChange = viewModel::updateFieldDescription,
            onShowAddedEditedChange = viewModel::setShowAddedEdited,
            onDelete = { contactId ->
                viewModel.deleteContact(contactId) {
                    selectedContactId = null
                    isViewingHistory = false
                    isViewingContactInitiatives = false
                }
            },
            onChangePhoto = pickDetailPhoto,
            onCopyDeepLink = { detail ->
                copyContactDeepLink(context, detail)
            },
            onErrorDismiss = viewModel::clearError,
            tagSuggestions = uiState.tagSuggestions,
            onTagQueryChange = viewModel::searchTagSuggestions,
            onClearTagSuggestions = viewModel::clearTagSuggestions,
            onAddTag = { contactId, tagName -> viewModel.addTagToContact(contactId, tagName) },
            onRemoveTag = { contactId, tagId -> viewModel.removeTagFromContact(contactId, tagId) },
            onTagClick = { tag ->
                viewModel.setActiveTagFilter(tag)
                selectedContactId = null
                isEditing = false
                isViewingHistory = false
                isViewingContactInitiatives = false
            },
            onScanMessagingLinks = viewModel::scanMessagingLinks,
            onConfirmMessagingLink = viewModel::confirmMessagingLink,
            onRejectMessagingLink = viewModel::rejectMessagingLink,
            onResetMessagingLink = viewModel::resetMessagingLinkVerification,
        )

        else -> ContactListScreen(
            snackbarHostState = snackbarHostState,
            uiState = uiState,
            onQueryChange = viewModel::setSearchQuery,
            onHomeSortCriterionChange = viewModel::setHomeSortCriterion,
            onHomeSortDirectionToggle = viewModel::toggleHomeSortDirection,
            onContactClick = { contactId ->
                openContact(contactId)
            },
            onTagClick = viewModel::setActiveTagFilter,
            onTagFiltersChange = viewModel::setActiveTagFilters,
            onClearTagFilter = viewModel::clearTagFilter,
            onClearSearchAndTags = viewModel::clearSearchAndTags,
            onSaveCurrentSearch = viewModel::saveCurrentSearch,
            onApplySavedSearch = viewModel::applySavedSearch,
            onCopySavedSearchDeepLink = { search ->
                copySavedSearchDeepLink(context, search)
            },
            onDeleteSavedSearch = viewModel::deleteSavedSearch,
            onBulkAddTag = viewModel::addTagToContacts,
            onBulkArchive = viewModel::archiveContacts,
            onManualCallOverlayTest = {
                val contactWithPhone = uiState.contacts.firstOrNull { it.phone.isNotBlank() }
                activeCallOverlay = CallOverlaySignal(
                    sequence = System.currentTimeMillis(),
                    phoneNumber = contactWithPhone?.phone,
                    isManualTest = true,
                )
                activeCallContactId = contactWithPhone?.id
            },
            onScanAllMessagingLinks = viewModel::scanAllMessagingLinks,
            onInitiativeClick = viewModel::recordInitiative,
            onNewContact = {
                isCreating = true
                isViewingHistory = false
                isViewingGlobalHistory = false
                isViewingContactInitiatives = false
                isViewingGlobalInitiatives = false
                viewModel.clearError()
            },
            onGlobalHistory = {
                selectedContactId = null
                isCreating = false
                isEditing = false
                isViewingHistory = false
                isViewingContactInitiatives = false
                isViewingGlobalHistory = true
                isViewingGlobalInitiatives = false
            },
            onGlobalInitiatives = {
                selectedContactId = null
                isCreating = false
                isEditing = false
                isViewingHistory = false
                isViewingContactInitiatives = false
                isViewingGlobalHistory = false
                isViewingGlobalInitiatives = true
            },
            onErrorDismiss = viewModel::clearError,
        )
    }

    activeCallOverlay?.let { signal ->
        CallOverlay(
            signal = signal,
            matchedContactId = activeCallContactId,
            onDismiss = { activeCallOverlay = null },
            onOpenContact = { contactId ->
                activeCallOverlay = null
                openContact(contactId)
            },
        )
    }

    timestampEditEvent?.let { event ->
        HistoryTimestampDialog(
            initialTimestamp = event.occurredAt,
            onDismiss = { timestampEditEvent = null },
            onConfirm = { timestampUtc ->
                viewModel.updateHistoryTimestamp(event, timestampUtc)
                timestampEditEvent = null
            },
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactListScreen(
    snackbarHostState: SnackbarHostState,
    uiState: ContactsUiState,
    onQueryChange: (String) -> Unit,
    onHomeSortCriterionChange: (ContactHomeSort) -> Unit,
    onHomeSortDirectionToggle: () -> Unit,
    onContactClick: (Long) -> Unit,
    onTagClick: (ContactTag) -> Unit,
    onTagFiltersChange: (List<ContactTag>) -> Unit,
    onClearTagFilter: () -> Unit,
    onClearSearchAndTags: () -> Unit,
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    EmojiToolbarButton(
                        emoji = "🗃️",
                        contentDescription = "Datasette",
                        testTag = "home-action-data-explorer",
                        onClick = {
                            context.startActivity(
                                DataExplorerContract.intent(context.packageName, "contacts"),
                            )
                        },
                    )
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
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    trailingIcon = {
                        if (uiState.searchQuery.isNotBlank() || uiState.activeTagFilters.isNotEmpty()) {
                            IconButton(
                                onClick = onClearSearchAndTags,
                                modifier = Modifier.testTag("home-filter-reset"),
                            ) {
                                Text("X")
                            }
                        }
                    },
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
private fun EmojiToolbarButton(
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
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home-tag-filter-search"),
                    label = { Text(stringResource(R.string.home_tag_filter_search)) },
                    singleLine = true,
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

@Composable
private fun CallOverlay(
    signal: CallOverlaySignal,
    matchedContactId: Long?,
    onDismiss: () -> Unit,
    onOpenContact: (Long) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("call-overlay"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (signal.isManualTest) {
                                R.string.call_overlay_manual_title
                            } else {
                                R.string.call_overlay_title
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("call-overlay-close"),
                    ) {
                        Text("X")
                    }
                }
                Text(
                    text = signal.phoneNumber
                        ?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.call_overlay_number_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (matchedContactId == null) {
                    Text(
                        text = stringResource(R.string.call_overlay_contact_not_found),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(
                        onClick = { onOpenContact(matchedContactId) },
                        modifier = Modifier.testTag("call-overlay-open-contact"),
                    ) {
                        Text(stringResource(R.string.call_overlay_open_contact))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactDetailScreen(
    snackbarHostState: SnackbarHostState,
    detail: ContactDetail?,
    linkedPlace: ResolvedAddress?,
    linkedPlaceMessage: String?,
    contactStats: ContactStats?,
    showAddedEdited: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onInitiativeHistory: () -> Unit,
    onInitiativeClick: (Long, InitiativeType, String) -> Unit,
    onFieldOpen: (Long, String) -> Unit,
    onFieldDescriptionChange: (Long, String) -> Unit,
    onShowAddedEditedChange: (Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onChangePhoto: () -> Unit,
    onCopyDeepLink: (ContactDetail) -> Unit,
    onErrorDismiss: () -> Unit,
    tagSuggestions: List<ContactTag>,
    onTagQueryChange: (String) -> Unit,
    onClearTagSuggestions: () -> Unit,
    onAddTag: (Long, String) -> Unit,
    onRemoveTag: (Long, Long) -> Unit,
    onTagClick: (ContactTag) -> Unit,
    onScanMessagingLinks: (Long) -> Unit,
    onConfirmMessagingLink: (Long) -> Unit,
    onRejectMessagingLink: (Long) -> Unit,
    onResetMessagingLink: (Long) -> Unit,
) {
    var showDeleteConfirm by rememberSaveable(detail?.id) { mutableStateOf(false) }
    var showStats by rememberSaveable(detail?.id) { mutableStateOf(false) }
    var showMessagingManager by rememberSaveable(detail?.id) { mutableStateOf(false) }
    val displayedAddress = when {
        detail?.addressPlaceId == null -> detail?.address.orEmpty()
        linkedPlace != null -> linkedPlace.nickname
        else -> stringResource(R.string.address_luoghi_unavailable)
    }
    BackHandler(onBack = onBack)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    EmojiToolbarButton(
                        emoji = "⬅️",
                        contentDescription = stringResource(R.string.back),
                        testTag = "detail-action-back",
                        onClick = onBack,
                    )
                },
                actions = {
                    EmojiToolbarButton(
                        emoji = "🔎",
                        contentDescription = stringResource(R.string.messaging_manage),
                        testTag = "detail-action-manage-messaging",
                        onClick = { showMessagingManager = true },
                        enabled = detail != null,
                    )
                    EmojiToolbarButton(
                        emoji = "📎",
                        contentDescription = stringResource(R.string.copy_deep_link),
                        testTag = "detail-action-copy-deep-link",
                        onClick = { detail?.let(onCopyDeepLink) },
                        enabled = detail != null,
                    )
                    EmojiToolbarButton(
                        emoji = "📈",
                        contentDescription = stringResource(R.string.contact_stats),
                        testTag = "detail-action-stats",
                        onClick = { showStats = true },
                        enabled = detail != null,
                    )
                    EmojiToolbarButton(
                        emoji = "🕘",
                        contentDescription = stringResource(R.string.history),
                        testTag = "detail-action-history",
                        onClick = onHistory,
                        enabled = detail != null,
                    )
                    EmojiToolbarButton(
                        emoji = "🤝",
                        contentDescription = stringResource(R.string.initiative),
                        testTag = "detail-action-initiative",
                        onClick = onInitiativeHistory,
                        enabled = detail != null,
                    )
                    EmojiToolbarButton(
                        emoji = "✏️",
                        contentDescription = stringResource(R.string.edit),
                        testTag = "detail-action-edit",
                        onClick = onEdit,
                        enabled = detail != null,
                    )
                    EmojiToolbarButton(
                        emoji = "🗑️",
                        contentDescription = stringResource(R.string.delete),
                        testTag = "detail-action-delete",
                        onClick = { showDeleteConfirm = true },
                        enabled = detail != null,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .semantics { detail?.publicId?.let { contentDescription = "hub-detail-people/person/$it" } }
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ErrorMessage(errorMessage, onErrorDismiss)
            if (detail == null) {
                LoadingScreenContent()
            } else {
                val detailContext = LocalContext.current

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContactAvatar(
                        photoPath = detail.photoPath,
                        ownerId = detail.publicId,
                        displayName = detail.displayName,
                        size = 96.dp,
                        circular = true,
                        contentDescription = stringResource(R.string.change_photo),
                        modifier = Modifier.testTag("contact-detail-avatar"),
                        onClick = onChangePhoto,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = detail.displayName,
                            modifier = Modifier.testTag("contact-detail-name"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        detail.subtitle?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NationalityFlagText(
                            nationality = detail.nationality,
                            countryCode = detail.nationalityCountryCode,
                        )
                    }
                }

                InitiativeDetailSection(
                    detail = detail,
                    onHistory = onInitiativeHistory,
                    onInitiativeClick = onInitiativeClick,
                )

                HubContextLinks(HubEntityRef("people", "person", detail.publicId))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Checkbox(
                        checked = showAddedEdited,
                        onCheckedChange = onShowAddedEditedChange,
                        modifier = Modifier.testTag("show-added-edited-toggle"),
                    )
                    Text(
                        text = stringResource(R.string.show_added_edited),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                FieldSection(
                    label = stringResource(R.string.name),
                    value = detail.name,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Name],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Name],
                    showTimestamp = showAddedEdited,
                    onOpen = { onFieldOpen(detail.id, ContactFieldType.Name) },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                PhoneFieldSection(
                    label = stringResource(R.string.phone),
                    value = detail.phone,
                    messagingLinks = detail.messagingLinks,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Phone],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Phone],
                    showTimestamp = showAddedEdited,
                    onOpen = { onFieldOpen(detail.id, ContactFieldType.Phone) },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                TelegramFieldSection(
                    value = detail.telegram,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Telegram],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Telegram],
                    showTimestamp = showAddedEdited,
                    onOpen = { onFieldOpen(detail.id, ContactFieldType.Telegram) },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                EmailFieldSection(
                    value = detail.email,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Email],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Email],
                    showTimestamp = showAddedEdited,
                    onOpen = { onFieldOpen(detail.id, ContactFieldType.Email) },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                FieldSection(
                    label = stringResource(R.string.nickname),
                    value = detail.nickname,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Nickname],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Nickname],
                    showTimestamp = showAddedEdited,
                    onOpen = { onFieldOpen(detail.id, ContactFieldType.Nickname) },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                FieldSection(
                    label = stringResource(R.string.company),
                    value = detail.company,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Company],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Company],
                    showTimestamp = showAddedEdited,
                    onOpen = { onFieldOpen(detail.id, ContactFieldType.Company) },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                FieldSection(
                    label = stringResource(R.string.address),
                    value = displayedAddress,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Address],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Address],
                    showTimestamp = showAddedEdited,
                    onOpen = {
                        onFieldOpen(detail.id, ContactFieldType.Address)
                        openMaps(detailContext, detail, linkedPlace)
                    },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                if (detail.addressPlaceId != null && linkedPlace == null) {
                    Text(
                        text = linkedPlaceMessage ?: stringResource(R.string.address_luoghi_unavailable_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                FieldSection(
                    label = stringResource(R.string.address_2),
                    value = detail.address2,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Address2],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Address2],
                    showTimestamp = showAddedEdited,
                    onOpen = { onFieldOpen(detail.id, ContactFieldType.Address2) },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                DistanceSection(detail = detail, linkedPlace = linkedPlace)
                NationalityFieldSection(
                    value = detail.nationality,
                    countryCode = detail.nationalityCountryCode,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Nationality],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Nationality],
                    showTimestamp = showAddedEdited,
                    onOpen = { onFieldOpen(detail.id, ContactFieldType.Nationality) },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                AgeFieldSection(
                    age = detail.age,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Age],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Age],
                    showTimestamp = showAddedEdited,
                    onOpen = { onFieldOpen(detail.id, ContactFieldType.Age) },
                )
                FieldSection(
                    label = stringResource(R.string.note),
                    value = detail.note,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Note],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Note],
                    showTimestamp = showAddedEdited,
                    onOpen = { onFieldOpen(detail.id, ContactFieldType.Note) },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                LinkFieldSection(
                    value = detail.link,
                    timestamp = detail.fieldTimestamps[ContactFieldType.Link],
                    descriptor = detail.fieldDescriptors[ContactFieldType.Link],
                    showTimestamp = showAddedEdited,
                    onOpen = { onFieldOpen(detail.id, ContactFieldType.Link) },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                SocialLinksSection(
                    detail = detail,
                    showTimestamp = showAddedEdited,
                    onFieldOpen = { fieldType -> onFieldOpen(detail.id, fieldType) },
                    onDescriptionChange = onFieldDescriptionChange,
                )
                MessagingLinksSection(
                    links = detail.messagingLinks,
                    onConfirm = onConfirmMessagingLink,
                    onReject = onRejectMessagingLink,
                    onReset = onResetMessagingLink,
                )
                TagsSection(
                    contactId = detail.id,
                    tags = detail.tags,
                    suggestions = tagSuggestions,
                    onTagQueryChange = onTagQueryChange,
                    onClearSuggestions = onClearTagSuggestions,
                    onAddTag = onAddTag,
                    onRemoveTag = onRemoveTag,
                    onTagClick = onTagClick,
                )
            }
        }
    }

    val deleteTarget = detail
    if (showDeleteConfirm && deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(deleteTarget.id)
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.delete_contact_confirm_title)) },
            text = { Text(stringResource(R.string.delete_contact_confirm_body)) },
        )
    }
    if (showStats && detail != null) {
        ContactStatsDialog(
            stats = contactStats,
            onDismiss = { showStats = false },
        )
    }
    if (showMessagingManager && detail != null) {
        MessagingLinksManagerDialog(
            links = detail.messagingLinks,
            onDismiss = { showMessagingManager = false },
            onScan = { onScanMessagingLinks(detail.id) },
            onConfirm = onConfirmMessagingLink,
            onReject = onRejectMessagingLink,
            onReset = onResetMessagingLink,
        )
    }
}

@Composable
private fun ContactStatsDialog(
    stats: ContactStats?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        title = { Text(stringResource(R.string.contact_stats)) },
        text = {
            if (stats == null) {
                Text(stringResource(R.string.loading))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatRow(
                        label = stringResource(R.string.stats_last_modified),
                        value = stats.lastModifiedAt?.let { formatTimestamp(it) }
                            ?: stringResource(R.string.stats_missing),
                    )
                    StatRow(
                        label = stringResource(R.string.stats_last_opened),
                        value = stats.lastOpenedAt?.let { formatTimestamp(it) }
                            ?: stringResource(R.string.stats_missing),
                    )
                    StatRow(
                        label = stringResource(R.string.stats_my_last_initiative),
                        value = stats.lastSelfInitiativeAt?.let { formatTimestamp(it) }
                            ?: stringResource(R.string.stats_missing),
                    )
                    StatRow(
                        label = stringResource(R.string.stats_their_last_initiative),
                        value = stats.lastOtherInitiativeAt?.let { formatTimestamp(it) }
                            ?: stringResource(R.string.stats_missing),
                    )
                    StatRow(
                        label = stringResource(R.string.stats_open_count),
                        value = stringResource(R.string.stats_open_count_value, stats.openCount),
                    )
                    StatRow(
                        label = stringResource(R.string.stats_known_since),
                        value = stats.knownSinceAt?.let { formatKnownDuration(it) }
                            ?: stringResource(R.string.stats_missing),
                    )
                }
            }
        },
    )
}

@Composable
private fun StatRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun InitiativeDetailSection(
    detail: ContactDetail,
    onHistory: () -> Unit,
    onInitiativeClick: (Long, InitiativeType, String) -> Unit,
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
                TextButton(onClick = onHistory) {
                    Text(stringResource(R.string.history))
                }
            }
            InitiativeButtons(
                contactId = detail.id,
                contactName = detail.displayName,
                scope = "detail",
                onInitiativeClick = onInitiativeClick,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactHistoryScreen(
    snackbarHostState: SnackbarHostState,
    detail: ContactDetail?,
    events: List<ContactEvent>,
    includeContact: Boolean,
    includeField: Boolean,
    includeInitiative: Boolean,
    onIncludeContactChange: (Boolean) -> Unit,
    onIncludeFieldChange: (Boolean) -> Unit,
    onIncludeInitiativeChange: (Boolean) -> Unit,
    onTimestampClick: (ContactEvent) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (detail == null) {
            CenteredLoading(innerPadding)
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = detail.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.contact_history),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                HistoryFilterRow(
                    includeContact = includeContact,
                    includeField = includeField,
                    includeInitiative = includeInitiative,
                    onIncludeContactChange = onIncludeContactChange,
                    onIncludeFieldChange = onIncludeFieldChange,
                    onIncludeInitiativeChange = onIncludeInitiativeChange,
                )
            }
            if (events.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_history_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(events, key = { event -> event.id }) { event ->
                    HistoryEventRow(
                        event = event,
                        onTimestampClick = { onTimestampClick(event) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalHistoryScreen(
    snackbarHostState: SnackbarHostState,
    events: List<GlobalContactEvent>,
    isAscending: Boolean,
    includeContact: Boolean,
    includeField: Boolean,
    includeInitiative: Boolean,
    onToggleSort: () -> Unit,
    onIncludeContactChange: (Boolean) -> Unit,
    onIncludeFieldChange: (Boolean) -> Unit,
    onIncludeInitiativeChange: (Boolean) -> Unit,
    onEventClick: (ContactEvent) -> Unit,
    onTimestampClick: (ContactEvent) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.global_history)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onToggleSort) {
                        Text(stringResource(if (isAscending) R.string.sort_asc else R.string.sort_desc))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(
                        if (isAscending) R.string.oldest_events_first else R.string.newest_events_first,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                HistoryFilterRow(
                    includeContact = includeContact,
                    includeField = includeField,
                    includeInitiative = includeInitiative,
                    onIncludeContactChange = onIncludeContactChange,
                    onIncludeFieldChange = onIncludeFieldChange,
                    onIncludeInitiativeChange = onIncludeInitiativeChange,
                )
            }
            if (events.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_history_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(events, key = { item -> item.event.id }) { item ->
                    GlobalHistoryEventRow(
                        item = item,
                        onClick = { onEventClick(item.event) },
                        onTimestampClick = { onTimestampClick(item.event) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryCalendarScreen(
    snackbarHostState: SnackbarHostState,
    calendarState: HistoryCalendarState,
    selectedRange: HistoryDateRange,
    rangeDetails: HistoryRangeDetails?,
    includeContact: Boolean,
    includeField: Boolean,
    includeInitiative: Boolean,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onIncludeContactChange: (Boolean) -> Unit,
    onIncludeFieldChange: (Boolean) -> Unit,
    onIncludeInitiativeChange: (Boolean) -> Unit,
    onEventClick: (ContactEvent) -> Unit,
    onTimestampClick: (ContactEvent) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_calendar)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                HistoryCalendarSection(
                    state = calendarState,
                    selectedRange = selectedRange,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                    onDayClick = onDayClick,
                )
            }
            item {
                HistoryFilterRow(
                    includeContact = includeContact,
                    includeField = includeField,
                    includeInitiative = includeInitiative,
                    onIncludeContactChange = onIncludeContactChange,
                    onIncludeFieldChange = onIncludeFieldChange,
                    onIncludeInitiativeChange = onIncludeInitiativeChange,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.history_selected_range),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = rangeText(selectedRange),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val events = rangeDetails?.events.orEmpty()
            if (rangeDetails == null) {
                item { LoadingScreenContent() }
            } else if (events.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_history_in_range),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(events, key = { item -> item.event.id }) { item ->
                    GlobalHistoryEventRow(
                        item = item,
                        onClick = { onEventClick(item.event) },
                        onTimestampClick = { onTimestampClick(item.event) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactInitiativeScreen(
    snackbarHostState: SnackbarHostState,
    detail: ContactDetail?,
    initiatives: List<ContactInitiative>,
    isAscending: Boolean,
    onToggleSort: () -> Unit,
    onBack: () -> Unit,
    onInitiativeClick: (Long, InitiativeType, String) -> Unit,
    onDeleteInitiative: (Long) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<ContactInitiative?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.initiative_contact)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onToggleSort) {
                        Text(stringResource(if (isAscending) R.string.sort_asc else R.string.sort_desc))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (detail == null) {
            CenteredLoading(innerPadding)
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = detail.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                InitiativeButtons(
                    contactId = detail.id,
                    contactName = detail.displayName,
                    scope = "history",
                    onInitiativeClick = onInitiativeClick,
                )
            }
            if (initiatives.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.initiative_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(initiatives, key = { item -> item.id }) { initiative ->
                    ContactInitiativeRow(
                        initiative = initiative,
                        onDeleteRequest = { deleteTarget = initiative },
                    )
                }
            }
        }
    }
    deleteTarget?.let { initiative ->
        DeleteInitiativeDialog(
            onDismiss = { deleteTarget = null },
            onConfirm = {
                onDeleteInitiative(initiative.id)
                deleteTarget = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalInitiativeScreen(
    snackbarHostState: SnackbarHostState,
    calendarState: InitiativeCalendarState,
    initiatives: List<GlobalContactInitiative>,
    isAscending: Boolean,
    onToggleSort: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (java.time.LocalDate) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.initiative_global)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onToggleSort) {
                        Text(stringResource(if (isAscending) R.string.sort_asc else R.string.sort_desc))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                InitiativeCalendarSection(
                    state = calendarState,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                    onDayClick = onDayClick,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.initiative_history_global_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (initiatives.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.initiative_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(initiatives, key = { item -> item.initiative.id }) { item ->
                    GlobalInitiativeRow(item)
                }
            }
        }
    }
}

@Composable
private fun HistoryCalendarSection(
    state: HistoryCalendarState,
    selectedRange: HistoryDateRange,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (LocalDate) -> Unit,
) {
    val daysByDate = state.days.associateBy { it.date }
    val firstDay = state.month.atDay(1)
    val daysInMonth = state.month.lengthOfMonth()
    val leadingBlanks = firstDay.dayOfWeek.value - 1
    val cells = buildList<LocalDate?> {
        repeat(leadingBlanks) { add(null) }
        repeat(daysInMonth) { add(state.month.atDay(it + 1)) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onPreviousMonth) {
                    Text(stringResource(R.string.previous))
                }
                Text(
                    text = ContactTimeFormatter.formatMonth(state.month),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onNextMonth) {
                    Text(stringResource(R.string.next))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ContactTimeFormatter.weekdayHeaders().forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyVerticalGrid(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                columns = GridCells.Fixed(7),
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(cells.size) { index ->
                    val date = cells[index]
                    if (date == null) {
                        Box(modifier = Modifier.height(40.dp))
                    } else {
                        HistoryDayCell(
                            date = date,
                            summary = daysByDate[date],
                            isSelected = !date.isBefore(selectedRange.startDate) &&
                                !date.isAfter(selectedRange.endDate),
                            onClick = { onDayClick(date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDayCell(
    date: LocalDate,
    summary: HistoryCalendarDaySummary?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .height(72.dp)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .testTag(ContactTimeFormatter.dateCellTag(date))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            summary?.let {
                Text(
                    text = stringResource(R.string.history_day_count, it.eventCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InitiativeCalendarSection(
    state: InitiativeCalendarState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (java.time.LocalDate) -> Unit,
) {
    val daysByDate = state.days.associateBy { it.date }
    val firstDay = state.month.atDay(1)
    val daysInMonth = state.month.lengthOfMonth()
    val leadingBlanks = firstDay.dayOfWeek.value - 1
    val cells = buildList<java.time.LocalDate?> {
        repeat(leadingBlanks) { add(null) }
        repeat(daysInMonth) { add(state.month.atDay(it + 1)) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onPreviousMonth) {
                    Text(stringResource(R.string.previous))
                }
                Text(
                    text = ContactTimeFormatter.formatMonth(state.month),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onNextMonth) {
                    Text(stringResource(R.string.next))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ContactTimeFormatter.weekdayHeaders().forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyVerticalGrid(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                columns = GridCells.Fixed(7),
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(cells.size) { index ->
                    val date = cells[index]
                    if (date == null) {
                        Box(modifier = Modifier.height(40.dp))
                    } else {
                        val summary = daysByDate[date]
                        InitiativeDayCell(
                            date = date,
                            summary = summary,
                            onClick = { onDayClick(date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InitiativeDayCell(
    date: java.time.LocalDate,
    summary: InitiativeDaySummary?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .height(72.dp)
            .testTag(ContactTimeFormatter.dateCellTag(date))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            summary?.let {
                Text(
                    text = "${it.selfCount}/${it.otherCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InitiativeDayViewScreen(
    snackbarHostState: SnackbarHostState,
    dayDetails: InitiativeDayDetails?,
    onBack: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.initiative_day_view)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (dayDetails == null) {
            CenteredLoading(innerPadding)
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = dayDetails.date.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Text(
                    text = stringResource(
                        R.string.initiative_totals,
                        dayDetails.selfCount,
                        dayDetails.otherCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (dayDetails.events.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.initiative_day_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(dayDetails.events, key = { item -> item.initiative.id }) { item ->
                    InitiativeDayEventRow(item)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    contactId: Long,
    tags: List<ContactTag>,
    suggestions: List<ContactTag>,
    onTagQueryChange: (String) -> Unit,
    onClearSuggestions: () -> Unit,
    onAddTag: (Long, String) -> Unit,
    onRemoveTag: (Long, Long) -> Unit,
    onTagClick: (ContactTag) -> Unit,
) {
    var isAdding by rememberSaveable(contactId) { mutableStateOf(false) }
    var tagInput by rememberSaveable(contactId) { mutableStateOf("") }

    fun submitTag(value: String) {
        val cleaned = value.trim()
        if (cleaned.isBlank()) return
        onAddTag(contactId, cleaned)
        tagInput = ""
        isAdding = false
        onClearSuggestions()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.tags),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                val removeTagDescription = stringResource(R.string.remove_tag_content_description, tag.name)
                AssistChip(
                    onClick = { onTagClick(tag) },
                    modifier = Modifier.testTag("contact-tag-chip-${tag.id}"),
                    label = {
                        Text(
                            text = tag.name,
                            fontSize = 28.sp,
                            lineHeight = 32.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 220.dp),
                        )
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { onRemoveTag(contactId, tag.id) },
                            modifier = Modifier
                                .size(32.dp)
                                .semantics {
                                    contentDescription = removeTagDescription
                                    role = Role.Button
                                }
                                .testTag("contact-tag-remove-${tag.id}"),
                        ) {
                            Text(
                                text = "X",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    },
                )
            }
            AssistChip(
                onClick = {
                    isAdding = true
                    onTagQueryChange(tagInput)
                },
                modifier = Modifier.testTag("contact-tag-add"),
                label = {
                    Text(
                        text = "+",
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                    )
                },
            )
        }
        if (isAdding) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { value ->
                        tagInput = value
                        onTagQueryChange(value)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact-tag-input"),
                    label = { Text(stringResource(R.string.tag)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitTag(tagInput) }),
                )
                if (suggestions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        suggestions.forEach { tag ->
                            TextButton(onClick = { submitTag(tag.name) }) {
                                Text(tag.name)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { submitTag(tagInput) },
                    enabled = tagInput.isNotBlank(),
                    modifier = Modifier.testTag("contact-tag-submit"),
                ) {
                    Text(stringResource(R.string.add))
                }
                TextButton(
                    onClick = {
                        isAdding = false
                        tagInput = ""
                        onClearSuggestions()
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactEditScreen(
    snackbarHostState: SnackbarHostState,
    title: String,
    initialInput: ContactInput,
    fieldDescriptors: Map<String, ContactFieldDescriptor>,
    isSaving: Boolean,
    errorMessage: String?,
    addressSuggestions: List<AddressSuggestion>,
    addressAutocompleteMessage: String?,
    linkedPlace: ResolvedAddress?,
    fieldSuggestions: Map<String, List<ContactFieldSuggestion>>,
    duplicateCandidates: List<ContactDuplicateCandidate>,
    onErrorDismiss: () -> Unit,
    onAddressQueryChange: (String) -> Unit,
    onAddressSuggestionSelected: (AddressSuggestion, (ResolvedAddress) -> Unit) -> Unit,
    onClearAddressSuggestions: () -> Unit,
    onFieldQueryChange: (String, String) -> Unit,
    onClearFieldSuggestions: (String) -> Unit,
    onDuplicateQueryChange: (ContactInput) -> Unit,
    onClearDuplicateCandidates: () -> Unit,
    onStrongDuplicateCheck: (ContactInput, (ContactDuplicateCandidate?) -> Unit) -> Unit,
    onDuplicateCandidateClick: (Long) -> Unit,
    onSavePhoto: (Uri, (String) -> Unit) -> Unit,
    onDeleteUnusedPhoto: (String) -> Unit,
    onFieldDescriptionChange: (Long, String) -> Unit,
    onSave: (ContactInput) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(initialInput) { mutableStateOf(titleCaseNameWords(initialInput.name)) }
    var phone by rememberSaveable(initialInput) { mutableStateOf(initialInput.phone) }
    var telegram by rememberSaveable(initialInput) { mutableStateOf(initialInput.telegram) }
    var email by rememberSaveable(initialInput) { mutableStateOf(initialInput.email) }
    var nickname by rememberSaveable(initialInput) { mutableStateOf(initialInput.nickname) }
    var company by rememberSaveable(initialInput) { mutableStateOf(initialInput.company) }
    var note by rememberSaveable(initialInput) { mutableStateOf(initialInput.note) }
    var link by rememberSaveable(initialInput) { mutableStateOf(initialInput.link) }
    var address by rememberSaveable(initialInput) { mutableStateOf(initialInput.address) }
    var addressPlaceId by rememberSaveable(initialInput) { mutableStateOf(initialInput.addressPlaceId) }
    var address2 by rememberSaveable(initialInput) { mutableStateOf(initialInput.address2) }
    var addressLatitude by rememberSaveable(initialInput) { mutableStateOf(initialInput.addressLatitude) }
    var addressLongitude by rememberSaveable(initialInput) { mutableStateOf(initialInput.addressLongitude) }
    var nationality by rememberSaveable(initialInput) { mutableStateOf(initialInput.nationality) }
    var nationalityCountryCode by rememberSaveable(initialInput) { mutableStateOf(initialInput.nationalityCountryCode) }
    var birthDate by rememberSaveable(initialInput) { mutableStateOf(initialInput.birthDate) }
    var manualAge by rememberSaveable(initialInput) { mutableStateOf(initialInput.manualAge) }
    var grindrNick by rememberSaveable(initialInput) { mutableStateOf(initialInput.grindrNick) }
    var instagramUsername by rememberSaveable(initialInput) { mutableStateOf(initialInput.instagramUsername) }
    var facebookUserId by rememberSaveable(initialInput) { mutableStateOf(initialInput.facebookUserId) }
    var photoPath by rememberSaveable(initialInput) { mutableStateOf(initialInput.photoPath) }
    val formScrollState = rememberScrollState()
    val countryOptions = rememberCountryOptions()

    LaunchedEffect(linkedPlace, addressPlaceId) {
        linkedPlace?.takeIf { it.placeId == addressPlaceId }?.let { place ->
            address = place.nickname
        }
    }

    fun revealAutocompleteDropdown() {
        // Keep focus-owned scrolling under Compose/IME control. Suggestions must not
        // issue delayed scrolls that can move the active field off screen.
    }

    val input = ContactInput(
        name = name,
        phone = phone,
        telegram = telegram,
        email = email,
        nickname = nickname,
        company = company,
        note = note,
        link = link,
        address = address.takeIf { addressPlaceId == null }.orEmpty(),
        address2 = address2,
        addressLatitude = addressLatitude.takeIf { addressPlaceId == null },
        addressLongitude = addressLongitude.takeIf { addressPlaceId == null },
        addressPlaceId = addressPlaceId,
        nationality = nationality,
        nationalityCountryCode = nationalityCountryCode,
        birthDate = birthDate,
        manualAge = manualAge,
        grindrNick = grindrNick,
        instagramUsername = instagramUsername,
        facebookUserId = facebookUserId,
        photoPath = photoPath,
    )
    val pickPhoto = rememberHubPhotoPicker { selected ->
        onSavePhoto(Uri.parse(selected.reference)) { path ->
            if (photoPath.isNotBlank() && photoPath != initialInput.photoPath) {
                onDeleteUnusedPhoto(photoPath)
            }
            photoPath = path
        }
    }

    fun cancelEdit() {
        if (photoPath.isNotBlank() && photoPath != initialInput.photoPath) {
            onDeleteUnusedPhoto(photoPath)
        }
        onCancel()
    }

    BackHandler(onBack = ::cancelEdit)

    var pendingSaveInput by remember { mutableStateOf<ContactInput?>(null) }
    var strongDuplicateWarning by remember { mutableStateOf<ContactDuplicateCandidate?>(null) }
    var editingDescription by remember { mutableStateOf<ContactFieldDescriptor?>(null) }

    fun requestSave() {
        onStrongDuplicateCheck(input) { candidate ->
            if (candidate == null) {
                onSave(input)
            } else {
                pendingSaveInput = input
                strongDuplicateWarning = candidate
            }
        }
    }

    LaunchedEffect(input) {
        onDuplicateQueryChange(input)
    }

    DisposableEffect(Unit) {
        onDispose {
            onClearDuplicateCandidates()
        }
    }

    LaunchedEffect(addressSuggestions) {
        if (addressSuggestions.isNotEmpty()) {
            revealAutocompleteDropdown()
        }
    }

    LaunchedEffect(fieldSuggestions) {
        if (fieldSuggestions.values.any { it.isNotEmpty() }) {
            revealAutocompleteDropdown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TextButton(onClick = ::cancelEdit) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Button(
                    onClick = ::requestSave,
                    enabled = input.hasAnyValue() && !isSaving,
                    modifier = Modifier.testTag("contact-form-save"),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(formScrollState)
                .imePadding()
                .padding(16.dp)
                .testTag("contact-form"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ErrorMessage(errorMessage, onErrorDismiss)
            ContactPhotoEditor(
                photoPath = photoPath,
                onPickPhoto = pickPhoto,
                onRemovePhoto = {
                    if (photoPath.isNotBlank() && photoPath != initialInput.photoPath) {
                        onDeleteUnusedPhoto(photoPath)
                    }
                    photoPath = ""
                },
            )
            AutocompleteContactTextField(
                fieldType = ContactFieldType.Name,
                label = stringResource(R.string.name),
                value = name,
                suggestions = fieldSuggestions[ContactFieldType.Name].orEmpty(),
                onValueChange = { name = titleCaseNameWords(it) },
                onQueryChange = onFieldQueryChange,
                onClearSuggestions = onClearFieldSuggestions,
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.Name],
                onDescriptionClick = { editingDescription = it },
            )
            PhoneEditField(
                value = phone,
                suggestions = fieldSuggestions[ContactFieldType.Phone].orEmpty(),
                onValueChange = { phone = it },
                onQueryChange = onFieldQueryChange,
                onClearSuggestions = onClearFieldSuggestions,
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.Phone],
                onDescriptionClick = { editingDescription = it },
            )
            TelegramEditField(
                value = telegram,
                phone = phone,
                onValueChange = { telegram = it },
                descriptor = fieldDescriptors[ContactFieldType.Telegram],
                onDescriptionClick = { editingDescription = it },
            )
            AutocompleteContactTextField(
                fieldType = ContactFieldType.Email,
                label = stringResource(R.string.email),
                value = email,
                suggestions = fieldSuggestions[ContactFieldType.Email].orEmpty(),
                onValueChange = { email = it },
                onQueryChange = onFieldQueryChange,
                onClearSuggestions = onClearFieldSuggestions,
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.Email],
                onDescriptionClick = { editingDescription = it },
            )
            AutocompleteContactTextField(
                fieldType = ContactFieldType.Nickname,
                label = stringResource(R.string.nickname),
                value = nickname,
                suggestions = fieldSuggestions[ContactFieldType.Nickname].orEmpty(),
                onValueChange = { nickname = it },
                onQueryChange = onFieldQueryChange,
                onClearSuggestions = onClearFieldSuggestions,
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.Nickname],
                onDescriptionClick = { editingDescription = it },
            )
            AutocompleteContactTextField(
                fieldType = ContactFieldType.Company,
                label = stringResource(R.string.company),
                value = company,
                suggestions = fieldSuggestions[ContactFieldType.Company].orEmpty(),
                onValueChange = { company = it },
                onQueryChange = onFieldQueryChange,
                onClearSuggestions = onClearFieldSuggestions,
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.Company],
                onDescriptionClick = { editingDescription = it },
            )
            AutocompleteContactTextField(
                fieldType = ContactFieldType.Link,
                label = stringResource(R.string.link),
                value = link,
                suggestions = fieldSuggestions[ContactFieldType.Link].orEmpty(),
                onValueChange = { link = it },
                onQueryChange = onFieldQueryChange,
                onClearSuggestions = onClearFieldSuggestions,
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.Link],
                onDescriptionClick = { editingDescription = it },
            )
            AutocompleteContactTextField(
                fieldType = ContactFieldType.GrindrNick,
                label = stringResource(R.string.grindr_nick),
                value = grindrNick,
                suggestions = fieldSuggestions[ContactFieldType.GrindrNick].orEmpty(),
                onValueChange = { grindrNick = it },
                onQueryChange = onFieldQueryChange,
                onClearSuggestions = onClearFieldSuggestions,
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.GrindrNick],
                onDescriptionClick = { editingDescription = it },
            )
            AutocompleteContactTextField(
                fieldType = ContactFieldType.InstagramUsername,
                label = stringResource(R.string.instagram_username),
                value = instagramUsername,
                suggestions = fieldSuggestions[ContactFieldType.InstagramUsername].orEmpty(),
                onValueChange = { instagramUsername = it },
                onQueryChange = onFieldQueryChange,
                onClearSuggestions = onClearFieldSuggestions,
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.InstagramUsername],
                onDescriptionClick = { editingDescription = it },
            )
            AutocompleteContactTextField(
                fieldType = ContactFieldType.FacebookUserId,
                label = stringResource(R.string.facebook_user_id),
                value = facebookUserId,
                suggestions = fieldSuggestions[ContactFieldType.FacebookUserId].orEmpty(),
                onValueChange = { facebookUserId = it },
                onQueryChange = onFieldQueryChange,
                onClearSuggestions = onClearFieldSuggestions,
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.FacebookUserId],
                onDescriptionClick = { editingDescription = it },
            )
            AddressField(
                value = address,
                linkedPlaceId = addressPlaceId,
                suggestions = addressSuggestions,
                message = addressAutocompleteMessage,
                onOpen = {
                    onAddressQueryChange("")
                    revealAutocompleteDropdown()
                },
                onSuggestionSelected = { suggestion ->
                    onAddressSuggestionSelected(suggestion) { resolved ->
                        address = resolved.nickname
                        addressPlaceId = resolved.placeId
                        addressLatitude = null
                        addressLongitude = null
                    }
                },
                onUnlink = {
                    address = ""
                    addressPlaceId = null
                    addressLatitude = null
                    addressLongitude = null
                    onClearAddressSuggestions()
                },
                onClearSuggestions = onClearAddressSuggestions,
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.Address],
                onDescriptionClick = { editingDescription = it },
            )
            AutocompleteContactTextField(
                fieldType = ContactFieldType.Address2,
                label = stringResource(R.string.address_2),
                value = address2,
                suggestions = fieldSuggestions[ContactFieldType.Address2].orEmpty(),
                onValueChange = { address2 = it },
                onQueryChange = onFieldQueryChange,
                onClearSuggestions = onClearFieldSuggestions,
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.Address2],
                onDescriptionClick = { editingDescription = it },
            )
            NationalityEditField(
                value = nationality,
                countryCode = nationalityCountryCode,
                countries = countryOptions,
                onValueChange = { value ->
                    nationality = value
                    nationalityCountryCode = countryOptions
                        .firstOrNull { it.nationality().equals(value.trim(), ignoreCase = true) }
                        ?.code
                },
                onCountrySelected = { country ->
                    nationality = country.nationality()
                    nationalityCountryCode = country.code
                },
                onSuggestionsVisible = ::revealAutocompleteDropdown,
                descriptor = fieldDescriptors[ContactFieldType.Nationality],
                onDescriptionClick = { editingDescription = it },
            )
            AgeEditField(
                birthDate = birthDate,
                manualAge = manualAge,
                descriptor = fieldDescriptors[ContactFieldType.Age],
                onBirthDateChange = { value ->
                    birthDate = value
                    if (value.isNotBlank()) manualAge = ""
                },
                onManualAgeChange = { value ->
                    manualAge = value.filter { it.isDigit() }
                    if (manualAge.isNotBlank()) birthDate = ""
                },
                onDescriptionClick = { editingDescription = it },
            )
            ContactTextField(
                label = stringResource(R.string.note),
                value = note,
                onValueChange = { value ->
                    note = value
                    if (value.isBlank()) {
                        onClearFieldSuggestions(ContactFieldType.Note)
                    } else {
                        onFieldQueryChange(ContactFieldType.Note, value)
                        revealAutocompleteDropdown()
                    }
                },
                singleLine = false,
                trailingIcon = {
                    fieldDescriptors[ContactFieldType.Note]?.let { descriptor ->
                        FieldDescriptionIconButton(
                            label = stringResource(R.string.note),
                            descriptor = descriptor,
                            onClick = { editingDescription = descriptor },
                        )
                    }
                },
            )
            AutocompleteSuggestionsDropdown(
                fieldType = ContactFieldType.Note,
                suggestions = fieldSuggestions[ContactFieldType.Note].orEmpty(),
                onSuggestionSelected = { suggestion ->
                    note = suggestion.value
                    onClearFieldSuggestions(ContactFieldType.Note)
                },
                onSuggestionsVisible = ::revealAutocompleteDropdown,
            )
            FieldDescriptionText(fieldDescriptors[ContactFieldType.Note])
            DuplicateCandidatesSection(
                candidates = duplicateCandidates,
                onCandidateClick = onDuplicateCandidateClick,
            )
            Spacer(modifier = Modifier.height(240.dp))
        }
    }

    val warningCandidate = strongDuplicateWarning
    if (warningCandidate != null) {
        AlertDialog(
            onDismissRequest = {
                strongDuplicateWarning = null
                pendingSaveInput = null
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val confirmedInput = pendingSaveInput
                        strongDuplicateWarning = null
                        pendingSaveInput = null
                        if (confirmedInput != null) {
                            onSave(confirmedInput)
                        }
                    },
                ) {
                    Text(stringResource(R.string.save_anyway))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        strongDuplicateWarning = null
                        pendingSaveInput = null
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.duplicate_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.duplicate_confirm_body,
                        warningCandidate.displayName,
                    ),
                )
            },
        )
    }
    editingDescription?.let { descriptor ->
        FieldDescriptionDialog(
            visible = true,
            label = fieldLabel(descriptor.fieldType),
            descriptor = descriptor,
            onDismiss = { editingDescription = null },
            onSave = { description ->
                onFieldDescriptionChange(descriptor.id, description)
                editingDescription = null
            },
            onRemove = {
                onFieldDescriptionChange(descriptor.id, "")
                editingDescription = null
            },
        )
    }
}

@Composable
private fun AddressField(
    value: String,
    linkedPlaceId: String?,
    suggestions: List<AddressSuggestion>,
    message: String?,
    onOpen: () -> Unit,
    onSuggestionSelected: (AddressSuggestion) -> Unit,
    onUnlink: () -> Unit,
    onClearSuggestions: () -> Unit,
    onSuggestionsVisible: () -> Unit,
    descriptor: ContactFieldDescriptor?,
    onDescriptionClick: (ContactFieldDescriptor) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .testTag("address-luoghi-selector"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.address), style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = value.ifBlank { stringResource(R.string.address_choose_luoghi) },
                        color = if (value.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                Text(stringResource(R.string.address_open_luoghi_menu))
            }
        }
        if (linkedPlaceId != null || value.isNotBlank()) {
            TextButton(onClick = onUnlink, modifier = Modifier.testTag("address-luoghi-unlink")) {
                Text(stringResource(R.string.address_unlink_luoghi))
            }
        }
        descriptor?.let {
            FieldDescriptionIconButton(
                label = stringResource(R.string.address),
                descriptor = it,
                onClick = { onDescriptionClick(it) },
            )
        }
        FieldDescriptionText(descriptor)
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (suggestions.isNotEmpty()) {
            LaunchedEffect(suggestions) {
                onSuggestionsVisible()
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("address-autocomplete-dropdown"),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    suggestions.forEach { suggestion ->
                        TextButton(
                            onClick = {
                                onSuggestionSelected(suggestion)
                                onClearSuggestions()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("address-suggestion-${suggestion.source.name.lowercase()}"),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(
                                    text = stringResource(R.string.address_source_luoghi),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(text = suggestion.primaryText)
                                if (suggestion.secondaryText.isNotBlank()) {
                                    Text(
                                        text = suggestion.secondaryText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DuplicateCandidatesSection(
    candidates: List<ContactDuplicateCandidate>,
    onCandidateClick: (Long) -> Unit,
) {
    if (candidates.isEmpty()) return
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("duplicate-candidates-section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.possible_existing_contacts),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        candidates.take(5).forEach { candidate ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onCandidateClick(candidate.contactId) }
                    .padding(12.dp)
                    .testTag("duplicate-candidate-${candidate.contactId}"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = candidate.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = candidate.reasons.joinToString {
                            context.getString(duplicateReasonLabelResId(it))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.open),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun duplicateReasonLabelResId(reason: ContactDuplicateReason): Int =
    when (reason) {
        ContactDuplicateReason.SameNumber -> R.string.duplicate_reason_same_number
        ContactDuplicateReason.SameEmail -> R.string.duplicate_reason_same_email
        ContactDuplicateReason.SimilarName -> R.string.duplicate_reason_similar_name
        ContactDuplicateReason.ExistingLink -> R.string.duplicate_reason_existing_link
        ContactDuplicateReason.SimilarAddress -> R.string.duplicate_reason_similar_address
    }

@Composable
private fun rememberCountryOptions(): List<CountryOption> {
    val context = LocalContext.current
    val countries by produceState(initialValue = emptyList<CountryOption>(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching { CountryCatalog.load(context) }.getOrDefault(emptyList())
        }
    }
    return countries
}

@Composable
private fun NationalityEditField(
    value: String,
    countryCode: String?,
    countries: List<CountryOption>,
    onValueChange: (String) -> Unit,
    onCountrySelected: (CountryOption) -> Unit,
    onSuggestionsVisible: () -> Unit,
    descriptor: ContactFieldDescriptor?,
    onDescriptionClick: (ContactFieldDescriptor) -> Unit,
) {
    val query = value.trim()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var collapsedSelectionValue by rememberSaveable { mutableStateOf<String?>(null) }
    val suggestions = remember(query, countries, Locale.getDefault()) {
        if (query.isBlank()) {
            emptyList()
        } else {
            val normalized = query.lowercase(Locale.ROOT)
            countries
                .filter { country ->
                    country.nationality().lowercase(Locale.ROOT).startsWith(normalized) ||
                        country.country().lowercase(Locale.ROOT).startsWith(normalized) ||
                        country.code.lowercase(Locale.ROOT).startsWith(normalized)
                }
                .take(8)
        }
    }
    val showSuggestions = suggestions.isNotEmpty() && collapsedSelectionValue != value

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ContactTextField(
            label = stringResource(R.string.nationality),
            value = value,
            onValueChange = { updated ->
                collapsedSelectionValue = null
                onValueChange(updated)
            },
            trailingIcon = {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    CountryCatalog.flagEmoji(countryCode)
                        .takeIf { it.isNotBlank() }
                        ?.let { Text(text = it, modifier = Modifier.testTag("nationality-edit-flag")) }
                    descriptor?.let {
                        FieldDescriptionIconButton(
                            label = stringResource(R.string.nationality),
                            descriptor = it,
                            onClick = { onDescriptionClick(it) },
                        )
                    }
                }
            },
        )
        FieldDescriptionText(descriptor)
        if (showSuggestions) {
            LaunchedEffect(query, suggestions.size) {
                onSuggestionsVisible()
                bringIntoViewRequester.bringIntoView()
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(bringIntoViewRequester)
                    .testTag("autocomplete-dropdown-${ContactFieldType.Nationality}"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    suggestions.forEach { country ->
                        val label = stringResource(
                            R.string.nationality_suggestion,
                            CountryCatalog.flagEmoji(country.code),
                            country.nationality(),
                            country.country(),
                        )
                        TextButton(
                            onClick = {
                                collapsedSelectionValue = country.nationality()
                                onCountrySelected(country)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("autocomplete-suggestion-${ContactFieldType.Nationality}-${country.code}"),
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AutocompleteContactTextField(
    fieldType: String,
    label: String,
    value: String,
    suggestions: List<ContactFieldSuggestion>,
    onValueChange: (String) -> Unit,
    onQueryChange: (String, String) -> Unit,
    onClearSuggestions: (String) -> Unit,
    onSuggestionsVisible: () -> Unit,
    singleLine: Boolean = true,
    trailingIcon: (@Composable (() -> Unit))? = null,
    descriptor: ContactFieldDescriptor? = null,
    onDescriptionClick: (ContactFieldDescriptor) -> Unit = {},
) {
    val mergedTrailingIcon: (@Composable (() -> Unit))? =
        if (trailingIcon == null && descriptor == null) {
            null
        } else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    trailingIcon?.invoke()
                    descriptor?.let {
                        FieldDescriptionIconButton(
                            label = label,
                            descriptor = it,
                            onClick = { onDescriptionClick(it) },
                        )
                    }
                }
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ContactTextField(
            label = label,
            value = value,
            onValueChange = { updated ->
                onValueChange(updated)
                if (updated.isBlank()) {
                    onClearSuggestions(fieldType)
                } else {
                    onQueryChange(fieldType, updated)
                    onSuggestionsVisible()
                }
            },
            singleLine = singleLine,
            trailingIcon = mergedTrailingIcon,
        )
        AutocompleteSuggestionsDropdown(
            fieldType = fieldType,
            suggestions = suggestions,
            onSuggestionSelected = { suggestion ->
                onValueChange(suggestion.value)
                onClearSuggestions(fieldType)
            },
            onSuggestionsVisible = onSuggestionsVisible,
        )
        FieldDescriptionText(descriptor)
    }
}

@Composable
private fun AutocompleteSuggestionsDropdown(
    fieldType: String,
    suggestions: List<ContactFieldSuggestion>,
    onSuggestionSelected: (ContactFieldSuggestion) -> Unit,
    onSuggestionsVisible: () -> Unit,
) {
    if (suggestions.isEmpty()) return

    LaunchedEffect(fieldType, suggestions) {
        onSuggestionsVisible()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("autocomplete-dropdown-$fieldType"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState())
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            suggestions.forEach { suggestion ->
                TextButton(
                    onClick = { onSuggestionSelected(suggestion) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("autocomplete-suggestion-$fieldType"),
                ) {
                    Text(
                        text = suggestion.value,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable (() -> Unit))? = null,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    scope.launch {
                        delay(300)
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
            .testTag("contact-field-${label.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')}"),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        trailingIcon = trailingIcon,
    )
}

@Composable
private fun PhoneEditField(
    value: String,
    suggestions: List<ContactFieldSuggestion>,
    onValueChange: (String) -> Unit,
    onQueryChange: (String, String) -> Unit,
    onClearSuggestions: (String) -> Unit,
    onSuggestionsVisible: () -> Unit,
    descriptor: ContactFieldDescriptor?,
    onDescriptionClick: (ContactFieldDescriptor) -> Unit,
) {
    val context = LocalContext.current
    AutocompleteContactTextField(
        fieldType = ContactFieldType.Phone,
        label = stringResource(R.string.phone),
        value = value,
        suggestions = suggestions,
        onValueChange = onValueChange,
        onQueryChange = onQueryChange,
        onClearSuggestions = onClearSuggestions,
        onSuggestionsVisible = onSuggestionsVisible,
        descriptor = descriptor,
        trailingIcon = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (value.isNotBlank()) {
                    IconButton(
                        onClick = { openPhoneDialer(context, value) },
                        modifier = Modifier.testTag("phone-action-dial"),
                    ) {
                        Text(text = "📞")
                    }
                    IconButton(
                        onClick = { openSms(context, value) },
                        modifier = Modifier.testTag("phone-action-sms"),
                    ) {
                        Text(text = "💬")
                    }
                }
            }
        },
    )
}

@Composable
private fun AgeEditField(
    birthDate: String,
    manualAge: String,
    descriptor: ContactFieldDescriptor?,
    onBirthDateChange: (String) -> Unit,
    onManualAgeChange: (String) -> Unit,
    onDescriptionClick: (ContactFieldDescriptor) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ContactTextField(
            label = stringResource(R.string.birth_date),
            value = birthDate,
            onValueChange = onBirthDateChange,
            trailingIcon = {
                descriptor?.let {
                    FieldDescriptionIconButton(
                        label = stringResource(R.string.age),
                        descriptor = it,
                        onClick = { onDescriptionClick(it) },
                    )
                }
            },
        )
        ContactTextField(
            label = stringResource(R.string.manual_age),
            value = manualAge,
            onValueChange = onManualAgeChange,
        )
        Text(
            text = stringResource(R.string.age_edit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FieldDescriptionText(descriptor)
    }
}

@Composable
private fun FieldDescriptionIconButton(
    label: String,
    descriptor: ContactFieldDescriptor,
    onClick: () -> Unit,
) {
    val descriptionAction = if (descriptor.description.isBlank()) {
        stringResource(R.string.add_field_description_for, label, descriptor.value)
    } else {
        stringResource(R.string.edit_field_description_for, label, descriptor.value)
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(32.dp)
            .semantics {
                contentDescription = descriptionAction
                role = Role.Button
            }
            .testTag("field-description-more-${descriptor.fieldType}"),
    ) {
        Text(
            text = if (descriptor.description.isBlank()) "+" else "✎",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TelegramEditField(
    value: String,
    phone: String,
    onValueChange: (String) -> Unit,
    descriptor: ContactFieldDescriptor?,
    onDescriptionClick: (ContactFieldDescriptor) -> Unit,
) {
    val context = LocalContext.current
    var showChoiceDialog by rememberSaveable { mutableStateOf(false) }
    var showUsernameDialog by rememberSaveable { mutableStateOf(false) }
    var usernameInput by rememberSaveable { mutableStateOf("") }
    val displayValue = telegramDisplayValue(value)

    fun submitUsername() {
        val link = telegramLinkForUsername(usernameInput)
        if (link != null) {
            onValueChange(link)
            usernameInput = ""
            showUsernameDialog = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (value.isBlank()) {
                        if (phone.isBlank()) showUsernameDialog = true else showChoiceDialog = true
                    } else {
                        openTelegram(context, value)
                    }
                }
                .testTag("telegram-field"),
            label = { Text(stringResource(R.string.telegram)) },
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (value.isNotBlank()) {
                        IconButton(
                            onClick = { openTelegram(context, value) },
                            modifier = Modifier.testTag("telegram-open"),
                        ) {
                            Text("↗")
                        }
                    }
                    IconButton(
                        onClick = {
                            usernameInput = telegramUsernameFromStoredValue(value).orEmpty()
                            if (value.isBlank() && phone.isNotBlank()) {
                                showChoiceDialog = true
                            } else {
                                showUsernameDialog = true
                            }
                        },
                        modifier = Modifier.testTag("telegram-edit"),
                    ) {
                        Text(if (value.isBlank()) "+" else "✎")
                    }
                    if (value.isNotBlank()) {
                        IconButton(
                            onClick = { onValueChange("") },
                            modifier = Modifier.testTag("telegram-remove"),
                        ) {
                            Text("×")
                        }
                    }
                    descriptor?.let {
                        FieldDescriptionIconButton(
                            label = stringResource(R.string.telegram),
                            descriptor = it,
                            onClick = { onDescriptionClick(it) },
                        )
                    }
                }
            },
        )
        Text(
            text = stringResource(R.string.telegram_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FieldDescriptionText(descriptor)
    }

    if (showChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showChoiceDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val link = telegramLinkForPhone(phone)
                        if (link != null) {
                            onValueChange(link)
                        }
                        showChoiceDialog = false
                    },
                ) {
                    Text(stringResource(R.string.telegram_via_phone))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showChoiceDialog = false
                            usernameInput = ""
                            showUsernameDialog = true
                        },
                    ) {
                        Text(stringResource(R.string.telegram_via_username))
                    }
                    TextButton(onClick = { showChoiceDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
            title = { Text(stringResource(R.string.telegram)) },
            text = { Text(stringResource(R.string.telegram_choice_question)) },
        )
    }

    if (showUsernameDialog) {
        AlertDialog(
            onDismissRequest = { showUsernameDialog = false },
            confirmButton = {
                TextButton(
                    onClick = ::submitUsername,
                    enabled = telegramLinkForUsername(usernameInput) != null,
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUsernameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.telegram_username_title)) },
            text = {
                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = { usernameInput = it },
                    label = { Text(stringResource(R.string.telegram_username)) },
                    singleLine = true,
                    modifier = Modifier.testTag("telegram-username-input"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitUsername() }),
                )
            },
        )
    }
}

@Composable
private fun ContactPhotoEditor(
    photoPath: String,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(
            photoPath = photoPath,
            ownerId = "people-draft",
            displayName = stringResource(R.string.contact_photo),
            size = 72.dp,
            circular = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPickPhoto) {
                Text(
                    stringResource(
                        if (photoPath.isBlank()) R.string.choose_photo else R.string.change_photo,
                    ),
                )
            }
            if (photoPath.isNotBlank()) {
                TextButton(onClick = onRemovePhoto) {
                    Text(stringResource(R.string.remove_photo))
                }
            }
        }
    }
}

@Composable
private fun ContactAvatar(
    photoPath: String,
    ownerId: String,
    displayName: String,
    size: Dp,
    circular: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val bytes by produceState<ByteArray?>(
        initialValue = null,
        key1 = photoPath,
    ) {
        value = if (photoPath.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                com.gernalix.personalhub.core.database.PersonalHubDatabase
                    .get(context)
                    .photoDao()
                    .find(photoPath)
                    ?.bytes
            }
        }
    }
    val shape = if (circular) CircleShape else RoundedCornerShape(4.dp)
    val clickableModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .border(2.dp, MaterialTheme.colorScheme.primary, shape)
            .clickable(
                onClickLabel = contentDescription,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription?.let { this.contentDescription = it }
                role = Role.Button
            }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .then(clickableModifier),
        contentAlignment = Alignment.Center,
    ) {
        val imageBytes = bytes
        if (imageBytes != null) {
            com.gernalix.personalhub.core.ui.photo.HubSquarePhotoThumbnail(
                photo = com.gernalix.personalhub.core.ui.photo.HubPhoto(
                    id = photoPath,
                    ownerId = ownerId,
                    reference = photoPath,
                    contentDescription = contentDescription ?: displayName,
                    loaderData = imageBytes,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = displayName.initials(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ErrorMessage(
    message: String?,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss))
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Scaffold { innerPadding ->
        CenteredLoading(innerPadding)
    }
}

@Composable
private fun CenteredLoading(innerPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LoadingScreenContent()
    }
}

@Composable
private fun LoadingScreenContent() {
    CircularProgressIndicator()
}

private fun emptyContactInput(): ContactInput =
    ContactInput(
        name = "",
        phone = "",
        telegram = "",
        email = "",
        nickname = "",
        company = "",
        note = "",
        link = "",
        address = "",
        address2 = "",
        nationality = "",
        nationalityCountryCode = null,
        photoPath = "",
    )

private fun titleCaseNameWords(value: String): String {
    if (value.isEmpty()) return value
    val builder = StringBuilder(value.length)
    var atWordStart = true
    value.forEach { char ->
        if (char.isWhitespace()) {
            builder.append(char)
            atWordStart = true
        } else {
            builder.append(if (atWordStart) char.uppercaseChar() else char)
            atWordStart = false
        }
    }
    return builder.toString()
}

@Composable
private fun InitiativeButtons(
    contactId: Long,
    contactName: String,
    scope: String,
    onInitiativeClick: (Long, InitiativeType, String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InitiativeButton(
            text = stringResource(R.string.initiative_self_short),
            description = stringResource(R.string.initiative_add_self_with_name, contactName),
            color = Color(0xFF2E7D32),
            modifier = Modifier.testTag("initiative-self-$scope"),
            onClick = { onInitiativeClick(contactId, InitiativeType.SELF, contactName) },
        )
        InitiativeButton(
            text = stringResource(R.string.initiative_other_short),
            description = stringResource(R.string.initiative_add_other_with_name, contactName),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("initiative-other-$scope"),
            onClick = { onInitiativeClick(contactId, InitiativeType.OTHER, contactName) },
        )
    }
}

@Composable
private fun InitiativeButton(
    text: String,
    description: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .size(width = 44.dp, height = 40.dp)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun HistoryFilterRow(
    includeContact: Boolean,
    includeField: Boolean,
    includeInitiative: Boolean,
    onIncludeContactChange: (Boolean) -> Unit,
    onIncludeFieldChange: (Boolean) -> Unit,
    onIncludeInitiativeChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.history_filters),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            HistoryFilterCheckbox(
                checked = includeContact,
                label = stringResource(R.string.history_filter_contact),
                onCheckedChange = onIncludeContactChange,
            )
            HistoryFilterCheckbox(
                checked = includeField,
                label = stringResource(R.string.history_filter_field),
                onCheckedChange = onIncludeFieldChange,
            )
            HistoryFilterCheckbox(
                checked = includeInitiative,
                label = stringResource(R.string.history_filter_initiative),
                onCheckedChange = onIncludeInitiativeChange,
            )
        }
    }
}

@Composable
private fun HistoryFilterCheckbox(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun GlobalHistoryEventRow(
    item: GlobalContactEvent,
    onClick: () -> Unit,
    onTimestampClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.global_event_row,
                    item.contactDisplayName,
                    eventDescription(item.event),
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = formatTimestamp(item.event.occurredAt),
                modifier = Modifier.clickable { onTimestampClick() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryEventRow(
    event: ContactEvent,
    onTimestampClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = formatTimestamp(event.occurredAt),
                modifier = Modifier.clickable { onTimestampClick() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = eventDescription(event),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ContactInitiativeRow(
    initiative: ContactInitiative,
    onDeleteRequest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = formatTimestamp(initiative.timestampUtc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = initiativeLabel(initiative.initiativeType),
                    style = MaterialTheme.typography.bodyMedium,
                    color = initiativeColor(initiative.initiativeType),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(
                onClick = onDeleteRequest,
                modifier = Modifier.testTag("initiative-delete-${initiative.id}"),
            ) {
                Text(stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun DeleteInitiativeDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("initiative-delete-confirm"),
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.initiative_delete_confirm_title)) },
        text = { Text(stringResource(R.string.initiative_delete_confirm_body)) },
    )
}

@Composable
private fun GlobalInitiativeRow(item: GlobalContactInitiative) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = initiativeEventText(item.contactDisplayName, item.initiative.initiativeType),
                style = MaterialTheme.typography.bodyMedium,
                color = initiativeColor(item.initiative.initiativeType),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = formatTimestamp(item.initiative.timestampUtc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InitiativeDayEventRow(item: GlobalContactInitiative) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = formatTimestamp(item.initiative.timestampUtc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = item.contactDisplayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = if (item.initiative.initiativeType == InitiativeType.SELF) {
                    stringResource(R.string.initiative_self_short)
                } else {
                    stringResource(R.string.initiative_other_short)
                },
                style = MaterialTheme.typography.titleMedium,
                color = initiativeColor(item.initiative.initiativeType),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FieldSection(
    label: String,
    value: String,
    timestamp: ContactFieldTimestamp?,
    descriptor: ContactFieldDescriptor?,
    showTimestamp: Boolean,
    onOpen: () -> Unit,
    onDescriptionChange: (Long, String) -> Unit,
) {
    if (value.isBlank()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabelRow(
            label = label,
            descriptor = null,
            onMoreClick = {},
        )
        Text(
            text = value.ifBlank { "-" },
            modifier = Modifier.clickable(enabled = value.isNotBlank()) { onOpen() },
            style = MaterialTheme.typography.bodyLarge,
        )
        timestamp?.takeIf { showTimestamp }?.let {
            Text(
                text = timestampText(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FieldDescriptionText(descriptor)
    }
}

@Composable
private fun NationalityFieldSection(
    value: String,
    countryCode: String?,
    timestamp: ContactFieldTimestamp?,
    descriptor: ContactFieldDescriptor?,
    showTimestamp: Boolean,
    onOpen: () -> Unit,
    onDescriptionChange: (Long, String) -> Unit,
) {
    if (value.isBlank()) return
    val label = stringResource(R.string.nationality)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabelRow(
            label = label,
            descriptor = null,
            onMoreClick = {},
        )
        NationalityFlagText(
            nationality = value,
            countryCode = countryCode,
            modifier = Modifier.clickable(enabled = value.isNotBlank()) { onOpen() },
        )
        timestamp?.takeIf { showTimestamp }?.let {
            Text(
                text = timestampText(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FieldDescriptionText(descriptor)
    }
}

@Composable
private fun NationalityFlagText(
    nationality: String,
    countryCode: String?,
    modifier: Modifier = Modifier,
) {
    val flag = CountryCatalog.flagEmoji(countryCode)
    if (nationality.isBlank() && flag.isBlank()) return
    Row(
        modifier = modifier.testTag("nationality-display"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (flag.isNotBlank()) {
            Text(
                text = flag,
                modifier = Modifier.testTag("nationality-flag"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (nationality.isNotBlank()) {
            Text(
                text = nationality,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun AgeFieldSection(
    age: com.supercontacts.app.data.repository.ContactAge?,
    timestamp: ContactFieldTimestamp?,
    descriptor: ContactFieldDescriptor?,
    showTimestamp: Boolean,
    onOpen: () -> Unit,
) {
    if (age == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabelRow(
            label = stringResource(R.string.age),
            descriptor = null,
            onMoreClick = {},
        )
        Text(
            text = stringResource(R.string.age_value_years, age.years),
            modifier = Modifier.clickable { onOpen() },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                if (age.source == com.supercontacts.app.data.repository.ContactAgeSource.BIRTH_DATE) {
                    R.string.age_source_birth_date
                } else {
                    R.string.age_source_manual
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        timestamp?.takeIf { showTimestamp }?.let {
            Text(
                text = timestampText(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FieldDescriptionText(descriptor)
    }
}

@Composable
private fun PhoneFieldSection(
    label: String,
    value: String,
    messagingLinks: List<ContactMessagingLink>,
    timestamp: ContactFieldTimestamp?,
    descriptor: ContactFieldDescriptor?,
    showTimestamp: Boolean,
    onOpen: () -> Unit,
    onDescriptionChange: (Long, String) -> Unit,
) {
    if (value.isBlank()) return
    val context = LocalContext.current
    val confirmedLinks = messagingLinks.filter {
        it.verificationStatus == MessagingLinkVerificationStatus.ManuallyConfirmed
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabelRow(
            label = label,
            descriptor = null,
            onMoreClick = {},
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = value.isNotBlank()) { onOpen() },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (value.isNotBlank()) {
                IconButton(
                    onClick = {
                        onOpen()
                        openPhoneDialer(context, value)
                    },
                    modifier = Modifier.testTag("phone-detail-dial"),
                ) {
                    Text("📞")
                }
                IconButton(
                    onClick = {
                        onOpen()
                        openSms(context, value)
                    },
                    modifier = Modifier.testTag("phone-detail-sms"),
                ) {
                    Text("💬")
                }
                confirmedLinks.forEach { link ->
                    MessagingQuickActionButton(
                        link = link,
                        onClick = {
                            onOpen()
                            openUri(context, Uri.parse(link.deepLink))
                        },
                    )
                }
            }
        }
        timestamp?.takeIf { showTimestamp }?.let {
            Text(
                text = timestampText(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FieldDescriptionText(descriptor)
    }
}

@Composable
private fun MessagingQuickActionButton(
    link: ContactMessagingLink,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.testTag("phone-action-messaging-${link.platform}"),
    ) {
        Icon(
            imageVector = messagingPlatformQuickIcon(link.platform),
            contentDescription = stringResource(
                R.string.messaging_open_confirmed,
                messagingPlatformLabel(link.platform),
            ),
            tint = messagingPlatformColor(link.platform),
        )
    }
}

@Composable
private fun EmailFieldSection(
    value: String,
    timestamp: ContactFieldTimestamp?,
    descriptor: ContactFieldDescriptor?,
    showTimestamp: Boolean,
    onOpen: () -> Unit,
    onDescriptionChange: (Long, String) -> Unit,
) {
    if (value.isBlank()) return
    val context = LocalContext.current
    FieldSection(
        label = stringResource(R.string.email),
        value = value,
        timestamp = timestamp,
        descriptor = descriptor,
        showTimestamp = showTimestamp,
        onOpen = {
            onOpen()
            openEmail(context, value)
        },
        onDescriptionChange = onDescriptionChange,
    )
}

@Composable
private fun TelegramFieldSection(
    value: String,
    timestamp: ContactFieldTimestamp?,
    descriptor: ContactFieldDescriptor?,
    showTimestamp: Boolean,
    onOpen: () -> Unit,
    onDescriptionChange: (Long, String) -> Unit,
) {
    if (value.isBlank()) return
    val context = LocalContext.current
    val label = stringResource(R.string.telegram)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabelRow(
            label = label,
            descriptor = null,
            onMoreClick = {},
        )
        Text(
            text = telegramDisplayValue(value),
            modifier = Modifier.clickable(enabled = value.isNotBlank()) {
                onOpen()
                openTelegram(context, value)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (value.isNotBlank()) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        timestamp?.takeIf { showTimestamp }?.let {
            Text(
                text = timestampText(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FieldDescriptionText(descriptor)
    }
}

@Composable
private fun LinkFieldSection(
    value: String,
    timestamp: ContactFieldTimestamp?,
    descriptor: ContactFieldDescriptor?,
    showTimestamp: Boolean,
    onOpen: () -> Unit,
    onDescriptionChange: (Long, String) -> Unit,
) {
    if (value.isBlank()) return

    val context = LocalContext.current
    val label = stringResource(R.string.link)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabelRow(
            label = label,
            descriptor = null,
            onMoreClick = {},
        )
        Text(
            text = value,
            modifier = Modifier.clickable(enabled = value.isNotBlank()) {
                onOpen()
                openLink(context, value)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (value.isNotBlank()) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        timestamp?.takeIf { showTimestamp }?.let {
            Text(
                text = timestampText(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FieldDescriptionText(descriptor)
    }
}

@Composable
private fun FieldLabelRow(
    label: String,
    descriptor: ContactFieldDescriptor?,
    onMoreClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (descriptor != null) {
            val descriptionAction = if (descriptor.description.isBlank()) {
                stringResource(R.string.add_field_description_for, label, descriptor.value)
            } else {
                stringResource(R.string.edit_field_description_for, label, descriptor.value)
            }
            val shape = CircleShape
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(shape)
                    .background(
                        if (descriptor.description.isBlank()) {
                            Color.Transparent
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        },
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = shape,
                    )
                    .clickable(onClick = onMoreClick)
                    .semantics {
                        contentDescription = descriptionAction
                        role = Role.Button
                    }
                    .testTag("field-description-more-${descriptor.fieldType}"),
            ) {
                Text(
                    text = if (descriptor.description.isBlank()) "+" else "✎",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FieldDescriptionText(descriptor: ContactFieldDescriptor?) {
    val description = descriptor?.description.orEmpty()
    if (description.isBlank()) return
    val fieldType = descriptor?.fieldType.orEmpty()
    Text(
        text = description,
        modifier = Modifier
            .padding(start = 16.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("field-description-$fieldType"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FieldDescriptionDialog(
    visible: Boolean,
    label: String,
    descriptor: ContactFieldDescriptor,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    if (!visible) return
    var description by rememberSaveable(descriptor.id, descriptor.description) {
        mutableStateOf(descriptor.description)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(description) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (descriptor.description.isNotBlank()) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.remove_field_description))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        title = {
            Text(
                text = if (descriptor.description.isBlank()) {
                    stringResource(R.string.add_field_description)
                } else {
                    stringResource(R.string.edit_field_description)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.field_description_parent, label, descriptor.value),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("field-description-input-${descriptor.fieldType}"),
                    label = { Text(stringResource(R.string.field_description)) },
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
    )
}

@Composable
private fun DistanceSection(detail: ContactDetail, linkedPlace: ResolvedAddress?) {
    if (detail.address.isBlank() && linkedPlace == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusText by rememberSaveable(detail.id, detail.address, linkedPlace?.placeId) {
        mutableStateOf(
            if (detail.address.isBlank() && linkedPlace == null) {
                context.getString(R.string.distance_missing_address)
            } else {
                context.getString(R.string.distance_ready)
            },
        )
    }
    var isCalculating by rememberSaveable(detail.id) { mutableStateOf(false) }
    var hasPendingRefresh by rememberSaveable(detail.id) { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.any { it }) {
            hasPendingRefresh = false
            scope.launchDistanceRefresh(context, detail, linkedPlace) { text, running ->
                statusText = text
                isCalculating = running
            }
        } else {
            hasPendingRefresh = false
            statusText = context.getString(R.string.distance_missing_permission)
            isCalculating = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.distance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(
                onClick = {
                    if (detail.address.isBlank() && linkedPlace == null) {
                        statusText = context.getString(R.string.distance_missing_address)
                        return@IconButton
                    }
                    if (!hasLocationPermission(context)) {
                        hasPendingRefresh = true
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                        return@IconButton
                    }
                    hasPendingRefresh = false
                    scope.launchDistanceRefresh(context, detail, linkedPlace) { text, running ->
                        statusText = text
                        isCalculating = running
                    }
                },
                enabled = !isCalculating,
            ) {
                Icon(
                    imageVector = RefreshIcon,
                    contentDescription = stringResource(R.string.distance_refresh),
                )
            }
        }
        Text(
            text = if (hasPendingRefresh) {
                stringResource(R.string.distance_missing_permission)
            } else {
                statusText
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SocialLinksSection(
    detail: ContactDetail,
    showTimestamp: Boolean,
    onFieldOpen: (String) -> Unit,
    onDescriptionChange: (Long, String) -> Unit,
) {
    val context = LocalContext.current
    val socialButtons = listOfNotNull(
        detail.instagramUsername.trim().takeIf { it.isNotBlank() }?.let { username ->
            SocialButton(
                label = stringResource(R.string.open_instagram),
                icon = InstagramIcon,
                fieldType = ContactFieldType.InstagramUsername,
                uri = Uri.parse("https://www.instagram.com/${Uri.encode(username)}"),
            )
        },
        detail.facebookUserId.trim().takeIf { it.isNotBlank() }?.let { facebookUserId ->
            SocialButton(
                label = stringResource(R.string.open_facebook),
                icon = FacebookIcon,
                fieldType = ContactFieldType.FacebookUserId,
                uri = Uri.parse("https://facebook.com/${Uri.encode(facebookUserId)}"),
            )
        },
        detail.facebookUserId.trim().takeIf { it.isNotBlank() }?.let { facebookUserId ->
            SocialButton(
                label = stringResource(R.string.open_messenger),
                icon = MessengerIcon,
                fieldType = ContactFieldType.FacebookUserId,
                uri = Uri.parse("fb-messenger://user/${Uri.encode(facebookUserId)}"),
            )
        },
    )
    val hasAnySocialField = detail.grindrNick.isNotBlank() ||
        detail.instagramUsername.isNotBlank() ||
        detail.facebookUserId.isNotBlank()

    if (!hasAnySocialField) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.social),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (socialButtons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                socialButtons.forEach { button ->
                    IconButton(
                        onClick = {
                            onFieldOpen(button.fieldType)
                            openUri(context, button.uri)
                        },
                    ) {
                        Icon(
                            imageVector = button.icon,
                            contentDescription = button.label,
                        )
                    }
                }
            }
        }
        SocialStoredValue(
            label = stringResource(R.string.grindr_nick),
            value = detail.grindrNick,
            timestamp = detail.fieldTimestamps[ContactFieldType.GrindrNick],
            descriptor = detail.fieldDescriptors[ContactFieldType.GrindrNick],
            showTimestamp = showTimestamp,
            onDescriptionChange = onDescriptionChange,
        )
        SocialStoredValue(
            label = stringResource(R.string.instagram_username),
            value = detail.instagramUsername,
            timestamp = detail.fieldTimestamps[ContactFieldType.InstagramUsername],
            descriptor = detail.fieldDescriptors[ContactFieldType.InstagramUsername],
            showTimestamp = showTimestamp,
            onDescriptionChange = onDescriptionChange,
        )
        SocialStoredValue(
            label = stringResource(R.string.facebook_user_id),
            value = detail.facebookUserId,
            timestamp = detail.fieldTimestamps[ContactFieldType.FacebookUserId],
            descriptor = detail.fieldDescriptors[ContactFieldType.FacebookUserId],
            showTimestamp = showTimestamp,
            onDescriptionChange = onDescriptionChange,
        )
    }
}

@Composable
private fun SocialStoredValue(
    label: String,
    value: String,
    timestamp: ContactFieldTimestamp?,
    descriptor: ContactFieldDescriptor?,
    showTimestamp: Boolean,
    onDescriptionChange: (Long, String) -> Unit,
) {
    if (value.isBlank()) return
    FieldLabelRow(
        label = label,
        descriptor = null,
        onMoreClick = {},
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
    )
    timestamp?.takeIf { showTimestamp }?.let {
        Text(
            text = timestampText(it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class SocialButton(
    val label: String,
    val icon: ImageVector,
    val fieldType: String,
    val uri: Uri,
)

@Composable
private fun MessagingLinksSection(
    links: List<ContactMessagingLink>,
    onConfirm: (Long) -> Unit,
    onReject: (Long) -> Unit,
    onReset: (Long) -> Unit,
) {
    val undecidedLinks = links.filter { it.verificationStatus == MessagingLinkVerificationStatus.Unverified }
    if (undecidedLinks.isEmpty()) return
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.messaging_links),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (undecidedLinks.isNotEmpty()) {
            Text(
                text = stringResource(R.string.messaging_links_unverified_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        undecidedLinks.forEach { link ->
            MessagingActionRequiredCard(
                link = link,
                onOpen = { openUri(context, Uri.parse(link.deepLink)) },
                onConfirm = onConfirm,
                onReject = onReject,
                onReset = onReset,
            )
        }
    }
}

@Composable
private fun MessagingLinksManagerDialog(
    links: List<ContactMessagingLink>,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onConfirm: (Long) -> Unit,
    onReject: (Long) -> Unit,
    onReset: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        dismissButton = {
            TextButton(onClick = onScan) {
                Text(stringResource(R.string.messaging_scan_contact))
            }
        },
        title = { Text(stringResource(R.string.messaging_manage)) },
        text = {
            if (links.isEmpty()) {
                Text(
                    text = stringResource(R.string.messaging_links_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    links.forEach { link ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("messaging-link-manager-${link.platform}-${link.normalizedPhone}"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "${messagingPlatformLabel(link.platform)} ${messagingStatusText(link)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.messaging_link_phone, "+${link.normalizedPhone}"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onConfirm(link.id) }) {
                                    Text(stringResource(R.string.messaging_confirm))
                                }
                                TextButton(onClick = { onReject(link.id) }) {
                                    Text(stringResource(R.string.messaging_reject))
                                }
                                TextButton(onClick = { onReset(link.id) }) {
                                    Text(stringResource(R.string.messaging_reset_unverified))
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun MessagingActionRequiredCard(
    link: ContactMessagingLink,
    onOpen: () -> Unit,
    onConfirm: (Long) -> Unit,
    onReject: (Long) -> Unit,
    onReset: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(8.dp)
            .testTag("messaging-link-card-${link.platform}-${link.normalizedPhone}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = messagingPlatformLabel(link.platform),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.messaging_link_phone, "+${link.normalizedPhone}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpen) {
                Text(stringResource(R.string.open))
            }
        }
        Text(
            text = messagingStatusText(link),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (link.platform == MessagingPlatform.Signal) {
            Text(
                text = stringResource(R.string.messaging_signal_best_effort),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onConfirm(link.id) }) {
                Text(stringResource(R.string.messaging_confirm))
            }
            TextButton(onClick = { onReject(link.id) }) {
                Text(stringResource(R.string.messaging_reject))
            }
            if (link.verificationStatus != MessagingLinkVerificationStatus.Unverified) {
                TextButton(onClick = { onReset(link.id) }) {
                    Text(stringResource(R.string.messaging_reset_unverified))
                }
            }
        }
    }
}

@Composable
private fun messagingPlatformLabel(platform: String): String =
    when (platform) {
        MessagingPlatform.WhatsApp -> stringResource(R.string.messaging_platform_whatsapp)
        MessagingPlatform.Telegram -> stringResource(R.string.messaging_platform_telegram)
        MessagingPlatform.Signal -> stringResource(R.string.messaging_platform_signal)
        else -> platform
    }

private fun messagingPlatformQuickIcon(platform: String): ImageVector =
    when (platform) {
        MessagingPlatform.WhatsApp -> WhatsAppIcon
        MessagingPlatform.Telegram -> TelegramIcon
        MessagingPlatform.Signal -> SignalIcon
        else -> LinkIcon
    }

@Composable
private fun messagingPlatformColor(platform: String): Color =
    when (platform) {
        MessagingPlatform.WhatsApp -> Color(0xFF128C7E)
        MessagingPlatform.Telegram -> Color(0xFF229ED9)
        MessagingPlatform.Signal -> Color(0xFF3A76F0)
        else -> MaterialTheme.colorScheme.primary
    }

@Composable
private fun messagingStatusText(link: ContactMessagingLink): String =
    when (link.verificationStatus) {
        MessagingLinkVerificationStatus.ManuallyConfirmed -> stringResource(R.string.messaging_status_manually_confirmed)
        MessagingLinkVerificationStatus.ManuallyRejected -> stringResource(R.string.messaging_status_manually_rejected)
        else -> stringResource(R.string.messaging_status_generated_unverified)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTimestampDialog(
    initialTimestamp: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val initialDateTime = remember(initialTimestamp) {
        Instant.ofEpochMilli(initialTimestamp).atZone(zoneId)
    }
    val initialDateMillis = remember(initialTimestamp) {
        initialDateTime.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialDateMillis)
    val timePickerState = rememberTimePickerState(
        initialHour = initialDateTime.hour,
        initialMinute = initialDateTime.minute,
        is24Hour = true,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.history_timestamp_edit),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                DatePicker(
                    state = datePickerState,
                    modifier = Modifier.fillMaxWidth(),
                    title = null,
                    headline = null,
                    showModeToggle = false,
                )
                TimeInput(state = timePickerState)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            val selectedDate = datePickerState.selectedDateMillis
                                ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                                ?: initialDateTime.toLocalDate()
                            val selectedTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                            onConfirm(
                                selectedDate
                                    .atTime(selectedTime)
                                    .atZone(zoneId)
                                    .toInstant()
                                    .toEpochMilli(),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

@Composable
private fun timestampText(timestamp: ContactFieldTimestamp): String =
    stringResource(
        R.string.timestamp_added_edited,
        formatTimestamp(timestamp.addedAt),
        timestamp.editedAt?.let { formatTimestamp(it) } ?: "-",
    )

@Composable
private fun formatTimestamp(value: Long): String =
    ContactTimeFormatter.formatDateTime(value)

@Composable
private fun rangeText(range: HistoryDateRange): String =
    if (range.startDate == range.endDate) {
        ContactTimeFormatter.formatDate(range.startDate)
    } else {
        stringResource(
            R.string.history_range_value,
            ContactTimeFormatter.formatDate(range.startDate),
            ContactTimeFormatter.formatDate(range.endDate),
        )
    }

@Composable
private fun formatKnownDuration(sinceUtc: Long): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(sinceUtc).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val days = ChronoUnit.DAYS.between(start, today).coerceAtLeast(0)
    return when {
        days < 7 -> stringResource(R.string.stats_days, days)
        days < 30 -> stringResource(R.string.stats_weeks, days / 7)
        days < 365 -> stringResource(R.string.stats_months, (days / 30).coerceAtLeast(1))
        else -> {
            val totalMonths = ChronoUnit.MONTHS.between(start.withDayOfMonth(1), today.withDayOfMonth(1))
                .coerceAtLeast(12)
            val years = totalMonths / 12
            val months = totalMonths % 12
            stringResource(R.string.stats_years_months, years, months)
        }
    }
}

@Composable
private fun eventDescription(event: ContactEvent): String =
    when {
        event.isFieldDescriptionEvent() && event.actionType == EventActionAdded ->
            stringResource(
                R.string.event_field_description_added,
                fieldLabel(event.fieldType),
                quotedValue(parentFieldValueFromMetadata(event.metadataJson)),
                quotedFieldValue(event.fieldType, event.newValue),
            )

        event.isFieldDescriptionEvent() && event.actionType == EventActionUpdated ->
            stringResource(
                R.string.event_field_description_changed,
                fieldLabel(event.fieldType),
                quotedValue(parentFieldValueFromMetadata(event.metadataJson)),
                quotedFieldValue(event.fieldType, event.oldValue),
                quotedFieldValue(event.fieldType, event.newValue),
            )

        event.isFieldDescriptionEvent() && event.actionType == EventActionDeleted ->
            stringResource(
                R.string.event_field_description_deleted,
                fieldLabel(event.fieldType),
                quotedValue(parentFieldValueFromMetadata(event.metadataJson)),
                quotedFieldValue(event.fieldType, event.oldValue),
            )

        event.eventType == ContactEventType.CONTACT_ADD ->
            stringResource(R.string.event_contact_created)

        event.eventType == ContactEventType.CONTACT_OPEN ->
            stringResource(R.string.event_contact_opened)

        event.eventType == ContactEventType.CONTACT_DELETE ->
            stringResource(R.string.event_contact_deleted)

        event.eventType == ContactEventType.CONTACT_ARCHIVE ->
            stringResource(R.string.event_contact_archived)

        event.eventType == ContactEventType.FIELD_ADD ->
            stringResource(
                R.string.event_field_added,
                fieldLabel(event.fieldType),
                quotedFieldValue(event.fieldType, event.newValue),
            )

        event.eventType == ContactEventType.FIELD_OPEN ->
            stringResource(R.string.event_field_opened, fieldLabel(event.fieldType))

        event.eventType == ContactEventType.FIELD_EDIT ->
            stringResource(
                R.string.event_field_changed,
                fieldLabel(event.fieldType),
                quotedFieldValue(event.fieldType, event.oldValue),
                quotedFieldValue(event.fieldType, event.newValue),
            )

        event.eventType == ContactEventType.FIELD_DELETE ->
            stringResource(
                R.string.event_field_deleted,
                fieldLabel(event.fieldType),
                quotedFieldValue(event.fieldType, event.oldValue),
            )

        event.eventType == ContactEventType.INITIATIVE ->
            stringResource(R.string.event_initiative, initiativeValueLabel(event.newValue))

        event.entityType == EventEntityContact && event.actionType == EventActionCreated ->
            stringResource(R.string.event_contact_created)

        event.entityType == EventEntityField && event.actionType == EventActionAdded ->
            stringResource(
                R.string.event_field_added,
                fieldLabel(event.fieldType),
                quotedFieldValue(event.fieldType, event.newValue),
            )

        event.entityType == EventEntityField && event.actionType == EventActionUpdated ->
            stringResource(
                R.string.event_field_changed,
                fieldLabel(event.fieldType),
                quotedFieldValue(event.fieldType, event.oldValue),
                quotedFieldValue(event.fieldType, event.newValue),
            )

        event.entityType == EventEntityField && event.actionType == EventActionDeleted ->
            stringResource(
                R.string.event_field_deleted,
                fieldLabel(event.fieldType),
                quotedFieldValue(event.fieldType, event.oldValue),
            )

        event.entityType == EventEntityTag && event.actionType == EventActionAdded ->
            stringResource(R.string.event_tag_added, quotedValue(event.newValue))

        event.entityType == EventEntityTag && event.actionType == EventActionDeleted ->
            stringResource(R.string.event_tag_removed, quotedValue(event.oldValue))

        else -> stringResource(R.string.event_contact)
    }

@Composable
private fun initiativeEventText(
    contactName: String,
    type: InitiativeType,
): String =
    stringResource(
        when (type) {
            InitiativeType.SELF -> R.string.initiative_saved_self_dynamic
            InitiativeType.OTHER -> R.string.initiative_saved_other_dynamic
        },
        contactName,
    )

@Composable
private fun initiativeLabel(type: InitiativeType): String =
    stringResource(
        when (type) {
            InitiativeType.SELF -> R.string.initiative_self
            InitiativeType.OTHER -> R.string.initiative_other
        },
    )

@Composable
private fun initiativeValueLabel(value: String?): String =
    when (value) {
        InitiativeType.SELF.name -> stringResource(R.string.initiative_self)
        InitiativeType.OTHER.name -> stringResource(R.string.initiative_other)
        else -> stringResource(R.string.initiative)
    }

@Composable
private fun initiativeColor(type: InitiativeType): Color =
    if (type == InitiativeType.SELF) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error

@Composable
private fun fieldLabel(fieldType: String?): String =
    when (fieldType) {
        ContactFieldType.Name -> stringResource(R.string.name)
        ContactFieldType.Phone -> stringResource(R.string.phone)
        ContactFieldType.Telegram -> stringResource(R.string.telegram)
        ContactFieldType.Email -> stringResource(R.string.email)
        ContactFieldType.Nickname -> stringResource(R.string.nickname)
        ContactFieldType.Company -> stringResource(R.string.company)
        ContactFieldType.Note -> stringResource(R.string.note)
        ContactFieldType.Link -> stringResource(R.string.link)
        ContactFieldType.Address -> stringResource(R.string.address)
        ContactFieldType.Address2 -> stringResource(R.string.address_2)
        ContactFieldType.Nationality -> stringResource(R.string.nationality)
        ContactFieldType.Age -> stringResource(R.string.age)
        ContactFieldType.GrindrNick -> stringResource(R.string.grindr_nick)
        ContactFieldType.InstagramUsername -> stringResource(R.string.instagram_username)
        ContactFieldType.FacebookUserId -> stringResource(R.string.facebook_user_id)
        ContactFieldType.Photo -> stringResource(R.string.contact_photo)
        ContactFieldType.Tag -> stringResource(R.string.tag)
        else -> stringResource(R.string.field)
    }

@Composable
private fun quotedFieldValue(fieldType: String?, value: String?): String =
    if (fieldType == ContactFieldType.Photo) {
        stringResource(R.string.photo_value_hidden)
    } else {
        quotedValue(value)
    }

private fun quotedValue(value: String?): String =
    "\"${value.orEmpty().replace("\"", "\\\"")}\""

private fun ContactEvent.isFieldDescriptionEvent(): Boolean =
    entityType == EventEntityFieldDescription &&
        metadataJson?.contains("\"subfield\":\"description\"") == true

private fun parentFieldValueFromMetadata(metadataJson: String?): String? {
    val marker = "\"parent_field_value\":\""
    val source = metadataJson ?: return null
    val start = source.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
    val builder = StringBuilder()
    var index = start
    var escaping = false
    while (index < source.length) {
        val char = source[index]
        if (escaping) {
            builder.append(
                when (char) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> char
                },
            )
            escaping = false
        } else {
            when (char) {
                '\\' -> escaping = true
                '"' -> return builder.toString()
                else -> builder.append(char)
            }
        }
        index += 1
    }
    return builder.toString()
}

private fun String.initials(): String {
    val parts = trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    return parts
        .take(2)
        .joinToString(separator = "") { it.first().uppercase() }
        .ifBlank { "?" }
}

private fun openLink(context: Context, value: String) {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return
    val uri = Uri.parse(
        if (trimmed.contains("://")) trimmed else "https://$trimmed",
    )
    openUri(context, uri)
}

private fun copyContactDeepLink(context: Context, detail: ContactDetail) {
    if (detail.publicId.isBlank()) {
        Toast.makeText(context, context.getString(R.string.deep_link_invalid), Toast.LENGTH_SHORT).show()
        return
    }
    val link = ContactDeepLink.create(detail.publicId)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.copy_deep_link), link))
    Toast.makeText(context, context.getString(R.string.deep_link_copied), Toast.LENGTH_SHORT).show()
}

private fun copySavedSearchDeepLink(context: Context, search: SavedSearch) {
    if (search.publicId.isBlank()) {
        Toast.makeText(context, context.getString(R.string.deep_link_invalid), Toast.LENGTH_SHORT).show()
        return
    }
    val link = ContactDeepLink.createSavedSearch(search.publicId)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.copy_deep_link), link))
    Toast.makeText(context, context.getString(R.string.deep_link_copied), Toast.LENGTH_SHORT).show()
}

private fun openPhoneDialer(context: Context, phone: String) {
    val trimmed = phone.trim()
    if (trimmed.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", trimmed, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openSms(context: Context, phone: String) {
    val trimmed = phone.trim()
    if (trimmed.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("sms", trimmed, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openEmail(context: Context, email: String) {
    val trimmed = email.trim()
    if (trimmed.isBlank()) return
    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", trimmed, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, context.getString(R.string.email_app_unavailable), Toast.LENGTH_SHORT).show()
        }
}

private fun openTelegram(context: Context, storedValue: String) {
    val trimmed = storedValue.trim()
    if (trimmed.isBlank()) return
    val username = telegramUsernameFromStoredValue(trimmed)
    val phoneDigits = telegramPhoneDigitsFromStoredValue(trimmed)
    val preferredUri = when {
        username != null -> Uri.parse("tg://resolve?domain=${Uri.encode(username)}")
        phoneDigits != null -> Uri.parse("tg://resolve?phone=${Uri.encode(phoneDigits)}")
        else -> Uri.parse(trimmed)
    }
    val fallbackUri = username?.let { Uri.parse("https://t.me/${Uri.encode(it)}") }
    if (!openUriIfResolvable(context, preferredUri) && fallbackUri != null) {
        openUri(context, fallbackUri)
    }
}

private fun openUriIfResolvable(context: Context, uri: Uri): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val canResolve = intent.resolveActivity(context.packageManager) != null
    if (canResolve) {
        runCatching { context.startActivity(intent) }
            .onSuccess { return true }
    }
    return false
}

private fun telegramLinkForPhone(phone: String): String? {
    val trimmed = phone.trim()
    if (trimmed.isBlank()) return null
    val digits = trimmed.filter { it.isDigit() }
    if (digits.isBlank()) return null
    return "tg://resolve?phone=${Uri.encode(digits)}"
}

private fun telegramLinkForUsername(username: String): String? {
    val normalized = normalizeTelegramUsername(username)
    if (normalized.isBlank()) return null
    return "https://t.me/${Uri.encode(normalized)}"
}

private fun normalizeTelegramUsername(username: String): String =
    username.trim()
        .removePrefix("@")
        .substringAfter("t.me/", missingDelimiterValue = username.trim().removePrefix("@"))
        .substringAfter("telegram.me/", missingDelimiterValue = username.trim().removePrefix("@"))
        .trim('/')
        .takeWhile { it.isLetterOrDigit() || it == '_' }

private fun telegramUsernameFromStoredValue(value: String): String? {
    val trimmed = value.trim()
    val raw = when {
        trimmed.startsWith("@") -> trimmed.removePrefix("@")
        trimmed.startsWith("https://t.me/", ignoreCase = true) -> trimmed.substringAfter("https://t.me/")
        trimmed.startsWith("http://t.me/", ignoreCase = true) -> trimmed.substringAfter("http://t.me/")
        trimmed.startsWith("tg://resolve?domain=", ignoreCase = true) -> trimmed.substringAfter("domain=")
        !trimmed.contains("://") && !trimmed.startsWith("+") -> trimmed
        else -> return null
    }
    val decoded = Uri.decode(raw.substringBefore("?").substringBefore("/"))
    if (decoded.startsWith("+")) return null
    return normalizeTelegramUsername(decoded).takeIf { it.isNotBlank() }
}

private fun telegramPhoneDigitsFromStoredValue(value: String): String? {
    val trimmed = value.trim()
    val raw = when {
        trimmed.startsWith("tg://resolve?phone=", ignoreCase = true) -> trimmed.substringAfter("phone=")
        trimmed.startsWith("https://t.me/+", ignoreCase = true) -> trimmed.substringAfter("https://t.me/+")
        trimmed.startsWith("http://t.me/+", ignoreCase = true) -> trimmed.substringAfter("http://t.me/+")
        else -> return null
    }
    return Uri.decode(raw.substringBefore("&").substringBefore("?"))
        .filter { it.isDigit() }
        .takeIf { it.isNotBlank() }
}

private fun telegramDisplayValue(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    telegramUsernameFromStoredValue(trimmed)?.let { return "@$it" }
    telegramPhoneDigitsFromStoredValue(trimmed)?.let { return "+$it" }
    return trimmed
}

private fun openMaps(context: Context, detail: ContactDetail, linkedPlace: ResolvedAddress?) {
    val label = linkedPlace?.nickname ?: detail.address
    val address = linkedPlace?.address ?: detail.address
    val latitude = linkedPlace?.latitude ?: detail.addressLatitude
    val longitude = linkedPlace?.longitude ?: detail.addressLongitude
    val uris = buildMapsUris(label, address, latitude, longitude)
    if (uris.isEmpty()) return

    val mapsIntent = Intent(Intent.ACTION_VIEW, uris.first())
        .setPackage("com.google.android.apps.maps")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (mapsIntent.resolveActivity(context.packageManager) != null &&
        runCatching { context.startActivity(mapsIntent) }.isSuccess
    ) {
        return
    }
    uris.drop(1).firstOrNull { openUriIfResolvable(context, it) }?.let { return }
    openUri(context, uris.last())
}

internal fun buildMapsUris(
    label: String,
    address: String,
    latitude: Double?,
    longitude: Double?,
): List<Uri> {
    val trimmedLabel = label.trim()
    val trimmedAddress = address.trim()
    if (trimmedLabel.isBlank() && trimmedAddress.isBlank()) return emptyList()
    val result = mutableListOf<Uri>()
    if (latitude != null && longitude != null) {
        val coordinateLabel = trimmedLabel.ifBlank { trimmedAddress }
        result += Uri.parse(
            "geo:$latitude,$longitude?q=" + Uri.encode("$latitude,$longitude($coordinateLabel)"),
        )
    }
    if (trimmedAddress.isNotBlank()) {
        result += Uri.parse("geo:0,0?q=${Uri.encode(trimmedAddress)}")
        result += Uri.parse(
            "https://www.google.com/maps/search/?api=1&query=${Uri.encode(trimmedAddress)}",
        )
    }
    return result.distinct()
}

private fun openUri(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun List<ContactSummary>.sortedByDistance(
    origin: Location,
    direction: ContactHomeSortDirection,
): List<ContactSummary> {
    val comparator =
        compareBy<ContactSummary> { contact ->
            val latitude = contact.addressLatitude
            val longitude = contact.addressLongitude
            if (latitude == null || longitude == null) Float.MAX_VALUE else origin.distanceTo(
                Location("contact").apply {
                    this.latitude = latitude
                    this.longitude = longitude
                },
            )
        }.thenByDescending { it.updatedAt }
    return sortedWith(if (direction == ContactHomeSortDirection.DESC) comparator.reversed() else comparator)
}

private fun CoroutineScope.launchDistanceRefresh(
    context: Context,
    detail: ContactDetail,
    linkedPlace: ResolvedAddress?,
    onStatusChange: (String, Boolean) -> Unit,
) {
    launch {
        onStatusChange(context.getString(R.string.distance_calculating), true)
        val result = calculateDistanceStatus(context, detail, linkedPlace)
        onStatusChange(result, false)
    }
}

private suspend fun calculateDistanceStatus(
    context: Context,
    detail: ContactDetail,
    linkedPlace: ResolvedAddress?,
): String {
    if (detail.address.isBlank() && linkedPlace == null) return context.getString(R.string.distance_missing_address)
    if (!hasLocationPermission(context)) return context.getString(R.string.distance_missing_permission)
    val currentLocation = getCurrentLocationOnce(context)
        ?: return context.getString(R.string.distance_location_unavailable)
    val addressLocation = resolveAddressLocation(context, detail, linkedPlace)
        ?: return context.getString(R.string.distance_geocoding_failed)
    val distanceKm = currentLocation.distanceTo(addressLocation) / 1000.0
    return context.getString(R.string.distance_value, formatDistanceKm(distanceKm))
}

private fun hasLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun callPermissions(): Array<String> =
    arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
    )

private fun hasCallStatePermission(context: Context): Boolean =
    callPermissions().any { permission ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

private suspend fun resolveAddressLocation(
    context: Context,
    detail: ContactDetail,
    linkedPlace: ResolvedAddress?,
): Location? {
    val latitude = linkedPlace?.latitude ?: detail.addressLatitude
    val longitude = linkedPlace?.longitude ?: detail.addressLongitude
    if (latitude != null && longitude != null) {
        return Location("contact").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
    }
    return withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        runCatching {
            Geocoder(context, Locale.getDefault())
                .getFromLocationName(linkedPlace?.address ?: detail.address, 1)
                ?.firstOrNull()
                ?.let { address ->
                    Location("geocoder").apply {
                        this.latitude = address.latitude
                        this.longitude = address.longitude
                    }
                }
        }.getOrNull()
    }
}

@Suppress("DEPRECATION")
private suspend fun getCurrentLocationOnce(context: Context): Location? {
    if (!hasLocationPermission(context)) return null
    val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
    val provider = runCatching {
        locationManager.getProviders(true).firstOrNull { provider ->
            provider == LocationManager.GPS_PROVIDER || provider == LocationManager.NETWORK_PROVIDER
        }
    }.getOrNull() ?: return bestLastKnownLocation(locationManager)

    return withTimeoutOrNull(12_000L) {
        suspendCancellableCoroutine { continuation ->
            @Suppress("MissingPermission")
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }

                override fun onProviderDisabled(provider: String) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
            runCatching {
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }.onFailure {
                locationManager.removeUpdates(listener)
                if (continuation.isActive) {
                    continuation.resume(bestLastKnownLocation(locationManager))
                }
            }
        }
    } ?: bestLastKnownLocation(locationManager)
}

private fun bestLastKnownLocation(locationManager: LocationManager): Location? =
    runCatching {
        locationManager.getProviders(true)
            .mapNotNull { provider ->
                @Suppress("MissingPermission")
                locationManager.getLastKnownLocation(provider)
            }
            .maxByOrNull { it.time }
    }.getOrNull()

private fun formatDistanceKm(distanceKm: Double): String =
    if (distanceKm < 10) {
        String.format(Locale.getDefault(), "%.1f", distanceKm)
    } else {
        distanceKm.roundToInt().toString()
    }

private const val EventEntityContact = "contact"
private const val EventEntityField = "field"
private const val EventEntityFieldDescription = "field_description"
private const val EventEntityTag = "tag"
private const val EventActionCreated = "created"
private const val EventActionAdded = "added"
private const val EventActionUpdated = "updated"
private const val EventActionDeleted = "deleted"

private val WhatsAppIcon: ImageVector
    get() {
        if (_whatsAppIcon != null) return _whatsAppIcon!!
        _whatsAppIcon = ImageVector.Builder(
            name = "WhatsApp",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2.2f)
                curveToRelative(-5.3f, 0f, -9.6f, 4.2f, -9.6f, 9.4f)
                curveToRelative(0f, 1.8f, 0.5f, 3.5f, 1.4f, 5f)
                lineTo(2.5f, 21.8f)
                lineToRelative(5.4f, -1.3f)
                curveToRelative(1.3f, 0.6f, 2.7f, 0.9f, 4.1f, 0.9f)
                curveToRelative(5.3f, 0f, 9.6f, -4.2f, 9.6f, -9.4f)
                curveToRelative(0f, -5.6f, -4.3f, -9.8f, -9.6f, -9.8f)
                close()
                moveTo(12f, 19.3f)
                curveToRelative(-1.3f, 0f, -2.5f, -0.3f, -3.6f, -0.9f)
                lineToRelative(-0.4f, -0.2f)
                lineToRelative(-3.1f, 0.8f)
                lineToRelative(0.8f, -3f)
                lineToRelative(-0.3f, -0.5f)
                curveToRelative(-0.7f, -1.2f, -1.1f, -2.5f, -1.1f, -3.9f)
                curveToRelative(0f, -4.1f, 3.4f, -7.5f, 7.7f, -7.5f)
                reflectiveCurveToRelative(7.7f, 3.4f, 7.7f, 7.5f)
                curveToRelative(0f, 4.3f, -3.4f, 7.7f, -7.7f, 7.7f)
                close()
                moveTo(16.3f, 14.1f)
                curveToRelative(-0.2f, -0.1f, -1.4f, -0.7f, -1.6f, -0.8f)
                curveToRelative(-0.2f, -0.1f, -0.4f, -0.1f, -0.5f, 0.1f)
                lineToRelative(-0.7f, 0.8f)
                curveToRelative(-0.1f, 0.2f, -0.3f, 0.2f, -0.5f, 0.1f)
                curveToRelative(-1.2f, -0.5f, -2.2f, -1.3f, -2.9f, -2.4f)
                curveToRelative(-0.2f, -0.3f, 0f, -0.4f, 0.1f, -0.6f)
                lineToRelative(0.4f, -0.5f)
                curveToRelative(0.1f, -0.2f, 0.1f, -0.3f, 0f, -0.5f)
                lineToRelative(-0.7f, -1.6f)
                curveToRelative(-0.2f, -0.4f, -0.4f, -0.4f, -0.6f, -0.4f)
                horizontalLineToRelative(-0.5f)
                curveToRelative(-0.2f, 0f, -0.5f, 0.1f, -0.7f, 0.3f)
                curveToRelative(-0.2f, 0.2f, -0.9f, 0.9f, -0.9f, 2.1f)
                curveToRelative(0f, 1.3f, 0.9f, 2.5f, 1f, 2.7f)
                curveToRelative(0.1f, 0.2f, 1.8f, 2.8f, 4.5f, 3.9f)
                curveToRelative(0.6f, 0.3f, 1.1f, 0.4f, 1.5f, 0.5f)
                curveToRelative(0.6f, 0.2f, 1.2f, 0.1f, 1.7f, 0.1f)
                curveToRelative(0.5f, -0.1f, 1.4f, -0.6f, 1.6f, -1.1f)
                curveToRelative(0.2f, -0.6f, 0.2f, -1f, 0.1f, -1.1f)
                curveToRelative(-0.1f, -0.1f, -0.2f, -0.2f, -0.4f, -0.3f)
                close()
            }
        }.build()
        return _whatsAppIcon!!
    }

private var _whatsAppIcon: ImageVector? = null

private val TelegramIcon: ImageVector
    get() {
        if (_telegramIcon != null) return _telegramIcon!!
        _telegramIcon = ImageVector.Builder(
            name = "Telegram",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(21.5f, 3.2f)
                lineTo(2.8f, 10.4f)
                curveToRelative(-1f, 0.4f, -0.9f, 1.8f, 0.2f, 2.1f)
                lineToRelative(4.6f, 1.4f)
                lineToRelative(1.8f, 5.6f)
                curveToRelative(0.3f, 0.9f, 1.4f, 1.1f, 2f, 0.4f)
                lineToRelative(2.6f, -2.6f)
                lineToRelative(4.7f, 3.5f)
                curveToRelative(0.9f, 0.6f, 2.1f, 0.1f, 2.3f, -1f)
                lineToRelative(2.8f, -15.1f)
                curveToRelative(0.2f, -1f, -0.8f, -1.8f, -1.8f, -1.5f)
                close()
                moveTo(8.4f, 13.2f)
                lineToRelative(9.1f, -5.8f)
                lineToRelative(-6.7f, 7.1f)
                lineToRelative(-0.3f, 3.1f)
                close()
            }
        }.build()
        return _telegramIcon!!
    }

private var _telegramIcon: ImageVector? = null

private val SignalIcon: ImageVector
    get() {
        if (_signalIcon != null) return _signalIcon!!
        _signalIcon = ImageVector.Builder(
            name = "Signal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 3f)
                curveToRelative(-4.9f, 0f, -8.8f, 3.6f, -8.8f, 8.1f)
                curveToRelative(0f, 2.2f, 0.9f, 4.2f, 2.5f, 5.7f)
                lineToRelative(-0.7f, 3.7f)
                lineToRelative(4.1f, -1.5f)
                curveToRelative(0.9f, 0.3f, 1.9f, 0.4f, 2.9f, 0.4f)
                curveToRelative(4.9f, 0f, 8.8f, -3.6f, 8.8f, -8.1f)
                curveToRelative(0f, -4.7f, -3.9f, -8.3f, -8.8f, -8.3f)
                close()
                moveTo(12f, 5f)
                curveToRelative(3.8f, 0f, 6.8f, 2.7f, 6.8f, 6.1f)
                reflectiveCurveToRelative(-3f, 6.1f, -6.8f, 6.1f)
                curveToRelative(-0.9f, 0f, -1.8f, -0.2f, -2.6f, -0.5f)
                lineToRelative(-0.4f, -0.2f)
                lineToRelative(-1.5f, 0.6f)
                lineToRelative(0.3f, -1.4f)
                lineToRelative(-0.5f, -0.4f)
                curveToRelative(-1.3f, -1.1f, -2f, -2.6f, -2f, -4.2f)
                curveTo(5.2f, 7.7f, 8.2f, 5f, 12f, 5f)
                close()
                moveTo(8.7f, 11.1f)
                curveToRelative(0f, -1.7f, 1.5f, -3.1f, 3.3f, -3.1f)
                reflectiveCurveToRelative(3.3f, 1.4f, 3.3f, 3.1f)
                curveToRelative(0f, 1.8f, -1.5f, 3.2f, -3.3f, 3.2f)
                reflectiveCurveToRelative(-3.3f, -1.4f, -3.3f, -3.2f)
                close()
            }
        }.build()
        return _signalIcon!!
    }

private var _signalIcon: ImageVector? = null

private val LinkIcon: ImageVector
    get() {
        if (_linkIcon != null) return _linkIcon!!
        _linkIcon = ImageVector.Builder(
            name = "Link",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3.9f, 12f)
                curveToRelative(0f, -1.1f, 0.4f, -2.1f, 1.2f, -2.9f)
                lineToRelative(3f, -3f)
                curveToRelative(1.6f, -1.6f, 4.2f, -1.6f, 5.8f, 0f)
                curveToRelative(0.4f, 0.4f, 0.4f, 1f, 0f, 1.4f)
                curveToRelative(-0.4f, 0.4f, -1f, 0.4f, -1.4f, 0f)
                curveToRelative(-0.8f, -0.8f, -2.1f, -0.8f, -3f, 0f)
                lineToRelative(-3f, 3f)
                curveToRelative(-0.8f, 0.8f, -0.8f, 2.1f, 0f, 3f)
                curveToRelative(0.8f, 0.8f, 2.1f, 0.8f, 3f, 0f)
                curveToRelative(0.4f, -0.4f, 1f, -0.4f, 1.4f, 0f)
                reflectiveCurveToRelative(0.4f, 1f, 0f, 1.4f)
                curveToRelative(-1.6f, 1.6f, -4.2f, 1.6f, -5.8f, 0f)
                curveToRelative(-0.8f, -0.8f, -1.2f, -1.8f, -1.2f, -2.9f)
                close()
                moveTo(20.1f, 12f)
                curveToRelative(0f, 1.1f, -0.4f, 2.1f, -1.2f, 2.9f)
                lineToRelative(-3f, 3f)
                curveToRelative(-1.6f, 1.6f, -4.2f, 1.6f, -5.8f, 0f)
                curveToRelative(-0.4f, -0.4f, -0.4f, -1f, 0f, -1.4f)
                reflectiveCurveToRelative(1f, -0.4f, 1.4f, 0f)
                curveToRelative(0.8f, 0.8f, 2.1f, 0.8f, 3f, 0f)
                lineToRelative(3f, -3f)
                curveToRelative(0.8f, -0.8f, 0.8f, -2.1f, 0f, -3f)
                curveToRelative(-0.8f, -0.8f, -2.1f, -0.8f, -3f, 0f)
                curveToRelative(-0.4f, 0.4f, -1f, 0.4f, -1.4f, 0f)
                reflectiveCurveToRelative(-0.4f, -1f, 0f, -1.4f)
                curveToRelative(1.6f, -1.6f, 4.2f, -1.6f, 5.8f, 0f)
                curveToRelative(0.8f, 0.8f, 1.2f, 1.8f, 1.2f, 2.9f)
                close()
            }
        }.build()
        return _linkIcon!!
    }

private var _linkIcon: ImageVector? = null

private val ClockIcon: ImageVector
    get() {
        if (_clockIcon != null) return _clockIcon!!
        _clockIcon = ImageVector.Builder(
            name = "Clock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(11.99f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveToRelative(4.48f, 10f, 9.99f, 10f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                reflectiveCurveTo(17.52f, 2f, 11.99f, 2f)
                close()
                moveTo(12f, 20f)
                curveToRelative(-4.42f, 0f, -8f, -3.58f, -8f, -8f)
                reflectiveCurveToRelative(3.58f, -8f, 8f, -8f)
                reflectiveCurveToRelative(8f, 3.58f, 8f, 8f)
                reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f)
                close()
                moveTo(12.5f, 7f)
                horizontalLineTo(11f)
                verticalLineToRelative(6f)
                lineToRelative(5.25f, 3.15f)
                lineToRelative(0.75f, -1.23f)
                lineToRelative(-4.5f, -2.67f)
                close()
            }
        }.build()
        return _clockIcon!!
    }

private var _clockIcon: ImageVector? = null

private val RefreshIcon: ImageVector
    get() {
        if (_refreshIcon != null) return _refreshIcon!!
        _refreshIcon = ImageVector.Builder(
            name = "Refresh",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17.65f, 6.35f)
                curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                curveToRelative(-4.42f, 0f, -7.99f, 3.58f, -7.99f, 8f)
                reflectiveCurveTo(7.58f, 20f, 12f, 20f)
                curveToRelative(3.73f, 0f, 6.84f, -2.55f, 7.73f, -6f)
                horizontalLineToRelative(-2.08f)
                curveTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f)
                curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
                reflectiveCurveToRelative(2.69f, -6f, 6f, -6f)
                curveToRelative(1.66f, 0f, 3.14f, 0.69f, 4.22f, 1.78f)
                lineTo(13f, 11f)
                horizontalLineToRelative(7f)
                verticalLineTo(4f)
                close()
            }
        }.build()
        return _refreshIcon!!
    }

private var _refreshIcon: ImageVector? = null

private val InstagramIcon: ImageVector
    get() {
        if (_instagramIcon != null) return _instagramIcon!!
        _instagramIcon = ImageVector.Builder(
            name = "Instagram",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7f, 2f)
                horizontalLineToRelative(10f)
                curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f)
                verticalLineToRelative(10f)
                curveToRelative(0f, 2.76f, -2.24f, 5f, -5f, 5f)
                horizontalLineTo(7f)
                curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
                verticalLineTo(7f)
                curveToRelative(0f, -2.76f, 2.24f, -5f, 5f, -5f)
                close()
                moveTo(7f, 4f)
                curveTo(5.35f, 4f, 4f, 5.35f, 4f, 7f)
                verticalLineToRelative(10f)
                curveToRelative(0f, 1.65f, 1.35f, 3f, 3f, 3f)
                horizontalLineToRelative(10f)
                curveToRelative(1.65f, 0f, 3f, -1.35f, 3f, -3f)
                verticalLineTo(7f)
                curveToRelative(0f, -1.65f, -1.35f, -3f, -3f, -3f)
                close()
                moveTo(12f, 7f)
                curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f)
                reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f)
                reflectiveCurveToRelative(-5f, -2.24f, -5f, -5f)
                reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
                close()
                moveTo(12f, 9f)
                curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
                reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                reflectiveCurveToRelative(3f, -1.34f, 3f, -3f)
                reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
                close()
                moveTo(17.5f, 6.25f)
                curveToRelative(0.69f, 0f, 1.25f, 0.56f, 1.25f, 1.25f)
                reflectiveCurveToRelative(-0.56f, 1.25f, -1.25f, 1.25f)
                reflectiveCurveToRelative(-1.25f, -0.56f, -1.25f, -1.25f)
                reflectiveCurveToRelative(0.56f, -1.25f, 1.25f, -1.25f)
                close()
            }
        }.build()
        return _instagramIcon!!
    }

private var _instagramIcon: ImageVector? = null

private val FacebookIcon: ImageVector
    get() {
        if (_facebookIcon != null) return _facebookIcon!!
        _facebookIcon = ImageVector.Builder(
            name = "Facebook",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(22f, 12f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                reflectiveCurveTo(2f, 6.48f, 2f, 12f)
                curveToRelative(0f, 4.99f, 3.66f, 9.13f, 8.44f, 9.88f)
                verticalLineToRelative(-6.99f)
                horizontalLineTo(7.9f)
                verticalLineTo(12f)
                horizontalLineToRelative(2.54f)
                verticalLineTo(9.8f)
                curveToRelative(0f, -2.51f, 1.49f, -3.89f, 3.77f, -3.89f)
                curveToRelative(1.09f, 0f, 2.23f, 0.2f, 2.23f, 0.2f)
                verticalLineToRelative(2.45f)
                horizontalLineToRelative(-1.25f)
                curveToRelative(-1.24f, 0f, -1.63f, 0.77f, -1.63f, 1.56f)
                verticalLineTo(12f)
                horizontalLineToRelative(2.77f)
                lineToRelative(-0.44f, 2.89f)
                horizontalLineToRelative(-2.33f)
                verticalLineToRelative(6.99f)
                curveTo(18.34f, 21.13f, 22f, 16.99f, 22f, 12f)
                close()
            }
        }.build()
        return _facebookIcon!!
    }

private var _facebookIcon: ImageVector? = null

private val MessengerIcon: ImageVector
    get() {
        if (_messengerIcon != null) return _messengerIcon!!
        _messengerIcon = ImageVector.Builder(
            name = "Messenger",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.14f, 2f, 11.25f)
                curveToRelative(0f, 2.91f, 1.45f, 5.51f, 3.72f, 7.2f)
                verticalLineTo(22f)
                lineToRelative(3.4f, -1.86f)
                curveToRelative(0.91f, 0.25f, 1.88f, 0.38f, 2.88f, 0.38f)
                curveToRelative(5.52f, 0f, 10f, -4.14f, 10f, -9.27f)
                curveTo(22f, 6.14f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(13.04f, 14.46f)
                lineToRelative(-2.55f, -2.72f)
                lineToRelative(-4.99f, 2.72f)
                lineToRelative(5.49f, -5.82f)
                lineToRelative(2.62f, 2.72f)
                lineToRelative(4.92f, -2.72f)
                close()
            }
        }.build()
        return _messengerIcon!!
    }

private var _messengerIcon: ImageVector? = null
