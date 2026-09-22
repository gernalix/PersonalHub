package com.supercontacts.app.data.repository

import androidx.room.withTransaction
import com.supercontacts.app.data.local.ContactEntity
import com.supercontacts.app.data.local.ContactEventEntity
import com.supercontacts.app.data.local.ContactEventWithContactName
import com.supercontacts.app.data.local.ContactFieldEntity
import com.supercontacts.app.data.local.ContactHomeMetricRow
import com.supercontacts.app.data.local.ContactInitiativeEntity
import com.supercontacts.app.data.local.ContactInitiativeWithContactName
import com.supercontacts.app.data.local.ContactMessagingLinkEntity
import com.supercontacts.app.data.local.ContactTagCrossRef
import com.supercontacts.app.data.local.ContactWithFieldsAndTags
import com.supercontacts.app.data.local.ContactsDao
import com.supercontacts.app.data.local.HistoryCalendarDayRow
import com.supercontacts.app.data.local.InitiativeCalendarDayRow
import com.supercontacts.app.data.local.SavedSearchEntity
import com.supercontacts.app.data.local.SavedSearchTagCrossRef
import com.supercontacts.app.data.local.SavedSearchWithTags
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.supercontacts.app.data.local.TagEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ContactsRepository(
    private val database: PersonalHubDatabase,
) {
    private val dao: ContactsDao = database.contactsDao()

    fun listContacts(sort: ContactHomeSortState = ContactHomeSortState()): Flow<List<ContactSummary>> =
        contactsWithHomeMetrics(dao.listContacts(), sort)

    fun searchContacts(query: String, sort: ContactHomeSortState = ContactHomeSortState()): Flow<List<ContactSummary>> {
        val trimmedQuery = query.trim()
        val normalizedQuery = PhoneNormalizer.normalize(trimmedQuery)
        return contactsWithHomeMetrics(
            contacts = dao.searchContacts(
                query = trimmedQuery,
                normalizedQuery = normalizedQuery,
            ),
            sort = sort,
            searchQuery = trimmedQuery,
            normalizedSearchQuery = normalizedQuery,
        )
    }

    fun filterContacts(
        query: String,
        tagIds: List<Long>,
        sort: ContactHomeSortState = ContactHomeSortState(),
    ): Flow<List<ContactSummary>> {
        val distinctTagIds = tagIds.distinct()
        if (distinctTagIds.isEmpty()) {
            return if (query.isBlank()) listContacts(sort) else searchContacts(query, sort)
        }
        val trimmedQuery = query.trim()
        val normalizedQuery = PhoneNormalizer.normalize(trimmedQuery)
        return contactsWithHomeMetrics(
            contacts = dao.searchContactsByTags(
                query = trimmedQuery,
                normalizedQuery = normalizedQuery,
                tagIds = distinctTagIds,
                tagCount = distinctTagIds.size,
            ),
            sort = sort,
            searchQuery = trimmedQuery,
            normalizedSearchQuery = normalizedQuery,
        )
    }

    fun getContactsByTag(tagId: Long, sort: ContactHomeSortState = ContactHomeSortState()): Flow<List<ContactSummary>> =
        contactsWithHomeMetrics(dao.getContactsByTag(tagId), sort)

    fun getContactById(contactId: Long): Flow<ContactDetail?> =
        dao.observeContactById(contactId).map { it?.toDetail() }

    suspend fun findContactIdByPublicId(publicId: String): Long? =
        dao.getContactByPublicId(publicId.trim())?.contact?.id

    suspend fun findContactIdByPhone(number: String): Long? =
        dao.getContactByNormalizedPhone(PhoneNormalizer.normalize(number))?.contact?.id

    suspend fun findContactForCallOverlay(number: String): CallOverlayContactMatch? =
        dao.getContactByNormalizedPhone(PhoneNormalizer.normalize(number))?.toDetail()?.let { detail ->
            CallOverlayContactMatch(
                publicId = detail.publicId,
                displayName = detail.displayName,
                phone = detail.phone.ifBlank { number },
            )
        }

    fun getContactStats(contactId: Long): Flow<ContactStats> =
        combine(
            dao.observeContactById(contactId),
            dao.observeEventsByContact(
                contactId = contactId,
                includeContact = true,
                includeField = true,
                includeInitiative = true,
            ),
            dao.observeInitiativesByContact(contactId, ascending = true),
        ) { contact, events, initiatives ->
            ContactStats(
                addedBy = null,
                lastModifiedAt = contact?.contact?.updatedAt,
                lastOpenedAt = events
                    .filter { it.eventType == ContactEventType.CONTACT_OPEN }
                    .maxOfOrNull { it.occurredAt },
                lastSelfInitiativeAt = initiatives
                    .filter { it.initiativeType == InitiativeType.SELF.name }
                    .maxOfOrNull { it.timestampUtc },
                lastOtherInitiativeAt = initiatives
                    .filter { it.initiativeType == InitiativeType.OTHER.name }
                    .maxOfOrNull { it.timestampUtc },
                openCount = events.count { it.eventType == ContactEventType.CONTACT_OPEN },
                knownSinceAt = contact?.contact?.createdAt,
            )
        }

    fun getContactEvents(
        contactId: Long,
        includeContact: Boolean,
        includeField: Boolean,
        includeInitiative: Boolean,
    ): Flow<List<ContactEvent>> =
        dao.observeEventsByContact(
            contactId = contactId,
            includeContact = includeContact,
            includeField = includeField,
            includeInitiative = includeInitiative,
        ).map { events -> events.map { it.toModel() } }

    fun getAllEvents(
        ascending: Boolean,
        includeContact: Boolean,
        includeField: Boolean,
        includeInitiative: Boolean,
    ): Flow<List<GlobalContactEvent>> =
        dao.getAllEvents(
            ascending = ascending,
            includeContact = includeContact,
            includeField = includeField,
            includeInitiative = includeInitiative,
        ).map { events -> events.map { it.toGlobalModel() } }

    fun getHistoryCalendar(
        month: YearMonth,
        includeContact: Boolean,
        includeField: Boolean,
        includeInitiative: Boolean,
    ): Flow<HistoryCalendarState> {
        val (startUtcInclusive, endUtcExclusive) = month.toUtcRange()
        return dao.observeHistoryCalendarMonth(
            startUtcInclusive = startUtcInclusive,
            endUtcExclusive = endUtcExclusive,
            includeContact = includeContact,
            includeField = includeField,
            includeInitiative = includeInitiative,
        ).map { rows ->
            HistoryCalendarState(
                month = month,
                days = rows.map { it.toModel() },
            )
        }
    }

    fun getHistoryEventsForRange(
        range: HistoryDateRange,
        includeContact: Boolean,
        includeField: Boolean,
        includeInitiative: Boolean,
    ): Flow<HistoryRangeDetails> {
        val (startUtcInclusive, endUtcExclusive) = range.startDate.toUtcRange(endExclusive = range.endDate.plusDays(1))
        return dao.observeEventsForRange(
            startUtcInclusive = startUtcInclusive,
            endUtcExclusive = endUtcExclusive,
            includeContact = includeContact,
            includeField = includeField,
            includeInitiative = includeInitiative,
        ).map { events ->
            HistoryRangeDetails(
                range = range,
                events = events.map { it.toGlobalModel() },
            )
        }
    }

    fun getContactInitiatives(
        contactId: Long,
        ascending: Boolean,
    ): Flow<List<ContactInitiative>> =
        dao.observeInitiativesByContact(contactId, ascending).map { initiatives ->
            initiatives.map { it.toModel() }
        }

    fun getAllInitiatives(ascending: Boolean): Flow<List<GlobalContactInitiative>> =
        dao.observeAllInitiatives(ascending).map { initiatives ->
            initiatives.map { it.toGlobalInitiativeModel() }
        }

    fun getRecentInitiatives(limit: Int = 5): Flow<List<GlobalContactInitiative>> =
        getAllInitiatives(ascending = false).map { it.take(limit.coerceAtLeast(1)) }

    fun getInitiativeCalendar(month: YearMonth): Flow<InitiativeCalendarState> {
        val (startUtcInclusive, endUtcExclusive) = month.toUtcRange()
        return dao.observeInitiativeCalendarMonth(startUtcInclusive, endUtcExclusive).map { rows ->
            InitiativeCalendarState(
                month = month,
                days = rows.map { it.toModel() },
            )
        }
    }

    fun getInitiativesForDay(date: LocalDate): Flow<InitiativeDayDetails> {
        val (startUtcInclusive, endUtcExclusive) = date.toUtcRange()
        return dao.observeInitiativesForDay(startUtcInclusive, endUtcExclusive).map { initiatives ->
            val events = initiatives.map { it.toGlobalInitiativeModel() }
            InitiativeDayDetails(
                date = date,
                selfCount = events.count { it.initiative.initiativeType == InitiativeType.SELF },
                otherCount = events.count { it.initiative.initiativeType == InitiativeType.OTHER },
                events = events,
            )
        }
    }

    suspend fun addTagToContact(contactId: Long, tagName: String): ContactTag {
        val cleanedName = tagName.trim()
        require(cleanedName.isNotBlank()) { "Tag name is required." }
        var changed = false
        val result = database.withTransaction {
            val now = System.currentTimeMillis()
            val tag = getOrCreateTag(cleanedName, now)
            if (addTagToContactInternal(contactId, tag, now)) {
                changed = true
            }
            tag.toModel()
        }
        if (changed) {
        }
        return result
    }

    suspend fun addTagToContacts(contactIds: List<Long>, tagName: String): Int {
        val cleanedName = tagName.trim()
        require(cleanedName.isNotBlank()) { "Tag name is required." }
        val distinctContactIds = contactIds.distinct()
        var changedCount = 0
        database.withTransaction {
            val now = System.currentTimeMillis()
            val tag = getOrCreateTag(cleanedName, now)
            distinctContactIds.forEach { contactId ->
                if (addTagToContactInternal(contactId, tag, now)) {
                    changedCount += 1
                }
            }
        }
        if (changedCount > 0) {
        }
        return changedCount
    }

    suspend fun removeTagFromContact(contactId: Long, tagId: Long) {
        var changed = false
        database.withTransaction {
            val tag = dao.getTagById(tagId) ?: return@withTransaction
            val removedRows = dao.removeTagFromContact(contactId, tagId)
            if (removedRows > 0) {
                changed = true
                touchContact(contactId, System.currentTimeMillis())
                appendEvent(
                    contactId = contactId,
                    entityType = EventEntityType.Tag,
                    actionType = EventActionType.Deleted,
                    fieldType = ContactFieldType.Tag,
                    oldValue = tag.name,
                )
            }
        }
        if (changed) {
        }
    }

    suspend fun getTagsForContact(contactId: Long): List<ContactTag> =
        dao.getTagsForContact(contactId).map { it.toModel() }

    suspend fun searchTags(prefix: String, limit: Int = 8): List<ContactTag> {
        val normalizedPrefix = normalizeTagName(prefix)
        if (normalizedPrefix.isBlank()) return emptyList()
        return dao.searchTags(normalizedPrefix, limit.coerceIn(1, 10)).map { it.toModel() }
    }

    fun observeHomeTags(): Flow<List<ContactTag>> =
        dao.observeHomeTags().map { it.toModels() }

    fun observeSavedSearches(): Flow<List<SavedSearch>> =
        dao.observeSavedSearches().map { searches -> searches.map { it.toModel() } }

    suspend fun saveSearch(title: String, query: String, tagIds: List<Long>): Long {
        val cleanedTitle = title.trim()
        require(cleanedTitle.isNotBlank()) { "Saved search title is required." }
        val distinctTagIds = tagIds.distinct()
        val now = System.currentTimeMillis()
        val savedSearchId = database.withTransaction {
            val id = dao.insertSavedSearch(
                SavedSearchEntity(
                    publicId = newSavedSearchPublicId(),
                    title = cleanedTitle,
                    query = query.trim(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            distinctTagIds.forEach { tagId ->
                dao.insertSavedSearchTag(SavedSearchTagCrossRef(savedSearchId = id, tagId = tagId))
            }
            id
        }
        return savedSearchId
    }

    suspend fun findSavedSearchByPublicId(publicId: String): SavedSearch? =
        dao.getSavedSearchByPublicId(publicId.trim())?.toModel()

    suspend fun deleteSavedSearch(savedSearchId: Long): Boolean {
        val deleted = dao.deleteSavedSearchById(savedSearchId) > 0
        if (deleted) {
        }
        return deleted
    }

    suspend fun searchFieldValueSuggestions(
        fieldType: String,
        query: String,
        limit: Int = 5,
    ): List<ContactFieldSuggestion> {
        if (fieldType !in ContactFieldType.initialTypes || fieldType == ContactFieldType.Photo) {
            return emptyList()
        }
        val normalizedPrefix = when (fieldType) {
            ContactFieldType.Phone -> ContactDuplicateNormalizer.normalizePhone(query)
            else -> ContactDuplicateNormalizer.normalizeLooseText(query)
        }
        if (normalizedPrefix.isBlank()) return emptyList()
        return dao.searchFieldValueSuggestions(
            fieldType = fieldType,
            normalizedPrefix = normalizedPrefix,
            limit = limit.coerceIn(1, 10),
        ).map { row ->
            ContactFieldSuggestion(
                fieldType = fieldType,
                value = row.value,
                usageCount = row.usageCount,
                lastUsedAt = row.lastUsedAt,
            )
        }
    }

    private suspend fun appendEvent(
        contactId: Long,
        entityType: String,
        actionType: String,
        eventType: String = eventTypeFor(entityType, actionType),
        fieldType: String? = null,
        oldValue: String? = null,
        newValue: String? = null,
        occurredAt: Long = System.currentTimeMillis(),
        metadataJson: String? = null,
    ): Long =
        dao.insertEvent(
            ContactEventEntity(
                contactId = contactId,
                entityType = entityType,
                actionType = actionType,
                eventType = eventType,
                fieldType = fieldType,
                oldValue = oldValue,
                newValue = newValue,
                occurredAt = occurredAt,
                metadataJson = metadataJson,
            ),
        )

    private suspend fun getOrCreateTag(cleanedName: String, now: Long): TagEntity {
        val normalizedName = normalizeTagName(cleanedName)
        return dao.getTagByNormalizedName(normalizedName)
            ?: run {
                dao.insertTag(
                    TagEntity(
                        name = cleanedName,
                        normalizedName = normalizedName,
                        createdAt = now,
                    ),
                )
                dao.getTagByNormalizedName(normalizedName)
                    ?: error("Tag could not be created.")
            }
    }

    private suspend fun addTagToContactInternal(contactId: Long, tag: TagEntity, now: Long): Boolean {
        val contact = dao.getContactEntity(contactId) ?: return false
        if (contact.deletedAt != null) return false
        val contactTagId = dao.insertContactTag(
            ContactTagCrossRef(
                contactId = contactId,
                tagId = tag.id,
                addedAt = now,
            ),
        )
        if (contactTagId == -1L) return false
        touchContact(contactId, now)
        appendEvent(
            contactId = contactId,
            entityType = EventEntityType.Tag,
            actionType = EventActionType.Added,
            fieldType = ContactFieldType.Tag,
            newValue = tag.name,
            occurredAt = now,
        )
        return true
    }

    suspend fun recordContactOpen(contactId: Long) {
        appendEvent(
            contactId = contactId,
            entityType = EventEntityType.Contact,
            actionType = EventActionType.Opened,
            eventType = ContactEventType.CONTACT_OPEN,
        )
    }

    suspend fun recordFieldOpen(contactId: Long, fieldType: String) {
        appendEvent(
            contactId = contactId,
            entityType = EventEntityType.Field,
            actionType = EventActionType.Opened,
            eventType = ContactEventType.FIELD_OPEN,
            fieldType = fieldType,
        )
    }

    suspend fun recordInitiative(
        contactId: Long,
        initiativeType: InitiativeType,
        timestampUtc: Long = System.currentTimeMillis(),
    ): Long {
        val initiativeId = database.withTransaction {
            val initiativeId = dao.insertInitiative(
                ContactInitiativeEntity(
                    contactId = contactId,
                    timestampUtc = timestampUtc,
                    initiativeType = initiativeType.name,
                ),
            )
            if (initiativeId > 0L) {
                appendEvent(
                    contactId = contactId,
                    entityType = EventEntityType.Initiative,
                    actionType = EventActionType.Added,
                    eventType = ContactEventType.INITIATIVE,
                    newValue = initiativeType.name,
                    occurredAt = timestampUtc,
                    metadataJson = initiativeMetadataJson(initiativeId, initiativeType),
                )
            }
            initiativeId
        }
        if (initiativeId > 0L) {
        }
        return initiativeId
    }

    suspend fun updateHistoryTimestamp(event: ContactEvent, timestampUtc: Long) {
        var changed = false
        database.withTransaction {
            val updatedEvents = dao.updateEventTimestamp(event.id, timestampUtc)
            if (updatedEvents <= 0) return@withTransaction
            changed = true
            if (event.eventType == ContactEventType.INITIATIVE) {
                val initiativeId = event.initiativeIdFromMetadata()
                if (initiativeId != null) {
                    dao.updateInitiativeTimestamp(initiativeId, timestampUtc)
                }
            }
        }
        if (changed) {
        }
    }

    suspend fun undoInitiative(initiativeId: Long): Boolean {
        var deleted = false
        database.withTransaction {
            val initiative = dao.getInitiativeById(initiativeId) ?: return@withTransaction
            deleted = dao.deleteInitiativeById(initiativeId) > 0
            if (deleted) {
                dao.deleteInitiativeEvent(
                    initiativeMetadataJson(
                        initiativeId = initiativeId,
                        initiativeType = initiative.initiativeType.toInitiativeType(),
                    ),
                )
            }
        }
        if (deleted) {
        }
        return deleted
    }

    suspend fun deleteInitiative(initiativeId: Long): Boolean =
        undoInitiative(initiativeId)

    suspend fun createContact(input: ContactInput): Long {
        require(input.hasAnyValue()) { "At least one field is required." }
        val now = System.currentTimeMillis()
        val contactId = database.withTransaction {
            val contactId = dao.insertContact(
                ContactEntity(
                    publicId = newContactPublicId(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            appendEvent(
                contactId = contactId,
                entityType = EventEntityType.Contact,
                actionType = EventActionType.Created,
                eventType = ContactEventType.CONTACT_ADD,
                occurredAt = now,
            )
            insertInitialFields(contactId, input, now)
            PeoplePhotoBinding.attach(database, contactId)
            synchronizeMessagingLinksForContact(contactId, now)
            contactId
        }
        PeoplePhotoBinding.discard(input.photoPath)
        return contactId
    }

    suspend fun updateContact(contactId: Long, input: ContactInput): ContactPhotoCleanupResult {
        require(input.hasAnyValue()) { "At least one field is required." }
        var changed = false
        var phoneChanged = false
        var deletableOldPhotoPath: String? = null
        database.withTransaction {
            val oldPhotoPath = dao.getFieldForContact(contactId, ContactFieldType.Photo)?.value.orEmpty()
            changed = upsertFieldInternal(contactId, ContactFieldType.Name, input.name, 0) || changed
            phoneChanged = upsertFieldInternal(contactId, ContactFieldType.Phone, input.phone, 1)
            changed = phoneChanged || changed
            changed = upsertFieldInternal(contactId, ContactFieldType.Telegram, input.telegram, 2) || changed
            changed = upsertFieldInternal(contactId, ContactFieldType.Email, input.email, 3) || changed
            changed = upsertFieldInternal(contactId, ContactFieldType.Nickname, input.nickname, 4) || changed
            changed = upsertFieldInternal(contactId, ContactFieldType.Company, input.company, 5) || changed
            changed = upsertFieldInternal(contactId, ContactFieldType.Note, input.note, 6) || changed
            changed = upsertFieldInternal(contactId, ContactFieldType.Link, input.link, 7) || changed
            changed = upsertFieldInternal(
                contactId = contactId,
                fieldType = ContactFieldType.Address,
                value = input.addressPlaceId ?: input.address,
                position = 8,
                latitude = input.addressLatitude.takeIf { input.addressPlaceId == null },
                longitude = input.addressLongitude.takeIf { input.addressPlaceId == null },
                source = if (input.addressPlaceId == null) "manual" else LUOGHI_FIELD_SOURCE,
            ) || changed
            changed = upsertFieldInternal(contactId, ContactFieldType.Address2, input.address2, 9) || changed
            changed = upsertFieldInternal(
                contactId = contactId,
                fieldType = ContactFieldType.Nationality,
                value = input.nationality,
                position = 10,
                countryCode = input.nationalityCountryCode,
            ) || changed
            changed = upsertAgeFieldInternal(contactId, input, 11) || changed
            changed = upsertFieldInternal(contactId, ContactFieldType.GrindrNick, input.grindrNick, 12) || changed
            changed = upsertFieldInternal(contactId, ContactFieldType.InstagramUsername, input.instagramUsername, 13) || changed
            changed = upsertFieldInternal(contactId, ContactFieldType.FacebookUserId, input.facebookUserId, 14) || changed
            changed = upsertFieldInternal(contactId, ContactFieldType.Photo, input.photoPath, 15) || changed
            PeoplePhotoBinding.attach(database, contactId)
            if (phoneChanged) {
                synchronizeMessagingLinksForContact(contactId, System.currentTimeMillis())
            }
            if (changed) {
                touchContact(contactId, System.currentTimeMillis())
                if (
                    oldPhotoPath.isNotBlank() &&
                    oldPhotoPath != input.photoPath.trim() &&
                    dao.countFieldsByValue(ContactFieldType.Photo, oldPhotoPath) == 0
                ) {
                    deletableOldPhotoPath = oldPhotoPath
                }
            }
        }
        PeoplePhotoBinding.discard(input.photoPath)
        if (changed) {
        }
        return ContactPhotoCleanupResult(oldPhotoPath = deletableOldPhotoPath, changed = changed)
    }

    suspend fun updateContactPhoto(contactId: Long, photoPath: String): ContactPhotoCleanupResult {
        var changed = false
        var deletableOldPhotoPath: String? = null
        database.withTransaction {
            val oldPhotoPath = dao.getFieldForContact(contactId, ContactFieldType.Photo)?.value.orEmpty()
            changed = upsertFieldInternal(contactId, ContactFieldType.Photo, photoPath, 14)
            PeoplePhotoBinding.attach(database, contactId)
            if (changed) {
                touchContact(contactId, System.currentTimeMillis())
                if (
                    oldPhotoPath.isNotBlank() &&
                    oldPhotoPath != photoPath &&
                    dao.countFieldsByValue(ContactFieldType.Photo, oldPhotoPath) == 0
                ) {
                    deletableOldPhotoPath = oldPhotoPath
                }
            }
        }
        PeoplePhotoBinding.discard(photoPath)
        if (changed) {
        }
        return ContactPhotoCleanupResult(oldPhotoPath = deletableOldPhotoPath, changed = changed)
    }

    suspend fun getContactPhotoReferences(): List<ContactPhotoReference> =
        dao.getFieldsByType(ContactFieldType.Photo).map { field ->
            ContactPhotoReference(
                contactId = field.contactId,
                photoPath = field.value,
            )
        }

    suspend fun updateField(fieldId: Long, newValue: String) {
        var changed = false
        database.withTransaction {
            val field = dao.getFieldById(fieldId) ?: return@withTransaction
            val cleanedValue = newValue.trim()
            if (field.value == cleanedValue) return@withTransaction
            val now = System.currentTimeMillis()
            if (cleanedValue.isEmpty()) {
                dao.deleteFieldById(field.id)
                appendFieldDeletedEvent(field, now)
                if (field.fieldType == ContactFieldType.Phone) {
                    synchronizeMessagingLinksForContact(field.contactId, now)
                }
                touchContact(field.contactId, now)
                changed = true
                return@withTransaction
            }
            dao.updateField(
                field.copy(
                    value = cleanedValue,
                    normalizedValue = normalizedValueFor(field.fieldType, cleanedValue),
                    sortValue = sortValueFor(field.fieldType, cleanedValue),
                    editedAt = now,
                    latitude = null,
                    longitude = null,
                ),
            )
            appendFieldUpdatedEvent(
                contactId = field.contactId,
                fieldType = field.fieldType,
                oldValue = field.value,
                newValue = cleanedValue,
                occurredAt = now,
            )
            if (field.fieldType == ContactFieldType.Phone) {
                synchronizeMessagingLinksForContact(field.contactId, now)
            }
            touchContact(field.contactId, now)
            changed = true
        }
        if (changed) {
        }
    }

    suspend fun upsertField(contactId: Long, fieldType: String, value: String) {
        require(fieldType in ContactFieldType.initialTypes) { "Unsupported field type: $fieldType" }
        var changed = false
        database.withTransaction {
            changed = upsertFieldInternal(contactId, fieldType, value.trim(), 0)
            if (changed) {
                if (fieldType == ContactFieldType.Phone) {
                    synchronizeMessagingLinksForContact(contactId, System.currentTimeMillis())
                }
                touchContact(contactId, System.currentTimeMillis())
            }
        }
        if (changed) {
        }
    }

    suspend fun updateFieldDescription(fieldId: Long, newDescription: String) {
        var changed = false
        database.withTransaction {
            val field = dao.getFieldById(fieldId) ?: return@withTransaction
            val oldDescription = field.description.orEmpty()
            val cleanedDescription = newDescription.trim()
            if (oldDescription == cleanedDescription) return@withTransaction

            val now = System.currentTimeMillis()
            dao.updateField(
                field.copy(
                    description = cleanedDescription.ifBlank { null },
                    editedAt = now,
                ),
            )
            appendFieldDescriptionEvent(
                field = field,
                oldValue = oldDescription.takeIf { it.isNotBlank() },
                newValue = cleanedDescription.takeIf { it.isNotBlank() },
                occurredAt = now,
            )
            touchContact(field.contactId, now)
            changed = true
        }
        if (changed) {
        }
    }

    suspend fun ensureFieldDescriptionTargets(contactId: Long) {
        var changed = false
        val now = System.currentTimeMillis()
        database.withTransaction {
            editableDescriptionFieldTypes.forEachIndexed { position, fieldType ->
                if (dao.getFieldForContact(contactId, fieldType) == null) {
                    dao.insertField(
                        ContactFieldEntity(
                            contactId = contactId,
                            fieldType = fieldType,
                            value = "",
                            addedAt = now,
                            position = position,
                        ),
                    )
                    changed = true
                }
            }
        }
        if (changed) {
        }
    }

    suspend fun deleteContact(contactId: Long) {
        var changed = false
        var deletedPublicId: String? = null
        database.withTransaction {
            val contact = dao.getContactEntity(contactId) ?: return@withTransaction
            if (contact.deletedAt != null) return@withTransaction
            val now = System.currentTimeMillis()
            appendEvent(
                contactId = contactId,
                entityType = EventEntityType.Contact,
                actionType = EventActionType.Deleted,
                eventType = ContactEventType.CONTACT_DELETE,
                occurredAt = now,
            )
            dao.updateContact(
                contact.copy(
                    updatedAt = now,
                    deletedAt = now,
                ),
            )
            deletedPublicId = contact.publicId
            changed = true
        }
        if (changed) {
            deletedPublicId?.let {
                com.gernalix.personalhub.core.hubcontext.HubContextRuntime.canonicalDeletedIfInitialized(
                    com.gernalix.personalhub.contracts.database.HubEntityRef("people", "person", it),
                )
            }
        }
    }

    suspend fun archiveContacts(contactIds: List<Long>): Int {
        val distinctContactIds = contactIds.distinct()
        var changedCount = 0
        database.withTransaction {
            val now = System.currentTimeMillis()
            val archiveTag = getOrCreateTag(ARCHIVE_TAG_NAME, now)
            distinctContactIds.forEach { contactId ->
                val archivedRows = dao.archiveContact(
                    contactId = contactId,
                    updatedAt = now,
                    archivedAt = now,
                )
                addTagToContactInternal(contactId, archiveTag, now)
                if (archivedRows > 0) {
                    changedCount += 1
                    appendEvent(
                        contactId = contactId,
                        entityType = EventEntityType.Contact,
                        actionType = EventActionType.Archived,
                        eventType = ContactEventType.CONTACT_ARCHIVE,
                        occurredAt = now,
                    )
                }
            }
        }
        if (changedCount > 0) {
        }
        return changedCount
    }

    suspend fun getContactByNormalizedPhone(number: String): ContactDetail? =
        dao.getContactByNormalizedPhone(PhoneNormalizer.normalize(number))?.toDetail()

    suspend fun scanMessagingLinksForContact(contactId: Long) {
        var changed = false
        database.withTransaction {
            changed = synchronizeMessagingLinksForContact(contactId, System.currentTimeMillis())
        }
        if (changed) {
        }
    }

    suspend fun scanMessagingLinksForAllContacts() {
        var changed = false
        database.withTransaction {
            val now = System.currentTimeMillis()
            dao.getActiveContactIds().forEach { contactId ->
                changed = synchronizeMessagingLinksForContact(contactId, now) || changed
            }
        }
        if (changed) {
        }
    }

    suspend fun updateMessagingLinkVerificationStatus(linkId: Long, verificationStatus: String) {
        require(
            verificationStatus in setOf(
                MessagingLinkVerificationStatus.ManuallyConfirmed,
                MessagingLinkVerificationStatus.ManuallyRejected,
                MessagingLinkVerificationStatus.Unverified,
            ),
        ) { "Unsupported messaging verification status: $verificationStatus" }
        var changed = false
        database.withTransaction {
            val link = dao.getMessagingLinkById(linkId) ?: return@withTransaction
            if (link.verificationStatus == verificationStatus) return@withTransaction
            dao.updateMessagingLink(
                link.copy(
                    verificationStatus = verificationStatus,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            changed = true
        }
        if (changed) {
        }
    }

    suspend fun findDuplicateCandidates(
        input: ContactInput,
        excludedContactId: Long? = null,
        limit: Int = 5,
    ): List<ContactDuplicateCandidate> {
        val normalizedPhone = ContactDuplicateNormalizer.normalizePhone(input.phone)
        val normalizedEmail = ContactDuplicateNormalizer.normalizeEmail(input.email)
        val normalizedLinks = input.linkValuesForDuplicateCheck()
        val normalizedNamePrefix = ContactDuplicateNormalizer.firstTokenPrefix(input.name, minLength = 3)
        val normalizedAddressPrefix = ContactDuplicateNormalizer.firstTokenPrefix(input.address, minLength = 4)
        if (
            normalizedPhone.isBlank() &&
            normalizedEmail.isBlank() &&
            normalizedLinks.isEmpty() &&
            normalizedNamePrefix.isBlank() &&
            normalizedAddressPrefix.isBlank()
        ) {
            return emptyList()
        }

        val excludedId = excludedContactId ?: -1L
        val queryLimit = (limit.coerceAtLeast(1) * 8).coerceAtMost(40)
        val contacts = linkedMapOf<Long, ContactWithFieldsAndTags>()

        suspend fun addMatches(matches: List<ContactWithFieldsAndTags>) {
            matches.forEach { contact ->
                contacts.putIfAbsent(contact.contact.id, contact)
            }
        }

        if (normalizedPhone.length >= 5) {
            addMatches(
                dao.findContactsByNormalizedFields(
                    fieldTypes = listOf(ContactFieldType.Phone),
                    normalizedValue = normalizedPhone,
                    excludedContactId = excludedId,
                    limit = queryLimit,
                ),
            )
            if (normalizedPhone.length >= 7) {
                addMatches(
                    dao.findContactsByPhoneTail(
                        phoneTail = normalizedPhone.takeLast(7),
                        excludedContactId = excludedId,
                        limit = queryLimit,
                    ),
                )
            }
        }

        if (normalizedEmail.isNotBlank()) {
            addMatches(
                dao.findContactsByNormalizedFields(
                    fieldTypes = listOf(ContactFieldType.Email),
                    normalizedValue = normalizedEmail,
                    excludedContactId = excludedId,
                    limit = queryLimit,
                ),
            )
        }

        normalizedLinks.forEach { (_, normalizedValue) ->
            addMatches(
                dao.findContactsByNormalizedFields(
                    fieldTypes = duplicateLinkFieldTypes,
                    normalizedValue = normalizedValue,
                    excludedContactId = excludedId,
                    limit = queryLimit,
                ),
            )
            addMatches(
                dao.findContactsBySortValue(
                    fieldTypes = duplicateLinkFieldTypes,
                    sortValue = normalizedValue,
                    excludedContactId = excludedId,
                    limit = queryLimit,
                ),
            )
        }

        if (normalizedNamePrefix.isNotBlank()) {
            addMatches(
                dao.findContactsBySortValuePrefix(
                    fieldType = ContactFieldType.Name,
                    prefix = normalizedNamePrefix,
                    excludedContactId = excludedId,
                    limit = queryLimit,
                ),
            )
        }
        if (normalizedAddressPrefix.isNotBlank()) {
            addMatches(
                dao.findContactsBySortValuePrefix(
                    fieldType = ContactFieldType.Address,
                    prefix = normalizedAddressPrefix,
                    excludedContactId = excludedId,
                    limit = queryLimit,
                ),
            )
        }
        return contacts.values
            .mapNotNull { contact -> contact.toDuplicateCandidate(input) }
            .sortedWith(
                compareByDescending<ContactDuplicateCandidate> { it.hasStrongMatch }
                    .thenBy { it.reasons.firstOrNull()?.priority ?: Int.MAX_VALUE }
                    .thenBy { it.displayName.lowercase() },
            )
            .take(limit.coerceIn(1, 5))
    }

    private suspend fun synchronizeMessagingLinksForContact(contactId: Long, now: Long): Boolean {
        val phoneValues = dao.getPhoneFieldsForContact(contactId).map { it.value }
        val candidates = MessagingDeepLinkGenerator.generate(phoneValues)
        if (candidates.isEmpty()) {
            return dao.deleteMessagingLinksForContact(contactId) > 0
        }

        var changed = dao.deleteMessagingLinksNotInPhones(
            contactId = contactId,
            normalizedPhones = candidates.map { it.normalizedPhone }.distinct(),
        ) > 0

        candidates.forEach { candidate ->
            val existing = dao.getMessagingLink(
                contactId = contactId,
                platform = candidate.platform,
                normalizedPhone = candidate.normalizedPhone,
            )
            if (existing == null) {
                val insertedId = dao.insertMessagingLink(
                    ContactMessagingLinkEntity(
                        contactId = contactId,
                        platform = candidate.platform,
                        normalizedPhone = candidate.normalizedPhone,
                        deepLink = candidate.deepLink,
                        generationStatus = MessagingLinkGenerationStatus.LinkGenerated,
                        verificationStatus = MessagingLinkVerificationStatus.Unverified,
                        lastScanAt = now,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                changed = insertedId > 0 || changed
            } else {
                val updated = existing.copy(
                    deepLink = candidate.deepLink,
                    generationStatus = MessagingLinkGenerationStatus.LinkGenerated,
                    lastScanAt = now,
                    updatedAt = now,
                )
                if (updated != existing) {
                    dao.updateMessagingLink(updated)
                    changed = true
                }
            }
        }
        return changed
    }

    private suspend fun insertInitialFields(contactId: Long, input: ContactInput, now: Long) {
        insertFieldIfPresent(contactId, ContactFieldType.Name, input.name, now, position = 0)
        insertFieldIfPresent(contactId, ContactFieldType.Phone, input.phone, now, position = 1)
        insertFieldIfPresent(contactId, ContactFieldType.Telegram, input.telegram, now, position = 2)
        insertFieldIfPresent(contactId, ContactFieldType.Email, input.email, now, position = 3)
        insertFieldIfPresent(contactId, ContactFieldType.Nickname, input.nickname, now, position = 4)
        insertFieldIfPresent(contactId, ContactFieldType.Company, input.company, now, position = 5)
        insertFieldIfPresent(contactId, ContactFieldType.Note, input.note, now, position = 6)
        insertFieldIfPresent(contactId, ContactFieldType.Link, input.link, now, position = 7)
        insertFieldIfPresent(
            contactId = contactId,
            fieldType = ContactFieldType.Address,
            value = input.addressPlaceId ?: input.address,
            now = now,
            position = 8,
            latitude = input.addressLatitude.takeIf { input.addressPlaceId == null },
            longitude = input.addressLongitude.takeIf { input.addressPlaceId == null },
            source = if (input.addressPlaceId == null) "manual" else LUOGHI_FIELD_SOURCE,
        )
        insertFieldIfPresent(contactId, ContactFieldType.Address2, input.address2, now, position = 9)
        insertFieldIfPresent(
            contactId = contactId,
            fieldType = ContactFieldType.Nationality,
            value = input.nationality,
            now = now,
            position = 10,
            countryCode = input.nationalityCountryCode,
        )
        insertAgeFieldIfPresent(contactId, input, now, position = 11)
        insertFieldIfPresent(contactId, ContactFieldType.GrindrNick, input.grindrNick, now, position = 12)
        insertFieldIfPresent(contactId, ContactFieldType.InstagramUsername, input.instagramUsername, now, position = 13)
        insertFieldIfPresent(contactId, ContactFieldType.FacebookUserId, input.facebookUserId, now, position = 14)
        insertFieldIfPresent(contactId, ContactFieldType.Photo, input.photoPath, now, position = 15)
    }

    private suspend fun insertAgeFieldIfPresent(contactId: Long, input: ContactInput, now: Long, position: Int) {
        val ageField = ageFieldValue(input, existingSource = null, now = now) ?: return
        insertFieldIfPresent(
            contactId = contactId,
            fieldType = ContactFieldType.Age,
            value = ageField.value,
            now = now,
            position = position,
            source = ageField.source,
        )
    }

    private suspend fun insertFieldIfPresent(
        contactId: Long,
        fieldType: String,
        value: String,
        now: Long,
        position: Int,
        latitude: Double? = null,
        longitude: Double? = null,
        countryCode: String? = null,
        source: String? = "manual",
    ) {
        val cleanedValue = value.trim()
        if (cleanedValue.isEmpty()) return
        val cleanedCountryCode = countryCode.cleanCountryCode()
        dao.insertField(
            ContactFieldEntity(
                contactId = contactId,
                fieldType = fieldType,
                value = cleanedValue,
                normalizedValue = normalizedValueFor(fieldType, cleanedValue),
                sortValue = sortValueFor(fieldType, cleanedValue),
                addedAt = now,
                position = position,
                isPrimary = fieldType == ContactFieldType.Name || fieldType == ContactFieldType.Phone,
                source = source,
                latitude = latitude,
                longitude = longitude,
                countryCode = cleanedCountryCode,
            ),
        )
        appendFieldAddedEvent(
            contactId = contactId,
            fieldType = fieldType,
            newValue = cleanedValue,
            occurredAt = now,
        )
    }

    private suspend fun upsertFieldInternal(
        contactId: Long,
        fieldType: String,
        value: String,
        position: Int,
        latitude: Double? = null,
        longitude: Double? = null,
        countryCode: String? = null,
        source: String? = "manual",
    ): Boolean {
        val cleanedValue = value.trim()
        val cleanedCountryCode = countryCode.cleanCountryCode().takeIf { cleanedValue.isNotBlank() }
        val existing = dao.getFieldForContact(contactId, fieldType)
        if (existing == null) {
            if (cleanedValue.isEmpty()) return false
            val now = System.currentTimeMillis()
            dao.insertField(
                ContactFieldEntity(
                    contactId = contactId,
                    fieldType = fieldType,
                    value = cleanedValue,
                    normalizedValue = normalizedValueFor(fieldType, cleanedValue),
                    sortValue = sortValueFor(fieldType, cleanedValue),
                    addedAt = now,
                    position = position,
                    isPrimary = fieldType == ContactFieldType.Name || fieldType == ContactFieldType.Phone,
                    latitude = latitude,
                    longitude = longitude,
                    countryCode = cleanedCountryCode,
                    source = source,
                ),
            )
            appendFieldAddedEvent(
                contactId = contactId,
                fieldType = fieldType,
                newValue = cleanedValue,
                occurredAt = now,
            )
            return true
        }

        if (
            existing.value == cleanedValue &&
            existing.latitude == latitude &&
            existing.longitude == longitude &&
            existing.countryCode == cleanedCountryCode &&
            existing.source == source
        ) {
            return false
        }

        if (cleanedValue.isEmpty()) {
            val now = System.currentTimeMillis()
            dao.deleteFieldById(existing.id)
            appendFieldDeletedEvent(existing, now)
            return true
        }

        val now = System.currentTimeMillis()
        dao.updateField(
            existing.copy(
                value = cleanedValue,
                normalizedValue = normalizedValueFor(fieldType, cleanedValue),
                sortValue = sortValueFor(fieldType, cleanedValue),
                editedAt = now,
                latitude = latitude,
                longitude = longitude,
                countryCode = cleanedCountryCode,
                source = source,
            ),
        )
        appendFieldUpdatedEvent(
            contactId = contactId,
            fieldType = fieldType,
            oldValue = existing.value,
            newValue = cleanedValue,
            occurredAt = now,
        )
        return true
    }

    private suspend fun upsertAgeFieldInternal(contactId: Long, input: ContactInput, position: Int): Boolean {
        val existing = dao.getFieldForContact(contactId, ContactFieldType.Age)
        val now = System.currentTimeMillis()
        val ageField = ageFieldValue(input, existingSource = existing?.source, now = now)
        if (ageField == null) {
            if (existing == null) return false
            dao.deleteFieldById(existing.id)
            appendFieldDeletedEvent(existing, now)
            return true
        }
        if (existing == null) {
            dao.insertField(
                ContactFieldEntity(
                    contactId = contactId,
                    fieldType = ContactFieldType.Age,
                    value = ageField.value,
                    normalizedValue = normalizedValueFor(ContactFieldType.Age, ageField.value),
                    sortValue = sortValueFor(ContactFieldType.Age, ageField.value),
                    addedAt = now,
                    position = position,
                    source = ageField.source,
                ),
            )
            appendFieldAddedEvent(contactId, ContactFieldType.Age, ageField.value, now)
            return true
        }
        if (existing.value == ageField.value && existing.source == ageField.source) return false
        dao.updateField(
            existing.copy(
                value = ageField.value,
                normalizedValue = normalizedValueFor(ContactFieldType.Age, ageField.value),
                sortValue = sortValueFor(ContactFieldType.Age, ageField.value),
                editedAt = now,
                source = ageField.source,
            ),
        )
        appendFieldUpdatedEvent(contactId, ContactFieldType.Age, existing.value, ageField.value, now)
        return true
    }

    private suspend fun appendFieldAddedEvent(
        contactId: Long,
        fieldType: String,
        newValue: String,
        occurredAt: Long,
    ) {
        appendEvent(
            contactId = contactId,
            entityType = EventEntityType.Field,
            actionType = EventActionType.Added,
            fieldType = fieldType,
            newValue = newValue,
            occurredAt = occurredAt,
        )
    }

    private suspend fun appendFieldUpdatedEvent(
        contactId: Long,
        fieldType: String,
        oldValue: String,
        newValue: String,
        occurredAt: Long,
    ) {
        appendEvent(
            contactId = contactId,
            entityType = EventEntityType.Field,
            actionType = EventActionType.Updated,
            fieldType = fieldType,
            oldValue = oldValue,
            newValue = newValue,
            occurredAt = occurredAt,
        )
    }

    private suspend fun appendFieldDeletedEvent(field: ContactFieldEntity, occurredAt: Long) {
        appendEvent(
            contactId = field.contactId,
            entityType = EventEntityType.Field,
            actionType = EventActionType.Deleted,
            fieldType = field.fieldType,
            oldValue = field.value,
            occurredAt = occurredAt,
        )
    }

    private suspend fun appendFieldDescriptionEvent(
        field: ContactFieldEntity,
        oldValue: String?,
        newValue: String?,
        occurredAt: Long,
    ) {
        val actionType = when {
            oldValue.isNullOrBlank() && !newValue.isNullOrBlank() -> EventActionType.Added
            !oldValue.isNullOrBlank() && newValue.isNullOrBlank() -> EventActionType.Deleted
            else -> EventActionType.Updated
        }
        appendEvent(
            contactId = field.contactId,
            entityType = EventEntityType.FieldDescription,
            actionType = actionType,
            eventType = eventTypeFor(EventEntityType.Field, actionType),
            fieldType = field.fieldType,
            oldValue = oldValue,
            newValue = newValue,
            occurredAt = occurredAt,
            metadataJson = fieldDescriptionMetadataJson(field),
        )
    }

    private suspend fun touchContact(contactId: Long, updatedAt: Long) {
        val contact = dao.getContactEntity(contactId) ?: return
        dao.updateContact(contact.copy(updatedAt = updatedAt))
    }

    private fun contactsWithHomeMetrics(
        contacts: Flow<List<ContactWithFieldsAndTags>>,
        sort: ContactHomeSortState,
        searchQuery: String = "",
        normalizedSearchQuery: String = "",
    ): Flow<List<ContactSummary>> =
        combine(contacts, dao.observeContactHomeMetrics()) { contactRows, metricRows ->
            val metricsByContactId = metricRows.associateBy { it.contactId }
            contactRows
                .map { contact ->
                    contact.toSummary(
                        metrics = metricsByContactId[contact.contact.id],
                        searchQuery = searchQuery,
                        normalizedSearchQuery = normalizedSearchQuery,
                    )
                }
                .sortForHome(sort)
        }

    private fun ContactWithFieldsAndTags.toSummary(
        metrics: ContactHomeMetricRow? = null,
        searchQuery: String = "",
        normalizedSearchQuery: String = "",
    ): ContactSummary {
        val displayFields = displayFields()
        val values = displayFields.mapValues { (_, field) -> field.value }
        val displayName = displayName(values)
        return ContactSummary(
            id = contact.id,
            publicId = contact.publicId.orEmpty(),
            displayName = displayName,
            subtitle = subtitle(values, displayName),
            phone = values[ContactFieldType.Phone].orEmpty(),
            searchMatches = searchMatches(searchQuery, normalizedSearchQuery),
            tags = tags.toModels(),
            createdAt = contact.createdAt,
            updatedAt = contact.updatedAt,
            lastInteractionAt = metrics?.lastInteractionAt,
            openCount = metrics?.openCount ?: 0,
            initiativeCount = metrics?.initiativeCount ?: 0,
            company = values[ContactFieldType.Company].orEmpty(),
            address = displayFields[ContactFieldType.Address].legacyAddressValue(),
            address2 = values[ContactFieldType.Address2].orEmpty(),
            addressLatitude = displayFields[ContactFieldType.Address]?.latitude,
            addressLongitude = displayFields[ContactFieldType.Address]?.longitude,
            addressPlaceId = displayFields[ContactFieldType.Address].luoghiPlaceId(),
            nationality = values[ContactFieldType.Nationality].orEmpty(),
            nationalityCountryCode = displayFields[ContactFieldType.Nationality]?.countryCode,
            age = displayFields[ContactFieldType.Age]?.toContactAge(),
            photoPath = values[ContactFieldType.Photo].orEmpty(),
        )
    }

    private fun ContactFieldEntity?.luoghiPlaceId(): String? =
        this?.value?.takeIf { source == LUOGHI_FIELD_SOURCE && it.isNotBlank() }

    private fun ContactFieldEntity?.legacyAddressValue(): String =
        this?.value.orEmpty().takeUnless { this?.source == LUOGHI_FIELD_SOURCE }.orEmpty()

    private fun ContactWithFieldsAndTags.toDetail(): ContactDetail {
        val displayFields = displayFields()
        val values = displayFields.mapValues { (_, field) -> field.value }
        val displayName = displayName(values)
        return ContactDetail(
            id = contact.id,
            publicId = contact.publicId.orEmpty(),
            name = values[ContactFieldType.Name].orEmpty(),
            phone = values[ContactFieldType.Phone].orEmpty(),
            telegram = values[ContactFieldType.Telegram].orEmpty(),
            email = values[ContactFieldType.Email].orEmpty(),
            nickname = values[ContactFieldType.Nickname].orEmpty(),
            company = values[ContactFieldType.Company].orEmpty(),
            note = values[ContactFieldType.Note].orEmpty(),
            link = values[ContactFieldType.Link].orEmpty(),
            address = displayFields[ContactFieldType.Address].legacyAddressValue(),
            address2 = values[ContactFieldType.Address2].orEmpty(),
            addressLatitude = displayFields[ContactFieldType.Address]?.latitude,
            addressLongitude = displayFields[ContactFieldType.Address]?.longitude,
            addressPlaceId = displayFields[ContactFieldType.Address].luoghiPlaceId(),
            nationality = values[ContactFieldType.Nationality].orEmpty(),
            nationalityCountryCode = displayFields[ContactFieldType.Nationality]?.countryCode,
            age = displayFields[ContactFieldType.Age]?.toContactAge(),
            grindrNick = values[ContactFieldType.GrindrNick].orEmpty(),
            instagramUsername = values[ContactFieldType.InstagramUsername].orEmpty(),
            facebookUserId = values[ContactFieldType.FacebookUserId].orEmpty(),
            photoPath = values[ContactFieldType.Photo].orEmpty(),
            displayName = displayName,
            subtitle = subtitle(values, displayName),
            createdAt = contact.createdAt,
            updatedAt = contact.updatedAt,
            fieldTimestamps = displayFields.mapValues { (_, field) ->
                ContactFieldTimestamp(
                    addedAt = field.addedAt,
                    editedAt = field.editedAt,
                )
            },
            fieldDescriptors = displayFields.mapValues { (_, field) -> field.toDescriptor() },
            messagingLinks = messagingLinks
                .sortedWith(
                    compareBy<ContactMessagingLinkEntity> { it.normalizedPhone }
                        .thenBy { MessagingPlatform.all.indexOf(it.platform).let { index -> if (index >= 0) index else Int.MAX_VALUE } }
                        .thenBy { it.id },
                )
                .map { it.toModel() },
            tags = tags.toModels(),
        )
    }

    private fun ContactWithFieldsAndTags.displayFields(): Map<String, ContactFieldEntity> =
        fields
            .filter { it.value.isNotBlank() || it.fieldType in editableDescriptionFieldTypes }
            .sortedWith(
                compareByDescending<ContactFieldEntity> { it.isPrimary }
                    .thenBy { it.position }
                    .thenBy { it.id },
            )
            .groupBy { it.fieldType }
            .mapValues { (_, fields) -> fields.first() }

    private fun ContactWithFieldsAndTags.searchMatches(
        searchQuery: String,
        normalizedSearchQuery: String,
    ): List<ContactSearchMatch> {
        if (searchQuery.isBlank()) return emptyList()

        return fields
            .asSequence()
            .filter { it.value.isNotBlank() }
            .sortedWith(
                compareByDescending<ContactFieldEntity> { it.isPrimary }
                    .thenBy { it.position }
                    .thenBy { it.id },
            )
            .filter { field -> field.fieldType != ContactFieldType.Photo }
            .filter { field -> field.matchesSearch(searchQuery, normalizedSearchQuery) }
            .filter { field -> field.fieldType != ContactFieldType.Phone }
            .map { field ->
                ContactSearchMatch(
                    fieldType = field.fieldType,
                    value = field.value,
                )
            }
            .toList()
    }

    private fun ContactFieldEntity.matchesSearch(
        searchQuery: String,
        normalizedSearchQuery: String,
    ): Boolean =
        value.contains(searchQuery, ignoreCase = true) ||
            (
                normalizedSearchQuery.isNotBlank() &&
                    normalizedValue?.contains(normalizedSearchQuery) == true
            )

    private fun ContactWithFieldsAndTags.toDuplicateCandidate(input: ContactInput): ContactDuplicateCandidate? {
        val displayFields = displayFields()
        val values = displayFields.mapValues { (_, field) -> field.value }
        val reasons = mutableListOf<ContactDuplicateReason>()

        values[ContactFieldType.Phone]
            ?.takeIf { ContactDuplicateNormalizer.phoneMatches(input.phone, it) }
            ?.let { reasons += ContactDuplicateReason.SameNumber }

        values[ContactFieldType.Email]
            ?.takeIf {
                ContactDuplicateNormalizer.normalizeEmail(input.email).isNotBlank() &&
                    ContactDuplicateNormalizer.normalizeEmail(input.email) ==
                    ContactDuplicateNormalizer.normalizeEmail(it)
            }
            ?.let { reasons += ContactDuplicateReason.SameEmail }

        val existingLinks = duplicateLinkFieldTypes
            .mapNotNull { fieldType -> values[fieldType] }
            .map { ContactDuplicateNormalizer.normalizeLinkOrUsername(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val inputLinks = input.linkValuesForDuplicateCheck().map { it.normalizedValue }.toSet()
        if (inputLinks.intersect(existingLinks).isNotEmpty()) {
            reasons += ContactDuplicateReason.ExistingLink
        }

        values[ContactFieldType.Name]
            ?.takeIf { ContactDuplicateNormalizer.looseTextMatches(input.name, it) }
            ?.let { reasons += ContactDuplicateReason.SimilarName }

        values[ContactFieldType.Address]
            ?.takeIf { ContactDuplicateNormalizer.looseTextMatches(input.address, it) }
            ?.let { reasons += ContactDuplicateReason.SimilarAddress }
        if (reasons.isEmpty()) return null
        val distinctReasons = reasons.distinct().sortedBy { it.priority }
        return ContactDuplicateCandidate(
            contactId = contact.id,
            displayName = displayName(values),
            reasons = distinctReasons,
            hasStrongMatch = distinctReasons.any { it.isStrong },
        )
    }

    private fun List<TagEntity>.toModels(): List<ContactTag> =
        sortedBy { it.normalizedName }.map { it.toModel() }

    private fun TagEntity.toModel(): ContactTag =
        ContactTag(
            id = id,
            name = name,
        )

    private fun SavedSearchWithTags.toModel(): SavedSearch =
        SavedSearch(
            id = savedSearch.id,
            publicId = savedSearch.publicId,
            title = savedSearch.title,
            query = savedSearch.query,
            tags = tags.toModels(),
            createdAt = savedSearch.createdAt,
            updatedAt = savedSearch.updatedAt,
        )

    private fun ContactFieldEntity.toDescriptor(): ContactFieldDescriptor =
        ContactFieldDescriptor(
            id = id,
            fieldType = fieldType,
            value = value,
            description = description.orEmpty(),
            timestamp = ContactFieldTimestamp(
                addedAt = addedAt,
                editedAt = editedAt,
            ),
        )

    private fun ContactMessagingLinkEntity.toModel(): ContactMessagingLink =
        ContactMessagingLink(
            id = id,
            contactId = contactId,
            platform = platform,
            normalizedPhone = normalizedPhone,
            deepLink = deepLink,
            generationStatus = generationStatus,
            verificationStatus = verificationStatus,
            lastScanAt = lastScanAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun ContactFieldEntity.toContactAge(now: Long = System.currentTimeMillis()): ContactAge? {
        val sourceValue = source.orEmpty()
        return when {
            sourceValue == AgeSourceBirthDate -> {
                val birthDate = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
                val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                val years = ChronoUnit.YEARS.between(birthDate, today).coerceAtLeast(0).toInt()
                ContactAge(
                    source = ContactAgeSource.BIRTH_DATE,
                    storedValue = value,
                    referenceAt = addedAt,
                    years = years,
                    birthDate = birthDate,
                )
            }

            sourceValue.startsWith(AgeSourceManualPrefix) -> {
                val baseAge = value.toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val referenceAt = sourceValue.substringAfter(AgeSourceManualPrefix).toLongOrNull() ?: addedAt
                val referenceDate = Instant.ofEpochMilli(referenceAt).atZone(ZoneId.systemDefault()).toLocalDate()
                val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                val elapsedYears = ChronoUnit.YEARS.between(referenceDate, today).coerceAtLeast(0).toInt()
                ContactAge(
                    source = ContactAgeSource.MANUAL,
                    storedValue = value,
                    referenceAt = referenceAt,
                    years = baseAge + elapsedYears,
                )
            }

            else -> value.toIntOrNull()?.takeIf { it >= 0 }?.let { years ->
                ContactAge(
                    source = ContactAgeSource.MANUAL,
                    storedValue = value,
                    referenceAt = addedAt,
                    years = years,
                )
            }
        }
    }

    private fun ContactEventEntity.toModel(): ContactEvent =
        ContactEvent(
            id = id,
            contactId = contactId,
            entityType = entityType,
            actionType = actionType,
            eventType = eventType,
            fieldType = fieldType,
            oldValue = oldValue,
            newValue = newValue,
            occurredAt = occurredAt,
            metadataJson = metadataJson,
        )

    private fun ContactEventWithContactName.toGlobalModel(): GlobalContactEvent =
        GlobalContactEvent(
            event = event.toModel(),
            contactDisplayName = contactDisplayName,
        )

    private fun ContactInitiativeEntity.toModel(): ContactInitiative =
        ContactInitiative(
            id = id,
            contactId = contactId,
            timestampUtc = timestampUtc,
            initiativeType = initiativeType.toInitiativeType(),
        )

    private fun ContactInitiativeWithContactName.toGlobalInitiativeModel(): GlobalContactInitiative =
        GlobalContactInitiative(
            initiative = initiative.toModel(),
            contactDisplayName = contactDisplayName,
        )

    private fun InitiativeCalendarDayRow.toModel(): InitiativeDaySummary =
        InitiativeDaySummary(
            date = LocalDate.parse(localDate),
            selfCount = selfCount,
            otherCount = otherCount,
        )

    private fun HistoryCalendarDayRow.toModel(): HistoryCalendarDaySummary =
        HistoryCalendarDaySummary(
            date = LocalDate.parse(localDate),
            eventCount = eventCount,
        )

    private fun displayName(values: Map<String, String>): String =
        values[ContactFieldType.Name]
            ?: values[ContactFieldType.Nickname]
            ?: values[ContactFieldType.Email]
            ?: "Unnamed contact"

    private fun subtitle(values: Map<String, String>, displayName: String): String? =
        listOf(
            values[ContactFieldType.Nickname],
            values[ContactFieldType.Company],
            values[ContactFieldType.Email],
            values[ContactFieldType.Note],
        ).firstOrNull { !it.isNullOrBlank() && it != displayName }

    private fun normalizedValueFor(fieldType: String, value: String): String? =
        when (fieldType) {
            ContactFieldType.Phone -> ContactDuplicateNormalizer.normalizePhone(value)
            ContactFieldType.Email -> ContactDuplicateNormalizer.normalizeEmail(value)
            ContactFieldType.Link,
            ContactFieldType.Telegram,
            ContactFieldType.GrindrNick,
            ContactFieldType.InstagramUsername,
            ContactFieldType.FacebookUserId,
            -> ContactDuplicateNormalizer.normalizeLinkOrUsername(value)

            else -> null
        }

    private fun sortValueFor(fieldType: String, value: String): String? =
        when (fieldType) {
            ContactFieldType.Name,
            ContactFieldType.Email,
            ContactFieldType.Link,
            ContactFieldType.Telegram,
            ContactFieldType.Nickname,
            ContactFieldType.Company,
            ContactFieldType.Note,
            ContactFieldType.Nationality,
            ContactFieldType.Age,
            ContactFieldType.Address,
            ContactFieldType.Address2,
            ContactFieldType.GrindrNick,
            ContactFieldType.InstagramUsername,
            ContactFieldType.FacebookUserId,
            -> ContactDuplicateNormalizer.normalizeLooseText(value)

            else -> null
        }

    private fun normalizeTagName(value: String): String =
        value.trim().lowercase()

    private fun String?.cleanCountryCode(): String? =
        this
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.matches(Regex("[A-Z]{2}")) }

    private fun List<ContactSummary>.sortForHome(sort: ContactHomeSortState): List<ContactSummary> {
        val descending = sort.direction == ContactHomeSortDirection.DESC
        val comparator = when (sort.criterion) {
            ContactHomeSort.NAME -> compareBy<ContactSummary> { it.displayName.lowercase() }
                .thenBy { it.id }

            ContactHomeSort.COMPANY -> compareBy<ContactSummary> { it.company.lowercase().ifBlank { "\uFFFF" } }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.id }

            ContactHomeSort.OPEN_COUNT -> compareBy<ContactSummary> { it.openCount }
                .thenBy { it.updatedAt }
                .thenBy { it.createdAt }
                .thenBy { it.id }

            ContactHomeSort.CREATED -> compareBy<ContactSummary> { it.createdAt }
                .thenBy { it.id }

            ContactHomeSort.INITIATIVE_COUNT -> compareBy<ContactSummary> { it.initiativeCount }
                .thenBy { it.updatedAt }
                .thenBy { it.createdAt }
                .thenBy { it.id }

            ContactHomeSort.LAST_INTERACTION -> compareBy<ContactSummary> { it.lastInteractionAt ?: Long.MIN_VALUE }
                .thenBy { it.updatedAt }
                .thenBy { it.id }

            ContactHomeSort.UPDATED,
            ContactHomeSort.DISTANCE,
            -> compareBy<ContactSummary> { it.updatedAt }
                .thenBy { it.createdAt }
                .thenBy { it.id }
        }
        return sortedWith(if (descending) comparator.reversed() else comparator)
    }

    private fun newContactPublicId(): String = "c-${UUID.randomUUID()}"

    private fun newSavedSearchPublicId(): String = "s-${UUID.randomUUID()}"

    private fun ageFieldValue(
        input: ContactInput,
        existingSource: String?,
        now: Long,
    ): AgeFieldValue? {
        val birthDate = input.birthDate.trim()
        if (birthDate.isNotBlank()) {
            val parsed = runCatching { LocalDate.parse(birthDate) }.getOrNull() ?: return null
            return AgeFieldValue(value = parsed.toString(), source = AgeSourceBirthDate)
        }
        val manualAge = input.manualAge.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val source = existingSource
            ?.takeIf { it.startsWith(AgeSourceManualPrefix) }
            ?: "$AgeSourceManualPrefix$now"
        return AgeFieldValue(value = manualAge.toString(), source = source)
    }

    private fun String.toInitiativeType(): InitiativeType =
        enumValues<InitiativeType>().firstOrNull { it.name == this } ?: InitiativeType.OTHER

    private fun initiativeMetadataJson(initiativeId: Long, initiativeType: InitiativeType): String =
        """{"initiative_id":$initiativeId,"initiative_type":"${initiativeType.name}"}"""

    private fun fieldDescriptionMetadataJson(field: ContactFieldEntity): String =
        """{"subfield":"description","parent_field_id":${field.id},"parent_field_type":"${jsonEscape(field.fieldType)}","parent_field_value":"${jsonEscape(field.value)}"}"""

    private data class AgeFieldValue(
        val value: String,
        val source: String,
    )

    private fun ContactEvent.initiativeIdFromMetadata(): Long? =
        metadataJson
            ?.substringAfter("\"initiative_id\":", missingDelimiterValue = "")
            ?.takeWhile { it.isDigit() }
            ?.toLongOrNull()

    private fun eventTypeFor(entityType: String, actionType: String): String =
        when {
            entityType == EventEntityType.Contact && actionType == EventActionType.Created ->
                ContactEventType.CONTACT_ADD

            entityType == EventEntityType.Contact && actionType == EventActionType.Opened ->
                ContactEventType.CONTACT_OPEN

            entityType == EventEntityType.Contact && actionType == EventActionType.Deleted ->
                ContactEventType.CONTACT_DELETE

            entityType == EventEntityType.Contact && actionType == EventActionType.Archived ->
                ContactEventType.CONTACT_ARCHIVE

            entityType == EventEntityType.Field && actionType == EventActionType.Added ->
                ContactEventType.FIELD_ADD

            entityType == EventEntityType.Field && actionType == EventActionType.Opened ->
                ContactEventType.FIELD_OPEN

            entityType == EventEntityType.Field && actionType == EventActionType.Updated ->
                ContactEventType.FIELD_EDIT

            entityType == EventEntityType.Field && actionType == EventActionType.Deleted ->
                ContactEventType.FIELD_DELETE

            entityType == EventEntityType.Initiative -> ContactEventType.INITIATIVE
            entityType == EventEntityType.Tag && actionType == EventActionType.Added ->
                ContactEventType.FIELD_ADD

            entityType == EventEntityType.Tag && actionType == EventActionType.Deleted ->
                ContactEventType.FIELD_DELETE

            else -> ""
        }

    private fun jsonEscape(value: String): String =
        buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }

    private fun YearMonth.toUtcRange(zoneId: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
        atDay(1).toUtcRange(zoneId = zoneId, endExclusive = plusMonths(1).atDay(1))

    private fun LocalDate.toUtcRange(
        zoneId: ZoneId = ZoneId.systemDefault(),
        endExclusive: LocalDate = plusDays(1),
    ): Pair<Long, Long> =
        atStartOfDay(zoneId).toInstant().toEpochMilli() to
            endExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()

    private object EventEntityType {
        const val Contact = "contact"
        const val Field = "field"
        const val FieldDescription = "field_description"
        const val Initiative = "initiative"
        const val Tag = "tag"
    }

    private object EventActionType {
        const val Created = "created"
        const val Opened = "opened"
        const val Added = "added"
        const val Updated = "updated"
        const val Deleted = "deleted"
        const val Archived = "archived"
    }

    private data class DuplicateLinkValue(
        val fieldType: String,
        val normalizedValue: String,
    )

    private fun ContactInput.linkValuesForDuplicateCheck(): List<DuplicateLinkValue> =
        listOf(
            ContactFieldType.Link to link,
            ContactFieldType.GrindrNick to grindrNick,
            ContactFieldType.InstagramUsername to instagramUsername,
            ContactFieldType.FacebookUserId to facebookUserId,
        ).mapNotNull { (fieldType, value) ->
            val normalized = ContactDuplicateNormalizer.normalizeLinkOrUsername(value)
            if (normalized.isBlank()) null else DuplicateLinkValue(fieldType, normalized)
        }

    private val ContactDuplicateReason.isStrong: Boolean
        get() = this == ContactDuplicateReason.SameNumber ||
            this == ContactDuplicateReason.SameEmail ||
            this == ContactDuplicateReason.ExistingLink

    private val ContactDuplicateReason.priority: Int
        get() = when (this) {
            ContactDuplicateReason.SameNumber -> 0
            ContactDuplicateReason.SameEmail -> 1
            ContactDuplicateReason.ExistingLink -> 2
            ContactDuplicateReason.SimilarName -> 3
            ContactDuplicateReason.SimilarAddress -> 4
        }

    private companion object {
        const val LUOGHI_FIELD_SOURCE = "luoghi"
        const val ARCHIVE_TAG_NAME = "archivio"
        const val AgeSourceBirthDate = "birth_date"
        const val AgeSourceManualPrefix = "manual_age:"

        val duplicateLinkFieldTypes = listOf(
            ContactFieldType.Link,
            ContactFieldType.GrindrNick,
            ContactFieldType.InstagramUsername,
            ContactFieldType.FacebookUserId,
        )

        val editableDescriptionFieldTypes = listOf(
            ContactFieldType.Name,
            ContactFieldType.Phone,
            ContactFieldType.Telegram,
            ContactFieldType.Email,
            ContactFieldType.Nickname,
            ContactFieldType.Company,
            ContactFieldType.Note,
            ContactFieldType.Link,
            ContactFieldType.Address,
            ContactFieldType.Address2,
            ContactFieldType.Nationality,
            ContactFieldType.Age,
            ContactFieldType.GrindrNick,
            ContactFieldType.InstagramUsername,
            ContactFieldType.FacebookUserId,
        )
    }
}
