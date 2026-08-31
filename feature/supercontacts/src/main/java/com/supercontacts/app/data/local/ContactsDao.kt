package com.supercontacts.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactsDao {
    @Insert
    suspend fun insertContact(contact: ContactEntity): Long

    @Update
    suspend fun updateContact(contact: ContactEntity)

    @Delete
    suspend fun deleteContact(contact: ContactEntity)

    @Insert
    suspend fun insertField(field: ContactFieldEntity): Long

    @Insert
    suspend fun insertEvent(event: ContactEventEntity): Long

    @Insert
    suspend fun insertInitiative(initiative: ContactInitiativeEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContactTag(contactTag: ContactTagCrossRef): Long

    @Insert
    suspend fun insertSavedSearch(savedSearch: SavedSearchEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSavedSearchTag(savedSearchTag: SavedSearchTagCrossRef): Long

    @Update
    suspend fun updateField(field: ContactFieldEntity)

    @Query("DELETE FROM contact_fields WHERE contact_id = :contactId")
    suspend fun deleteFieldsForContact(contactId: Long)

    @Query("DELETE FROM contact_fields WHERE id = :fieldId")
    suspend fun deleteFieldById(fieldId: Long)

    @Query("DELETE FROM contacts WHERE id = :contactId")
    suspend fun deleteContactById(contactId: Long): Int

    @Query("DELETE FROM contact_initiatives WHERE id = :initiativeId")
    suspend fun deleteInitiativeById(initiativeId: Long): Int

    @Query("SELECT * FROM contact_initiatives WHERE id = :initiativeId LIMIT 1")
    suspend fun getInitiativeById(initiativeId: Long): ContactInitiativeEntity?

    @Query("DELETE FROM contact_events WHERE event_type = 'INITIATIVE' AND metadata_json = :metadataJson")
    suspend fun deleteInitiativeEvent(metadataJson: String): Int

    @Query("UPDATE contact_events SET occurred_at = :timestampUtc WHERE id = :eventId")
    suspend fun updateEventTimestamp(eventId: Long, timestampUtc: Long): Int

    @Query("UPDATE contact_initiatives SET timestamp_utc = :timestampUtc WHERE id = :initiativeId")
    suspend fun updateInitiativeTimestamp(initiativeId: Long, timestampUtc: Long): Int

    @Query("SELECT * FROM contacts WHERE id = :contactId LIMIT 1")
    suspend fun getContactEntity(contactId: Long): ContactEntity?

    @Query("UPDATE contacts SET updated_at = :updatedAt, archived_at = :archivedAt WHERE id = :contactId AND deleted_at IS NULL AND archived_at IS NULL")
    suspend fun archiveContact(contactId: Long, updatedAt: Long, archivedAt: Long): Int

    @Query("SELECT * FROM contact_fields WHERE id = :fieldId LIMIT 1")
    suspend fun getFieldById(fieldId: Long): ContactFieldEntity?

    @Query(
        """
        SELECT COUNT(*) FROM contact_fields
        WHERE field_type = :fieldType AND value = :value
        """,
    )
    suspend fun countFieldsByValue(fieldType: String, value: String): Int

    @Query(
        """
        SELECT * FROM contact_fields
        WHERE contact_id = :contactId AND field_type = :fieldType
        ORDER BY is_primary DESC, position ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun getFieldForContact(contactId: Long, fieldType: String): ContactFieldEntity?

    @Query(
        """
        SELECT * FROM contact_fields
        WHERE field_type = :fieldType AND value != ''
        ORDER BY contact_id ASC, id ASC
        """,
    )
    suspend fun getFieldsByType(fieldType: String): List<ContactFieldEntity>

    @Query(
        """
        SELECT * FROM contact_fields
        WHERE contact_id = :contactId AND field_type = 'phone' AND value != ''
        ORDER BY is_primary DESC, position ASC, id ASC
        """,
    )
    suspend fun getPhoneFieldsForContact(contactId: Long): List<ContactFieldEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessagingLink(link: ContactMessagingLinkEntity): Long

    @Update
    suspend fun updateMessagingLink(link: ContactMessagingLinkEntity)

    @Query(
        """
        SELECT * FROM contact_messaging_links
        WHERE contact_id = :contactId
        AND platform = :platform
        AND normalized_phone = :normalizedPhone
        LIMIT 1
        """,
    )
    suspend fun getMessagingLink(
        contactId: Long,
        platform: String,
        normalizedPhone: String,
    ): ContactMessagingLinkEntity?

    @Query("SELECT * FROM contact_messaging_links WHERE id = :linkId LIMIT 1")
    suspend fun getMessagingLinkById(linkId: Long): ContactMessagingLinkEntity?

    @Query(
        """
        DELETE FROM contact_messaging_links
        WHERE contact_id = :contactId
        AND normalized_phone NOT IN (:normalizedPhones)
        """,
    )
    suspend fun deleteMessagingLinksNotInPhones(contactId: Long, normalizedPhones: List<String>): Int

    @Query("DELETE FROM contact_messaging_links WHERE contact_id = :contactId")
    suspend fun deleteMessagingLinksForContact(contactId: Long): Int

    @Query("SELECT id FROM contacts WHERE deleted_at IS NULL AND archived_at IS NULL ORDER BY id ASC")
    suspend fun getActiveContactIds(): List<Long>

    @Transaction
    @Query("SELECT * FROM contacts WHERE id = :contactId LIMIT 1")
    fun observeContactById(contactId: Long): Flow<ContactWithFieldsAndTags?>

    @Transaction
    @Query("SELECT * FROM contacts WHERE id = :contactId LIMIT 1")
    suspend fun getContactById(contactId: Long): ContactWithFieldsAndTags?

    @Transaction
    @Query("SELECT * FROM contacts WHERE public_id = :publicId AND deleted_at IS NULL AND archived_at IS NULL LIMIT 1")
    suspend fun getContactByPublicId(publicId: String): ContactWithFieldsAndTags?

    @Transaction
    @Query(
        """
        SELECT * FROM contacts
        WHERE deleted_at IS NULL
        AND archived_at IS NULL
        ORDER BY updated_at DESC, created_at DESC, id DESC
        """,
    )
    fun listContacts(): Flow<List<ContactWithFieldsAndTags>>

    @Transaction
    @Query(
        """
        SELECT * FROM contacts
        WHERE deleted_at IS NULL
        AND archived_at IS NULL
        AND (
            :query = ''
            OR EXISTS (
                SELECT 1 FROM contact_fields
                WHERE contact_fields.contact_id = contacts.id
                AND contact_fields.field_type != 'photo'
                AND (
                    contact_fields.value LIKE '%' || :query || '%' COLLATE NOCASE
                    OR (
                        :normalizedQuery != ''
                        AND contact_fields.normalized_value LIKE '%' || :normalizedQuery || '%'
                    )
                )
            )
        )
        ORDER BY updated_at DESC, created_at DESC, id DESC
        """,
    )
    fun searchContacts(query: String, normalizedQuery: String): Flow<List<ContactWithFieldsAndTags>>

    @Transaction
    @Query(
        """
        SELECT * FROM contacts
        WHERE deleted_at IS NULL
        AND archived_at IS NULL
        AND (
            :query = ''
            OR EXISTS (
                SELECT 1 FROM contact_fields
                WHERE contact_fields.contact_id = contacts.id
                AND contact_fields.field_type != 'photo'
                AND (
                    contact_fields.value LIKE '%' || :query || '%' COLLATE NOCASE
                    OR (
                        :normalizedQuery != ''
                        AND contact_fields.normalized_value LIKE '%' || :normalizedQuery || '%'
                    )
                )
            )
        )
        AND (
            SELECT COUNT(DISTINCT contact_tags.tag_id) FROM contact_tags
            WHERE contact_tags.contact_id = contacts.id
            AND contact_tags.tag_id IN (:tagIds)
        ) = :tagCount
        ORDER BY updated_at DESC, created_at DESC, id DESC
        """,
    )
    fun searchContactsByTags(
        query: String,
        normalizedQuery: String,
        tagIds: List<Long>,
        tagCount: Int,
    ): Flow<List<ContactWithFieldsAndTags>>

    @Transaction
    @Query(
        """
        SELECT contacts.* FROM contacts
        INNER JOIN contact_tags ON contact_tags.contact_id = contacts.id
        WHERE contact_tags.tag_id = :tagId
        AND contacts.deleted_at IS NULL
        AND contacts.archived_at IS NULL
        ORDER BY contacts.updated_at DESC, contacts.created_at DESC, contacts.id DESC
        """,
    )
    fun getContactsByTag(tagId: Long): Flow<List<ContactWithFieldsAndTags>>

    @Query(
        """
        SELECT
            contacts.id AS contact_id,
            (
                SELECT COUNT(*) FROM contact_events
                WHERE contact_events.contact_id = contacts.id
                AND contact_events.event_type = 'CONTACT_OPEN'
            ) AS open_count,
            (
                SELECT COUNT(*) FROM contact_initiatives
                WHERE contact_initiatives.contact_id = contacts.id
            ) AS initiative_count,
            (
                SELECT MAX(occurred_at) FROM contact_events
                WHERE contact_events.contact_id = contacts.id
            ) AS last_interaction_at
        FROM contacts
        WHERE contacts.deleted_at IS NULL
        AND contacts.archived_at IS NULL
        """,
    )
    fun observeContactHomeMetrics(): Flow<List<ContactHomeMetricRow>>

    @Transaction
    @Query(
        """
        SELECT contacts.* FROM contacts
        INNER JOIN contact_fields ON contact_fields.contact_id = contacts.id
        WHERE contact_fields.field_type = 'phone'
        AND contact_fields.normalized_value = :normalizedPhone
        AND contacts.deleted_at IS NULL
        AND contacts.archived_at IS NULL
        LIMIT 1
        """,
    )
    suspend fun getContactByNormalizedPhone(normalizedPhone: String): ContactWithFieldsAndTags?

    @Transaction
    @Query(
        """
        SELECT DISTINCT contacts.* FROM contacts
        INNER JOIN contact_fields ON contact_fields.contact_id = contacts.id
        WHERE contact_fields.field_type IN (:fieldTypes)
        AND contact_fields.normalized_value = :normalizedValue
        AND contacts.deleted_at IS NULL
        AND contacts.archived_at IS NULL
        AND contacts.id != :excludedContactId
        ORDER BY contacts.updated_at DESC, contacts.created_at DESC, contacts.id DESC
        LIMIT :limit
        """,
    )
    suspend fun findContactsByNormalizedFields(
        fieldTypes: List<String>,
        normalizedValue: String,
        excludedContactId: Long,
        limit: Int,
    ): List<ContactWithFieldsAndTags>

    @Transaction
    @Query(
        """
        SELECT DISTINCT contacts.* FROM contacts
        INNER JOIN contact_fields ON contact_fields.contact_id = contacts.id
        WHERE contact_fields.field_type = 'phone'
        AND contact_fields.normalized_value LIKE '%' || :phoneTail
        AND contacts.deleted_at IS NULL
        AND contacts.archived_at IS NULL
        AND contacts.id != :excludedContactId
        ORDER BY contacts.updated_at DESC, contacts.created_at DESC, contacts.id DESC
        LIMIT :limit
        """,
    )
    suspend fun findContactsByPhoneTail(
        phoneTail: String,
        excludedContactId: Long,
        limit: Int,
    ): List<ContactWithFieldsAndTags>

    @Transaction
    @Query(
        """
        SELECT DISTINCT contacts.* FROM contacts
        INNER JOIN contact_fields ON contact_fields.contact_id = contacts.id
        WHERE contact_fields.field_type IN (:fieldTypes)
        AND contact_fields.sort_value = :sortValue
        AND contacts.deleted_at IS NULL
        AND contacts.archived_at IS NULL
        AND contacts.id != :excludedContactId
        ORDER BY contacts.updated_at DESC, contacts.created_at DESC, contacts.id DESC
        LIMIT :limit
        """,
    )
    suspend fun findContactsBySortValue(
        fieldTypes: List<String>,
        sortValue: String,
        excludedContactId: Long,
        limit: Int,
    ): List<ContactWithFieldsAndTags>

    @Transaction
    @Query(
        """
        SELECT DISTINCT contacts.* FROM contacts
        INNER JOIN contact_fields ON contact_fields.contact_id = contacts.id
        WHERE contact_fields.field_type = :fieldType
        AND contact_fields.sort_value LIKE :prefix || '%'
        AND contacts.deleted_at IS NULL
        AND contacts.archived_at IS NULL
        AND contacts.id != :excludedContactId
        ORDER BY contacts.updated_at DESC, contacts.created_at DESC, contacts.id DESC
        LIMIT :limit
        """,
    )
    suspend fun findContactsBySortValuePrefix(
        fieldType: String,
        prefix: String,
        excludedContactId: Long,
        limit: Int,
    ): List<ContactWithFieldsAndTags>

    @Query(
        """
        SELECT
            contact_fields.value AS value,
            COUNT(*) AS usage_count,
            MAX(COALESCE(contact_fields.edited_at, contacts.updated_at, contact_fields.added_at)) AS last_used_at
        FROM contact_fields
        INNER JOIN contacts ON contacts.id = contact_fields.contact_id
        WHERE contact_fields.field_type = :fieldType
        AND contact_fields.field_type != 'photo'
        AND contact_fields.value != ''
        AND (
            COALESCE(contact_fields.sort_value, LOWER(TRIM(contact_fields.value))) LIKE :normalizedPrefix || '%'
            OR (
                contact_fields.normalized_value IS NOT NULL
                AND contact_fields.normalized_value LIKE :normalizedPrefix || '%'
            )
        )
        AND contacts.deleted_at IS NULL
        AND contacts.archived_at IS NULL
        GROUP BY LOWER(TRIM(contact_fields.value))
        ORDER BY usage_count DESC, last_used_at DESC, contact_fields.value COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun searchFieldValueSuggestions(
        fieldType: String,
        normalizedPrefix: String,
        limit: Int,
    ): List<ContactFieldSuggestionRow>

    @Query(
        """
        SELECT
            contact_fields.value AS value,
            MAX(contact_fields.latitude) AS latitude,
            MAX(contact_fields.longitude) AS longitude,
            COUNT(*) AS usage_count,
            MAX(COALESCE(contact_fields.edited_at, contacts.updated_at, contact_fields.added_at)) AS last_used_at
        FROM contact_fields
        INNER JOIN contacts ON contacts.id = contact_fields.contact_id
        WHERE contact_fields.field_type = 'address'
        AND contact_fields.value != ''
        AND contact_fields.sort_value LIKE :normalizedPrefix || '%'
        AND contacts.deleted_at IS NULL
        AND contacts.archived_at IS NULL
        GROUP BY LOWER(TRIM(contact_fields.value))
        ORDER BY usage_count DESC, last_used_at DESC, contact_fields.value COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun searchAddressSuggestions(
        normalizedPrefix: String,
        limit: Int,
    ): List<ContactAddressSuggestionRow>

    @Query("SELECT * FROM tags WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun getTagByNormalizedName(normalizedName: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id = :tagId LIMIT 1")
    suspend fun getTagById(tagId: Long): TagEntity?

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN contact_tags ON contact_tags.tag_id = tags.id
        WHERE contact_tags.contact_id = :contactId
        ORDER BY tags.normalized_name ASC
        """,
    )
    suspend fun getTagsForContact(contactId: Long): List<TagEntity>

    @Query(
        """
        SELECT * FROM tags
        WHERE normalized_name LIKE :normalizedPrefix || '%'
        ORDER BY normalized_name ASC
        LIMIT :limit
        """,
    )
    suspend fun searchTags(normalizedPrefix: String, limit: Int): List<TagEntity>

    @Query(
        """
        SELECT DISTINCT tags.* FROM tags
        INNER JOIN contact_tags ON contact_tags.tag_id = tags.id
        INNER JOIN contacts ON contacts.id = contact_tags.contact_id
        WHERE contacts.deleted_at IS NULL
        AND contacts.archived_at IS NULL
        ORDER BY tags.normalized_name ASC
        """,
    )
    fun observeHomeTags(): Flow<List<TagEntity>>

    @Transaction
    @Query(
        """
        SELECT * FROM saved_searches
        ORDER BY updated_at DESC, created_at DESC, id DESC
        """,
    )
    fun observeSavedSearches(): Flow<List<SavedSearchWithTags>>

    @Transaction
    @Query("SELECT * FROM saved_searches WHERE public_id = :publicId LIMIT 1")
    suspend fun getSavedSearchByPublicId(publicId: String): SavedSearchWithTags?

    @Query("DELETE FROM saved_searches WHERE id = :savedSearchId")
    suspend fun deleteSavedSearchById(savedSearchId: Long): Int

    @Query(
        """
        SELECT * FROM contact_events
        WHERE contact_id = :contactId
        AND (
            (:includeContact = 1 AND event_type IN ('CONTACT_ADD', 'CONTACT_OPEN', 'CONTACT_DELETE', 'CONTACT_ARCHIVE'))
            OR (:includeField = 1 AND event_type IN ('FIELD_ADD', 'FIELD_OPEN', 'FIELD_EDIT', 'FIELD_DELETE'))
            OR (:includeInitiative = 1 AND event_type = 'INITIATIVE')
        )
        ORDER BY occurred_at DESC, id DESC
        """,
    )
    suspend fun getEventsByContact(
        contactId: Long,
        includeContact: Boolean = true,
        includeField: Boolean = true,
        includeInitiative: Boolean = true,
    ): List<ContactEventEntity>

    @Query(
        """
        SELECT * FROM contact_events
        WHERE contact_id = :contactId
        AND (
            (:includeContact = 1 AND event_type IN ('CONTACT_ADD', 'CONTACT_OPEN', 'CONTACT_DELETE', 'CONTACT_ARCHIVE'))
            OR (:includeField = 1 AND event_type IN ('FIELD_ADD', 'FIELD_OPEN', 'FIELD_EDIT', 'FIELD_DELETE'))
            OR (:includeInitiative = 1 AND event_type = 'INITIATIVE')
        )
        ORDER BY occurred_at DESC, id DESC
        """,
    )
    fun observeEventsByContact(
        contactId: Long,
        includeContact: Boolean,
        includeField: Boolean,
        includeInitiative: Boolean,
    ): Flow<List<ContactEventEntity>>

    @Query(
        """
        SELECT contact_events.*,
        COALESCE(
            (
                SELECT name_fields.value FROM contact_fields AS name_fields
                WHERE name_fields.contact_id = contact_events.contact_id
                AND name_fields.field_type = 'name'
                AND name_fields.value != ''
                ORDER BY name_fields.is_primary DESC, name_fields.position ASC, name_fields.id ASC
                LIMIT 1
            ),
            (
                SELECT nickname_fields.value FROM contact_fields AS nickname_fields
                WHERE nickname_fields.contact_id = contact_events.contact_id
                AND nickname_fields.field_type = 'nickname'
                AND nickname_fields.value != ''
                ORDER BY nickname_fields.is_primary DESC, nickname_fields.position ASC, nickname_fields.id ASC
                LIMIT 1
            ),
            (
                SELECT phone_fields.value FROM contact_fields AS phone_fields
                WHERE phone_fields.contact_id = contact_events.contact_id
                AND phone_fields.field_type = 'phone'
                AND phone_fields.value != ''
                ORDER BY phone_fields.is_primary DESC, phone_fields.position ASC, phone_fields.id ASC
                LIMIT 1
            ),
            'Unnamed contact'
        ) AS contact_display_name
        FROM contact_events
        INNER JOIN contacts ON contacts.id = contact_events.contact_id
        WHERE (
            (:includeContact = 1 AND contact_events.event_type IN ('CONTACT_ADD', 'CONTACT_OPEN', 'CONTACT_DELETE', 'CONTACT_ARCHIVE'))
            OR (:includeField = 1 AND contact_events.event_type IN ('FIELD_ADD', 'FIELD_OPEN', 'FIELD_EDIT', 'FIELD_DELETE'))
            OR (:includeInitiative = 1 AND contact_events.event_type = 'INITIATIVE')
        )
        ORDER BY
            CASE WHEN :ascending = 1 THEN contact_events.occurred_at END ASC,
            CASE WHEN :ascending = 1 THEN contact_events.id END ASC,
            CASE WHEN :ascending = 0 THEN contact_events.occurred_at END DESC,
            CASE WHEN :ascending = 0 THEN contact_events.id END DESC
        """,
    )
    fun getAllEvents(
        ascending: Boolean,
        includeContact: Boolean,
        includeField: Boolean,
        includeInitiative: Boolean,
    ): Flow<List<ContactEventWithContactName>>

    @Query(
        """
        SELECT
            strftime('%Y-%m-%d', occurred_at / 1000, 'unixepoch', 'localtime') AS local_date,
            COUNT(*) AS event_count
        FROM contact_events
        WHERE occurred_at >= :startUtcInclusive
        AND occurred_at < :endUtcExclusive
        AND (
            (:includeContact = 1 AND event_type IN ('CONTACT_ADD', 'CONTACT_OPEN', 'CONTACT_DELETE', 'CONTACT_ARCHIVE'))
            OR (:includeField = 1 AND event_type IN ('FIELD_ADD', 'FIELD_OPEN', 'FIELD_EDIT', 'FIELD_DELETE'))
            OR (:includeInitiative = 1 AND event_type = 'INITIATIVE')
        )
        GROUP BY local_date
        ORDER BY local_date ASC
        """,
    )
    fun observeHistoryCalendarMonth(
        startUtcInclusive: Long,
        endUtcExclusive: Long,
        includeContact: Boolean,
        includeField: Boolean,
        includeInitiative: Boolean,
    ): Flow<List<HistoryCalendarDayRow>>

    @Query(
        """
        SELECT contact_events.*,
        COALESCE(
            (
                SELECT name_fields.value FROM contact_fields AS name_fields
                WHERE name_fields.contact_id = contact_events.contact_id
                AND name_fields.field_type = 'name'
                AND name_fields.value != ''
                ORDER BY name_fields.is_primary DESC, name_fields.position ASC, name_fields.id ASC
                LIMIT 1
            ),
            (
                SELECT nickname_fields.value FROM contact_fields AS nickname_fields
                WHERE nickname_fields.contact_id = contact_events.contact_id
                AND nickname_fields.field_type = 'nickname'
                AND nickname_fields.value != ''
                ORDER BY nickname_fields.is_primary DESC, nickname_fields.position ASC, nickname_fields.id ASC
                LIMIT 1
            ),
            (
                SELECT phone_fields.value FROM contact_fields AS phone_fields
                WHERE phone_fields.contact_id = contact_events.contact_id
                AND phone_fields.field_type = 'phone'
                AND phone_fields.value != ''
                ORDER BY phone_fields.is_primary DESC, phone_fields.position ASC, phone_fields.id ASC
                LIMIT 1
            ),
            'Unknown contact'
        ) AS contact_display_name
        FROM contact_events
        LEFT JOIN contacts ON contacts.id = contact_events.contact_id
        WHERE contact_events.occurred_at >= :startUtcInclusive
        AND contact_events.occurred_at < :endUtcExclusive
        AND (
            (:includeContact = 1 AND contact_events.event_type IN ('CONTACT_ADD', 'CONTACT_OPEN', 'CONTACT_DELETE', 'CONTACT_ARCHIVE'))
            OR (:includeField = 1 AND contact_events.event_type IN ('FIELD_ADD', 'FIELD_OPEN', 'FIELD_EDIT', 'FIELD_DELETE'))
            OR (:includeInitiative = 1 AND contact_events.event_type = 'INITIATIVE')
        )
        ORDER BY contact_events.occurred_at DESC, contact_events.id DESC
        """,
    )
    fun observeEventsForRange(
        startUtcInclusive: Long,
        endUtcExclusive: Long,
        includeContact: Boolean,
        includeField: Boolean,
        includeInitiative: Boolean,
    ): Flow<List<ContactEventWithContactName>>

    @Query(
        """
        SELECT * FROM contact_initiatives
        WHERE contact_id = :contactId
        ORDER BY
            CASE WHEN :ascending = 1 THEN timestamp_utc END ASC,
            CASE WHEN :ascending = 1 THEN id END ASC,
            CASE WHEN :ascending = 0 THEN timestamp_utc END DESC,
            CASE WHEN :ascending = 0 THEN id END DESC
        """,
    )
    fun observeInitiativesByContact(contactId: Long, ascending: Boolean): Flow<List<ContactInitiativeEntity>>

    @Query(
        """
        SELECT contact_initiatives.*,
        COALESCE(
            (
                SELECT name_fields.value FROM contact_fields AS name_fields
                WHERE name_fields.contact_id = contact_initiatives.contact_id
                AND name_fields.field_type = 'name'
                AND name_fields.value != ''
                ORDER BY name_fields.is_primary DESC, name_fields.position ASC, name_fields.id ASC
                LIMIT 1
            ),
            (
                SELECT nickname_fields.value FROM contact_fields AS nickname_fields
                WHERE nickname_fields.contact_id = contact_initiatives.contact_id
                AND nickname_fields.field_type = 'nickname'
                AND nickname_fields.value != ''
                ORDER BY nickname_fields.is_primary DESC, nickname_fields.position ASC, nickname_fields.id ASC
                LIMIT 1
            ),
            (
                SELECT phone_fields.value FROM contact_fields AS phone_fields
                WHERE phone_fields.contact_id = contact_initiatives.contact_id
                AND phone_fields.field_type = 'phone'
                AND phone_fields.value != ''
                ORDER BY phone_fields.is_primary DESC, phone_fields.position ASC, phone_fields.id ASC
                LIMIT 1
            ),
            'Unnamed contact'
        ) AS contact_display_name
        FROM contact_initiatives
        INNER JOIN contacts ON contacts.id = contact_initiatives.contact_id
        ORDER BY
            CASE WHEN :ascending = 1 THEN contact_initiatives.timestamp_utc END ASC,
            CASE WHEN :ascending = 1 THEN contact_initiatives.id END ASC,
            CASE WHEN :ascending = 0 THEN contact_initiatives.timestamp_utc END DESC,
            CASE WHEN :ascending = 0 THEN contact_initiatives.id END DESC
        """,
    )
    fun observeAllInitiatives(ascending: Boolean): Flow<List<ContactInitiativeWithContactName>>

    @Query(
        """
        SELECT
            strftime('%Y-%m-%d', timestamp_utc / 1000, 'unixepoch', 'localtime') AS local_date,
            SUM(CASE WHEN initiative_type = 'SELF' THEN 1 ELSE 0 END) AS self_count,
            SUM(CASE WHEN initiative_type = 'OTHER' THEN 1 ELSE 0 END) AS other_count
        FROM contact_initiatives
        WHERE timestamp_utc >= :startUtcInclusive
        AND timestamp_utc < :endUtcExclusive
        GROUP BY local_date
        ORDER BY local_date ASC
        """,
    )
    fun observeInitiativeCalendarMonth(
        startUtcInclusive: Long,
        endUtcExclusive: Long,
    ): Flow<List<InitiativeCalendarDayRow>>

    @Query(
        """
        SELECT contact_initiatives.*,
        COALESCE(
            (
                SELECT name_fields.value FROM contact_fields AS name_fields
                WHERE name_fields.contact_id = contact_initiatives.contact_id
                AND name_fields.field_type = 'name'
                AND name_fields.value != ''
                ORDER BY name_fields.is_primary DESC, name_fields.position ASC, name_fields.id ASC
                LIMIT 1
            ),
            (
                SELECT nickname_fields.value FROM contact_fields AS nickname_fields
                WHERE nickname_fields.contact_id = contact_initiatives.contact_id
                AND nickname_fields.field_type = 'nickname'
                AND nickname_fields.value != ''
                ORDER BY nickname_fields.is_primary DESC, nickname_fields.position ASC, nickname_fields.id ASC
                LIMIT 1
            ),
            (
                SELECT phone_fields.value FROM contact_fields AS phone_fields
                WHERE phone_fields.contact_id = contact_initiatives.contact_id
                AND phone_fields.field_type = 'phone'
                AND phone_fields.value != ''
                ORDER BY phone_fields.is_primary DESC, phone_fields.position ASC, phone_fields.id ASC
                LIMIT 1
            ),
            'Unnamed contact'
        ) AS contact_display_name
        FROM contact_initiatives
        INNER JOIN contacts ON contacts.id = contact_initiatives.contact_id
        WHERE contact_initiatives.timestamp_utc >= :startUtcInclusive
        AND contact_initiatives.timestamp_utc < :endUtcExclusive
        ORDER BY contact_initiatives.timestamp_utc ASC, contact_initiatives.id ASC
        """,
    )
    fun observeInitiativesForDay(
        startUtcInclusive: Long,
        endUtcExclusive: Long,
    ): Flow<List<ContactInitiativeWithContactName>>

    @Query(
        """
        DELETE FROM contact_tags
        WHERE contact_id = :contactId AND tag_id = :tagId
        """,
    )
    suspend fun removeTagFromContact(contactId: Long, tagId: Long): Int
}
