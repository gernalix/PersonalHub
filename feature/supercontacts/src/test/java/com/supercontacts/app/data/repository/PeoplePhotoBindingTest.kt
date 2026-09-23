package com.supercontacts.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.gernalix.personalhub.core.database.HubPhotoMediaStore
import com.gernalix.personalhub.core.database.PeoplePhoto
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.supercontacts.app.data.local.ContactEntity
import com.supercontacts.app.data.local.ContactFieldEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeoplePhotoBindingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun existingPeopleBlobReloadsWithoutMigrationOrReselection() = runBlocking {
        val name = "people-photo-legacy-${UUID.randomUUID()}.db"
        val bytes = byteArrayOf(9, 8, 7, 6)
        val database = open(name)
        try {
            val (contactId, fieldId) = insertPhotoField(database, "legacy-photo")
            database.photoDao().put(
                PeoplePhoto(fieldId, contactId, "legacy-photo", "image/jpeg", bytes, "legacy-sha", 100),
            )
        } finally {
            database.close()
        }

        val reopened = open(name)
        try {
            assertArrayEquals(bytes, reopened.photoDao().find("legacy-photo")?.bytes)
        } finally {
            reopened.close()
        }
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun sharedStagePersistsExactBytesAndReplaceRemoveFollowOwnerField() = runBlocking {
        val name = "people-photo-shared-${UUID.randomUUID()}.db"
        val database = open(name)
        try {
            val first = HubPhotoMediaStore.stageOriginal(byteArrayOf(1, 2, 3), "image/png")
            val (contactId, fieldId) = insertPhotoField(database, first.reference)
            PeoplePhotoBinding.attach(database, contactId)
            HubPhotoMediaStore.discard(first.reference)
            assertArrayEquals(byteArrayOf(1, 2, 3), database.photoDao().find(first.reference)?.bytes)

            val replacement = HubPhotoMediaStore.stageOriginal(byteArrayOf(4, 5), "image/webp")
            val field = requireNotNull(database.contactsDao().getFieldById(fieldId))
            database.contactsDao().updateField(field.copy(value = replacement.reference, editedAt = 200))
            PeoplePhotoBinding.attach(database, contactId)
            HubPhotoMediaStore.discard(replacement.reference)

            assertNull(database.photoDao().find(first.reference))
            assertArrayEquals(byteArrayOf(4, 5), database.photoDao().find(replacement.reference)?.bytes)
            database.contactsDao().deleteFieldById(fieldId)
            assertNull(database.photoDao().find(replacement.reference))
        } finally {
            database.close()
        }
        context.deleteDatabase(name)
        Unit
    }

    private suspend fun insertPhotoField(
        database: PersonalHubDatabase,
        reference: String,
    ): Pair<Long, Long> {
        val contactId = database.contactsDao().insertContact(
            ContactEntity(publicId = UUID.randomUUID().toString(), createdAt = 100, updatedAt = 100),
        )
        val fieldId = database.contactsDao().insertField(
            ContactFieldEntity(
                contactId = contactId,
                fieldType = "photo",
                value = reference,
                addedAt = 100,
            ),
        )
        return contactId to fieldId
    }

    private fun open(name: String): PersonalHubDatabase =
        Room.databaseBuilder(context, PersonalHubDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
}
