package com.supercontacts.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import com.gernalix.personalhub.core.database.HubPhotoMediaStore
import com.gernalix.personalhub.core.database.PeoplePhoto
import com.gernalix.personalhub.core.database.PersonalHubDatabase

/** Thin People adapter: ownership stays in People; image ingestion stays shared. */
object PeoplePhotoBinding {
    suspend fun importOriginal(contentResolver: ContentResolver, sourceUri: Uri): String =
        HubPhotoMediaStore.importOriginal(contentResolver, sourceUri).reference

    fun discard(reference: String) = HubPhotoMediaStore.discard(reference)

    suspend fun attach(database: PersonalHubDatabase, contactId: Long) {
        database.contactsDao().getFieldsByType("photo")
            .filter { it.contactId == contactId }
            .forEach { field ->
                val staged = HubPhotoMediaStore.staged(field.value) ?: return@forEach
                database.photoDao().put(
                    PeoplePhoto(
                        fieldId = field.id,
                        contactId = contactId,
                        reference = staged.reference,
                        mimeType = staged.mimeType,
                        bytes = staged.bytes,
                        sha256 = staged.sha256,
                        createdAt = field.editedAt ?: field.addedAt,
                    ),
                )
            }
    }
}
