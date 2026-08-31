package com.supercontacts.app.ui.contacts

import com.supercontacts.app.data.repository.ContactInitiative
import com.supercontacts.app.data.repository.ContactsRepository
import com.supercontacts.app.data.repository.GlobalContactInitiative
import com.supercontacts.app.data.repository.InitiativeCalendarState
import com.supercontacts.app.data.repository.InitiativeDayDetails
import com.supercontacts.app.data.repository.InitiativeType
import com.supercontacts.app.data.repository.InitiativeUndoRequest
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContactInitiativeState(
    val homeInitiatives: List<GlobalContactInitiative> = emptyList(),
    val contactInitiatives: List<ContactInitiative> = emptyList(),
    val contactInitiativeAscending: Boolean = false,
    val globalInitiatives: List<GlobalContactInitiative> = emptyList(),
    val globalInitiativeAscending: Boolean = false,
    val initiativeCalendar: InitiativeCalendarState =
        InitiativeCalendarState(month = YearMonth.now(ZoneId.systemDefault())),
    val selectedInitiativeDay: LocalDate? = null,
    val initiativeDayDetails: InitiativeDayDetails? = null,
)

class ContactInitiativeCapsule(
    private val repository: ContactsRepository,
    private val status: ContactOperationStatusCapsule,
    private val currentContactId: () -> Long?,
    private val scope: CoroutineScope,
) : ContactInitiativeOwner {
    private val mutableState = MutableStateFlow(ContactInitiativeState())
    private val initiativeUndoRequestsMutable = MutableSharedFlow<InitiativeUndoRequest>(
        extraBufferCapacity = 32,
    )
    private var contactInitiativesJob: Job? = null
    private var globalInitiativesJob: Job? = null
    private var initiativeCalendarJob: Job? = null
    private var initiativeDayJob: Job? = null

    override val state: StateFlow<ContactInitiativeState> = mutableState.asStateFlow()

    override val initiativeUndoRequests: SharedFlow<InitiativeUndoRequest> =
        initiativeUndoRequestsMutable.asSharedFlow()

    init {
        observeHomeInitiatives()
    }

    override fun observeContactInitiatives(contactId: Long) {
        contactInitiativesJob?.cancel()
        contactInitiativesJob = scope.launch {
            repository.getContactInitiatives(
                contactId,
                mutableState.value.contactInitiativeAscending,
            ).collect { initiatives ->
                mutableState.value = mutableState.value.copy(contactInitiatives = initiatives)
            }
        }
    }

    override fun toggleContactInitiativeSort() {
        mutableState.value = mutableState.value.copy(
            contactInitiativeAscending = !mutableState.value.contactInitiativeAscending,
        )
        currentContactId()?.let(::observeContactInitiatives)
    }

    override fun clearContactInitiatives() {
        contactInitiativesJob?.cancel()
        contactInitiativesJob = null
        mutableState.value = mutableState.value.copy(contactInitiatives = emptyList())
    }

    override fun observeGlobalInitiatives() {
        globalInitiativesJob?.cancel()
        globalInitiativesJob = scope.launch {
            repository.getAllInitiatives(mutableState.value.globalInitiativeAscending).collect { initiatives ->
                mutableState.value = mutableState.value.copy(globalInitiatives = initiatives)
            }
        }
    }

    override fun toggleGlobalInitiativeSort() {
        mutableState.value = mutableState.value.copy(
            globalInitiativeAscending = !mutableState.value.globalInitiativeAscending,
        )
        if (globalInitiativesJob != null) {
            observeGlobalInitiatives()
        }
    }

    override fun clearGlobalInitiatives() {
        globalInitiativesJob?.cancel()
        globalInitiativesJob = null
        mutableState.value = mutableState.value.copy(globalInitiatives = emptyList())
    }

    override fun observeInitiativeCalendar() {
        initiativeCalendarJob?.cancel()
        initiativeCalendarJob = scope.launch {
            repository.getInitiativeCalendar(mutableState.value.initiativeCalendar.month).collect { calendar ->
                mutableState.value = mutableState.value.copy(initiativeCalendar = calendar)
            }
        }
    }

    override fun previousInitiativeMonth() {
        mutableState.value = mutableState.value.copy(
            initiativeCalendar = mutableState.value.initiativeCalendar.copy(
                month = mutableState.value.initiativeCalendar.month.minusMonths(1),
                days = emptyList(),
            ),
        )
        if (initiativeCalendarJob != null) {
            observeInitiativeCalendar()
        }
    }

    override fun nextInitiativeMonth() {
        mutableState.value = mutableState.value.copy(
            initiativeCalendar = mutableState.value.initiativeCalendar.copy(
                month = mutableState.value.initiativeCalendar.month.plusMonths(1),
                days = emptyList(),
            ),
        )
        if (initiativeCalendarJob != null) {
            observeInitiativeCalendar()
        }
    }

    override fun clearInitiativeCalendar() {
        initiativeCalendarJob?.cancel()
        initiativeCalendarJob = null
        mutableState.value = mutableState.value.copy(
            initiativeCalendar = InitiativeCalendarState(month = mutableState.value.initiativeCalendar.month),
        )
    }

    override fun selectInitiativeDay(date: LocalDate) {
        mutableState.value = mutableState.value.copy(selectedInitiativeDay = date)
        initiativeDayJob?.cancel()
        initiativeDayJob = scope.launch {
            repository.getInitiativesForDay(date).collect { details ->
                mutableState.value = mutableState.value.copy(initiativeDayDetails = details)
            }
        }
    }

    override fun clearSelectedInitiativeDay() {
        initiativeDayJob?.cancel()
        initiativeDayJob = null
        mutableState.value = mutableState.value.copy(
            selectedInitiativeDay = null,
            initiativeDayDetails = null,
        )
    }

    override fun recordInitiative(
        contactId: Long,
        initiativeType: InitiativeType,
        contactName: String,
    ) {
        scope.launch {
            status.setError(null)
            runCatching {
                repository.recordInitiative(contactId, initiativeType)
            }.onSuccess { initiativeId ->
                initiativeUndoRequestsMutable.tryEmit(
                    InitiativeUndoRequest(
                        initiativeId = initiativeId,
                        initiativeType = initiativeType,
                        contactName = contactName,
                    ),
                )
            }.onFailure { error ->
                status.setError(error.message ?: "Operation failed.")
            }
        }
    }

    override fun undoInitiative(initiativeId: Long) {
        scope.launch {
            status.setError(null)
            runCatching {
                repository.undoInitiative(initiativeId)
            }.onFailure { error ->
                status.setError(error.message ?: "Operation failed.")
            }
        }
    }

    override fun deleteInitiative(initiativeId: Long) {
        scope.launch {
            status.setError(null)
            runCatching {
                repository.deleteInitiative(initiativeId)
            }.onFailure { error ->
                status.setError(error.message ?: "Operation failed.")
            }
        }
    }

    private fun observeHomeInitiatives() {
        scope.launch {
            repository.getRecentInitiatives().collect { initiatives ->
                mutableState.value = mutableState.value.copy(homeInitiatives = initiatives)
            }
        }
    }
}
