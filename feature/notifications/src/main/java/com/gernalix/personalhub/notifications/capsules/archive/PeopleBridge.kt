package com.gernalix.personalhub.notifications.capsules.archive
import android.content.Context
import com.gernalix.personalhub.core.database.DatabaseProfiles
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.supercontacts.app.data.local.ContactWithFieldsAndTags

internal data class PersonChoice(val id: String, val label: String)
/** Read-only contract access; associations never mutate the main DB/People/Git history. */
internal class PeopleBridge(private val context: Context) {
    val profile: String get() = DatabaseProfiles.activeProfileId(context)
    private fun choice(row: ContactWithFieldsAndTags): PersonChoice? = row.contact.publicId?.let { id ->
        PersonChoice(id,row.fields.sortedWith(compareByDescending<com.supercontacts.app.data.local.ContactFieldEntity> { it.isPrimary }.thenBy { it.position })
            .take(3).joinToString(" · ") { it.value }.ifBlank { context.getString(com.gernalix.personalhub.notifications.R.string.unnamed_person) })
    }
    suspend fun search(query: String): List<PersonChoice> = PersonalHubDatabase.get(context).contactsDao().searchForHub(query,100).mapNotNull(::choice)
    suspend fun resolve(ids: List<String>): List<PersonChoice> = if(ids.isEmpty()) emptyList() else PersonalHubDatabase.get(context).contactsDao().getContactsByPublicIds(ids).mapNotNull(::choice)
    suspend fun exists(profile: String, person: String): Boolean = this.profile==profile && PersonalHubDatabase.get(context).contactsDao().getContactByPublicId(person)!=null
}
