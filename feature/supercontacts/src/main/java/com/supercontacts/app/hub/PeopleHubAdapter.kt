package com.supercontacts.app.hub

import android.content.Context
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.supercontacts.app.data.local.ContactWithFieldsAndTags
import com.supercontacts.app.data.repository.AppContainer
import com.supercontacts.app.data.repository.ContactInput
import com.supercontacts.app.R

class PeopleHubAdapter(private val context: Context) : HubEntityAdapter {
    override val moduleId = "people"
    override val entityKind = "person"
    override val capabilities = setOf("person", "contact")
    private val dao get() = PersonalHubDatabase.get(context).contactsDao()

    override suspend fun exists(canonicalId: String) = dao.getContactByPublicId(canonicalId)?.contact?.deletedAt == null
    override suspend fun lifecycle(canonicalId: String): String {
        val contact = dao.getContactByPublicId(canonicalId)?.contact ?: return HubEntityLifecycle.DELETED
        return when {
            contact.deletedAt != null -> HubEntityLifecycle.DELETED
            contact.archivedAt != null -> HubEntityLifecycle.ARCHIVED
            else -> HubEntityLifecycle.ACTIVE
        }
    }
    override suspend fun summaries(canonicalIds: Set<String>) =
        if (canonicalIds.isEmpty()) emptyMap() else dao.getContactsByPublicIds(canonicalIds.toList()).associate { row -> row.contact.publicId.orEmpty() to row.summary() }
    override suspend fun search(query: String, limit: Int) = dao.searchForHub(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget("supercontacts://contact/${android.net.Uri.encode(canonicalId)}", "com.supercontacts.app.MainActivity")
    override suspend fun create(request: HubCreateRequest): HubEntitySummary? {
        val name = request.suggestedLabel?.trim().orEmpty()
        if (name.isBlank()) return null
        val id = AppContainer.contactsRepository(context).createContact(ContactInput(name = name))
        val publicId = requireNotNull(dao.getContactEntity(id)?.publicId)
        return requireNotNull(dao.getContactByPublicId(publicId)).summary()
    }

    private fun ContactWithFieldsAndTags.summary(): HubEntitySummary {
        val values = fields.associate { it.fieldType to it.value }
        val label = values["name"]?.takeIf(String::isNotBlank)
            ?: values["nickname"]?.takeIf(String::isNotBlank)
            ?: values["email"]?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.hub_unnamed_contact)
        return HubEntitySummary(HubEntityRef(moduleId, entityKind, requireNotNull(contact.publicId)), label, lifecycle = when {
            contact.deletedAt != null -> HubEntityLifecycle.DELETED
            contact.archivedAt != null -> HubEntityLifecycle.ARCHIVED
            else -> HubEntityLifecycle.ACTIVE
        })
    }
}
