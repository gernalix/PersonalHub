package com.gernalix.personalhub.core.database

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Uncommitted crop previews are transient. Committed photos live only in Room. */
object PhotoCapsule {
    private val pending = ConcurrentHashMap<String, ByteArray>()
    fun stage(bytes: ByteArray): String = "hubphoto:${UUID.randomUUID()}".also { pending[it] = bytes }
    fun preview(reference: String): ByteArray? = pending[reference]
    fun discard(reference: String) { pending.remove(reference) }
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Called inside the contact transaction so fields and BLOBs commit or roll back together. */
    suspend fun attach(database: PersonalHubDatabase, contactId: Long) {
        database.contactsDao().getFieldsByType("photo").filter { it.contactId == contactId }.forEach { field ->
            val bytes = pending[field.value] ?: return@forEach
            database.photoDao().put(PeoplePhoto(field.id, contactId, field.value, "image/jpeg", bytes, sha256(bytes), field.editedAt ?: field.addedAt))
        }
    }
}
