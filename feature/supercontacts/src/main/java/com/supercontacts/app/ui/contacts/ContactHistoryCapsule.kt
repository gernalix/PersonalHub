package com.supercontacts.app.ui.contacts

import com.supercontacts.app.data.repository.ContactEvent
import com.supercontacts.app.data.repository.ContactsRepository
import com.supercontacts.app.data.repository.GlobalContactEvent
import com.supercontacts.app.data.repository.HistoryCalendarState
import com.supercontacts.app.data.repository.HistoryDateRange
import com.supercontacts.app.data.repository.HistoryRangeDetails
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContactHistoryState(
    val historyEvents: List<ContactEvent> = emptyList(),
    val globalHistoryEvents: List<GlobalContactEvent> = emptyList(),
    val globalHistoryAscending: Boolean = false,
    val historyIncludeContact: Boolean = true,
    val historyIncludeField: Boolean = true,
    val historyIncludeInitiative: Boolean = true,
    val historyCalendar: HistoryCalendarState =
        HistoryCalendarState(month = YearMonth.now(ZoneId.systemDefault())),
    val selectedHistoryRange: HistoryDateRange =
        HistoryDateRange(
            startDate = LocalDate.now(ZoneId.systemDefault()),
            endDate = LocalDate.now(ZoneId.systemDefault()),
        ),
    val historyRangeDetails: HistoryRangeDetails? = null,
)

class ContactHistoryCapsule(
    private val repository: ContactsRepository,
    private val status: ContactOperationStatusCapsule,
    private val currentContactId: () -> Long?,
    private val scope: CoroutineScope,
) : ContactHistoryOwner {
    private val mutableState = MutableStateFlow(ContactHistoryState())
    private var observedContactId: Long? = null
    private var historyJob: Job? = null
    private var globalHistoryJob: Job? = null
    private var historyCalendarJob: Job? = null
    private var historyRangeJob: Job? = null
    private var historyRangeAnchor: LocalDate? = null

    override val state: StateFlow<ContactHistoryState> = mutableState.asStateFlow()

    override fun observeContactHistory(contactId: Long) {
        observedContactId = contactId
        historyJob?.cancel()
        historyJob = scope.launch {
            val snapshot = mutableState.value
            repository.getContactEvents(
                contactId = contactId,
                includeContact = snapshot.historyIncludeContact,
                includeField = snapshot.historyIncludeField,
                includeInitiative = snapshot.historyIncludeInitiative,
            ).collect { events ->
                mutableState.value = mutableState.value.copy(historyEvents = events)
            }
        }
    }

    override fun clearHistory() {
        historyJob?.cancel()
        observedContactId = null
        mutableState.value = mutableState.value.copy(historyEvents = emptyList())
    }

    override fun observeGlobalHistory() {
        globalHistoryJob?.cancel()
        globalHistoryJob = scope.launch {
            val snapshot = mutableState.value
            repository.getAllEvents(
                ascending = snapshot.globalHistoryAscending,
                includeContact = snapshot.historyIncludeContact,
                includeField = snapshot.historyIncludeField,
                includeInitiative = snapshot.historyIncludeInitiative,
            ).collect { events ->
                mutableState.value = mutableState.value.copy(globalHistoryEvents = events)
            }
        }
    }

    override fun setHistoryIncludeContact(include: Boolean) {
        mutableState.value = mutableState.value.copy(historyIncludeContact = include)
        refreshObservers()
    }

    override fun setHistoryIncludeField(include: Boolean) {
        mutableState.value = mutableState.value.copy(historyIncludeField = include)
        refreshObservers()
    }

    override fun setHistoryIncludeInitiative(include: Boolean) {
        mutableState.value = mutableState.value.copy(historyIncludeInitiative = include)
        refreshObservers()
    }

    override fun toggleGlobalHistorySort() {
        mutableState.value = mutableState.value.copy(
            globalHistoryAscending = !mutableState.value.globalHistoryAscending,
        )
        if (globalHistoryJob != null) {
            observeGlobalHistory()
        }
    }

    override fun clearGlobalHistory() {
        globalHistoryJob?.cancel()
        globalHistoryJob = null
        mutableState.value = mutableState.value.copy(globalHistoryEvents = emptyList())
    }

    override fun observeHistoryCalendar() {
        historyCalendarJob?.cancel()
        historyCalendarJob = scope.launch {
            val snapshot = mutableState.value
            repository.getHistoryCalendar(
                month = snapshot.historyCalendar.month,
                includeContact = snapshot.historyIncludeContact,
                includeField = snapshot.historyIncludeField,
                includeInitiative = snapshot.historyIncludeInitiative,
            ).collect { calendar ->
                mutableState.value = mutableState.value.copy(historyCalendar = calendar)
            }
        }
    }

    override fun observeHistoryRange() {
        historyRangeJob?.cancel()
        historyRangeJob = scope.launch {
            val snapshot = mutableState.value
            repository.getHistoryEventsForRange(
                range = snapshot.selectedHistoryRange,
                includeContact = snapshot.historyIncludeContact,
                includeField = snapshot.historyIncludeField,
                includeInitiative = snapshot.historyIncludeInitiative,
            ).collect { details ->
                mutableState.value = mutableState.value.copy(historyRangeDetails = details)
            }
        }
    }

    override fun previousHistoryMonth() {
        mutableState.value = mutableState.value.copy(
            historyCalendar = mutableState.value.historyCalendar.copy(
                month = mutableState.value.historyCalendar.month.minusMonths(1),
                days = emptyList(),
            ),
        )
        if (historyCalendarJob != null) {
            observeHistoryCalendar()
        }
    }

    override fun nextHistoryMonth() {
        mutableState.value = mutableState.value.copy(
            historyCalendar = mutableState.value.historyCalendar.copy(
                month = mutableState.value.historyCalendar.month.plusMonths(1),
                days = emptyList(),
            ),
        )
        if (historyCalendarJob != null) {
            observeHistoryCalendar()
        }
    }

    override fun selectHistoryDate(date: LocalDate) {
        val anchor = historyRangeAnchor
        val nextRange = if (anchor == null) {
            historyRangeAnchor = date
            HistoryDateRange(startDate = date, endDate = date)
        } else {
            historyRangeAnchor = null
            if (date.isBefore(anchor)) {
                HistoryDateRange(startDate = date, endDate = anchor)
            } else {
                HistoryDateRange(startDate = anchor, endDate = date)
            }
        }
        mutableState.value = mutableState.value.copy(selectedHistoryRange = nextRange)
        if (historyRangeJob != null) {
            observeHistoryRange()
        }
    }

    override fun clearHistoryCalendar() {
        historyCalendarJob?.cancel()
        historyRangeJob?.cancel()
        historyCalendarJob = null
        historyRangeJob = null
        historyRangeAnchor = null
        mutableState.value = mutableState.value.copy(
            historyCalendar = HistoryCalendarState(month = mutableState.value.historyCalendar.month),
            historyRangeDetails = null,
        )
    }

    override fun updateHistoryTimestamp(event: ContactEvent, timestampUtc: Long) {
        scope.launch {
            status.save {
                repository.updateHistoryTimestamp(event, timestampUtc)
            }
        }
    }

    private fun refreshObservers() {
        val contactId = observedContactId ?: currentContactId()
        if (historyJob != null && contactId != null) {
            observeContactHistory(contactId)
        }
        if (globalHistoryJob != null) {
            observeGlobalHistory()
        }
        if (historyCalendarJob != null) {
            observeHistoryCalendar()
        }
        if (historyRangeJob != null) {
            observeHistoryRange()
        }
    }
}
