package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.PhotoCapsule
import com.supercontacts.app.data.backup.SuperContactsBackupManager
import com.supercontacts.app.data.repository.ContactInput
import com.supercontacts.app.data.repository.ContactsRepository
import com.supercontacts.app.data.repository.InitiativeType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeopleRelatedPersistenceDeviceTest {
    @TableProbe("people_photos")
    @Test fun croppedPhotoBlobPersistsWithContactTransaction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val name = "qa914263-photo-${UUID.randomUUID()}.db"
        val owner = PersonalHubDatabase.openTemporary(context, name)
        try {
            val repo = ContactsRepository(owner, SuperContactsBackupManager(context, {}, {}))
            val firstBytes = byteArrayOf(1, 2, 3, 4)
            val firstRef = PhotoCapsule.stage(firstBytes)
            val contactId = repo.createContact(ContactInput(name = "QA914263Photo", photoPath = firstRef))
            val stored = requireNotNull(owner.photoDao().find(firstRef))
            assertEquals(contactId, stored.contactId)
            assertTrue(stored.bytes.contentEquals(firstBytes))
            val secondBytes = byteArrayOf(5, 6, 7, 8)
            val secondRef = PhotoCapsule.stage(secondBytes)
            repo.updateContactPhoto(contactId, secondRef)
            assertTrue(requireNotNull(owner.photoDao().find(secondRef)).bytes.contentEquals(secondBytes))
            assertEquals(null, owner.photoDao().find(firstRef))
        } finally {
            owner.close()
            context.deleteDatabase(name)
        }
    }

    @TableProbe("contact_initiatives", "contact_messaging_links", "saved_searches", "saved_search_tags", "tags", "contact_tags")
    @Test fun contactRelationsPersistThroughRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val name = "qa914263-people-${UUID.randomUUID()}.db"
        val owner = PersonalHubDatabase.openTemporary(context, name)
        try {
            val backup = SuperContactsBackupManager(context, {}, {})
            val repo = ContactsRepository(owner, backup)
            val marker = "QA914263Person${UUID.randomUUID()}"
            val contactId = repo.createContact(ContactInput(name = marker, phone = "+4512345678"))
            val dao = owner.contactsDao()
            val tag = repo.addTagToContact(contactId, marker)
            assertEquals(tag.id, repo.getTagsForContact(contactId).single().id)
            val savedSearchId = repo.saveSearch(marker, marker, listOf(tag.id))
            val initiativeId = repo.recordInitiative(contactId, InitiativeType.SELF)
            assertTrue(initiativeId > 0)
            repo.scanMessagingLinksForContact(contactId)
            fun count(table: String, key: String, value: Any) = owner.openHelper.readableDatabase.query(
                "SELECT count(*) FROM $table WHERE $key=?", arrayOf(value),
            ).use { it.moveToFirst(); it.getLong(0) }
            assertEquals(1L, count("contact_initiatives", "id", initiativeId))
            assertTrue(count("contact_messaging_links", "contact_id", contactId) > 0)
            assertEquals(1L, count("saved_searches", "id", savedSearchId))
            assertEquals(1L, count("saved_search_tags", "saved_search_id", savedSearchId))
            assertEquals(1L, count("tags", "id", tag.id))
            assertEquals(1L, count("contact_tags", "contact_id", contactId))
            assertTrue(repo.deleteInitiative(initiativeId))
            assertTrue(repo.deleteSavedSearch(savedSearchId))
            repo.removeTagFromContact(contactId, tag.id)
            assertEquals(0L, count("contact_tags", "contact_id", contactId))
        } finally {
            owner.close()
            context.deleteDatabase(name)
        }
    }
}
