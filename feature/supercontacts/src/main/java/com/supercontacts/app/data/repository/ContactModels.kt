package com.supercontacts.app.data.repository

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ContactSummary(
    val id: Long,
    val publicId: String,
    val displayName: String,
    val subtitle: String?,
    val phone: String = "",
    val searchMatches: List<ContactSearchMatch> = emptyList(),
    val tags: List<ContactTag> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastInteractionAt: Long? = null,
    val openCount: Int = 0,
    val initiativeCount: Int = 0,
    val company: String = "",
    val address: String = "",
    val address2: String = "",
    val addressLatitude: Double? = null,
    val addressLongitude: Double? = null,
    val addressPlaceId: String? = null,
    val nationality: String = "",
    val nationalityCountryCode: String? = null,
    val age: ContactAge? = null,
    val photoPath: String = "",
)

data class ContactSearchMatch(
    val fieldType: String,
    val value: String,
)

data class ContactFieldSuggestion(
    val fieldType: String,
    val value: String,
    val usageCount: Int,
    val lastUsedAt: Long,
)

data class ContactPhotoReference(
    val contactId: Long,
    val photoPath: String,
)

data class ContactTag(
    val id: Long,
    val name: String,
)

data class SavedSearch(
    val id: Long,
    val publicId: String,
    val title: String,
    val query: String,
    val tags: List<ContactTag>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ContactFieldTimestamp(
    val addedAt: Long,
    val editedAt: Long?,
)

data class ContactFieldDescriptor(
    val id: Long,
    val fieldType: String,
    val value: String,
    val description: String,
    val timestamp: ContactFieldTimestamp,
)

data class ContactEvent(
    val id: Long,
    val contactId: Long,
    val entityType: String,
    val actionType: String,
    val eventType: String,
    val fieldType: String?,
    val oldValue: String?,
    val newValue: String?,
    val occurredAt: Long,
    val metadataJson: String?,
)

data class GlobalContactEvent(
    val event: ContactEvent,
    val contactDisplayName: String,
)

data class HistoryCalendarDaySummary(
    val date: LocalDate,
    val eventCount: Int,
)

data class HistoryCalendarState(
    val month: YearMonth,
    val days: List<HistoryCalendarDaySummary> = emptyList(),
)

data class HistoryDateRange(
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class HistoryRangeDetails(
    val range: HistoryDateRange,
    val events: List<GlobalContactEvent>,
)

data class ContactStats(
    val addedBy: String?,
    val lastModifiedAt: Long?,
    val lastOpenedAt: Long?,
    val lastSelfInitiativeAt: Long?,
    val lastOtherInitiativeAt: Long?,
    val openCount: Int,
    val knownSinceAt: Long?,
)

data class ContactDetail(
    val id: Long,
    val publicId: String,
    val name: String,
    val phone: String,
    val telegram: String,
    val email: String,
    val nickname: String,
    val company: String,
    val note: String,
    val link: String,
    val address: String,
    val address2: String,
    val addressLatitude: Double?,
    val addressLongitude: Double?,
    val addressPlaceId: String?,
    val nationality: String,
    val nationalityCountryCode: String?,
    val age: ContactAge?,
    val grindrNick: String,
    val instagramUsername: String,
    val facebookUserId: String,
    val photoPath: String,
    val displayName: String,
    val subtitle: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val fieldTimestamps: Map<String, ContactFieldTimestamp>,
    val fieldDescriptors: Map<String, ContactFieldDescriptor>,
    val messagingLinks: List<ContactMessagingLink> = emptyList(),
    val tags: List<ContactTag> = emptyList(),
) {
    fun toInput(): ContactInput =
        ContactInput(
            name = name,
            phone = phone,
            telegram = telegram,
            email = email,
            nickname = nickname,
            company = company,
            note = note,
            link = link,
            address = address,
            address2 = address2,
            addressLatitude = addressLatitude,
            addressLongitude = addressLongitude,
            addressPlaceId = addressPlaceId,
            nationality = nationality,
            nationalityCountryCode = nationalityCountryCode,
            birthDate = age?.birthDateString.orEmpty(),
            manualAge = age?.manualAgeString.orEmpty(),
            grindrNick = grindrNick,
            instagramUsername = instagramUsername,
            facebookUserId = facebookUserId,
            photoPath = photoPath,
        )
}

enum class ContactAgeSource {
    BIRTH_DATE,
    MANUAL,
}

data class ContactAge(
    val source: ContactAgeSource,
    val storedValue: String,
    val referenceAt: Long,
    val years: Int,
    val birthDate: LocalDate? = null,
) {
    val birthDateString: String
        get() = if (source == ContactAgeSource.BIRTH_DATE) storedValue else ""

    val manualAgeString: String
        get() = if (source == ContactAgeSource.MANUAL) storedValue else ""
}

enum class InitiativeType {
    SELF,
    OTHER,
}

data class ContactInitiative(
    val id: Long,
    val contactId: Long,
    val timestampUtc: Long,
    val initiativeType: InitiativeType,
)

data class GlobalContactInitiative(
    val initiative: ContactInitiative,
    val contactDisplayName: String,
)

data class InitiativeDaySummary(
    val date: LocalDate,
    val selfCount: Int,
    val otherCount: Int,
)

data class InitiativeDayDetails(
    val date: LocalDate,
    val selfCount: Int,
    val otherCount: Int,
    val events: List<GlobalContactInitiative>,
)

data class InitiativeCalendarState(
    val month: YearMonth,
    val days: List<InitiativeDaySummary> = emptyList(),
)

data class InitiativeUndoRequest(
    val initiativeId: Long,
    val initiativeType: InitiativeType,
    val contactName: String,
)

object ContactEventType {
    const val CONTACT_ADD = "CONTACT_ADD"
    const val CONTACT_OPEN = "CONTACT_OPEN"
    const val CONTACT_DELETE = "CONTACT_DELETE"
    const val CONTACT_ARCHIVE = "CONTACT_ARCHIVE"
    const val FIELD_ADD = "FIELD_ADD"
    const val FIELD_OPEN = "FIELD_OPEN"
    const val FIELD_EDIT = "FIELD_EDIT"
    const val FIELD_DELETE = "FIELD_DELETE"
    const val INITIATIVE = "INITIATIVE"

    val contactTypes = setOf(CONTACT_ADD, CONTACT_OPEN, CONTACT_DELETE, CONTACT_ARCHIVE)
    val fieldTypes = setOf(FIELD_ADD, FIELD_OPEN, FIELD_EDIT, FIELD_DELETE)
}

enum class ContactHomeSort {
    NAME,
    UPDATED,
    LAST_INTERACTION,
    OPEN_COUNT,
    CREATED,
    INITIATIVE_COUNT,
    DISTANCE,
    COMPANY,
}

enum class ContactHomeSortDirection {
    ASC,
    DESC,
}

data class ContactHomeSortState(
    val criterion: ContactHomeSort = ContactHomeSort.NAME,
    val direction: ContactHomeSortDirection = ContactHomeSortDirection.ASC,
)

data class ContactPhotoCleanupResult(
    val oldPhotoPath: String?,
    val changed: Boolean,
)

data class CallOverlayContactMatch(
    val publicId: String,
    val displayName: String,
    val phone: String,
)

object ContactDeepLink {
    const val Scheme = "supercontacts"
    const val Host = "contact"
    const val SearchHost = "search"

    fun create(publicId: String): String =
        "$Scheme://$Host/${UriEncoder.encode(publicId)}"

    fun createSavedSearch(publicId: String): String =
        "$Scheme://$SearchHost/${UriEncoder.encode(publicId)}"

    fun parse(value: String?): String? {
        val uri = value?.trim()?.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) } ?: return null
        if (!uri.scheme.equals(Scheme, ignoreCase = true)) return null
        if (!uri.host.equals(Host, ignoreCase = true)) return null
        return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    fun parseSavedSearch(value: String?): String? {
        val uri = value?.trim()?.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) } ?: return null
        if (!uri.scheme.equals(Scheme, ignoreCase = true)) return null
        if (!uri.host.equals(SearchHost, ignoreCase = true)) return null
        return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}

private object UriEncoder {
    fun encode(value: String): String = android.net.Uri.encode(value)
}
