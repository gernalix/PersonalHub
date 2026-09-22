package com.supercontacts.app.data.repository

import android.content.Context
import com.gernalix.personalhub.core.database.PersonalHubDatabase

object AppContainer {
    @Volatile
    private var contactsRepository: ContactsRepository? = null
    @Volatile
    private var addressAutocompleteRepository: AddressAutocompleteRepository? = null
    @Volatile
    private var homePreferencesStore: HomePreferencesStore? = null


    fun contactsRepository(context: Context): ContactsRepository =
        contactsRepository ?: synchronized(this) {
            contactsRepository ?: ContactsRepository(database(context)).also { contactsRepository = it }
        }

    fun addressAutocompleteRepository(context: Context): AddressAutocompleteRepository =
        addressAutocompleteRepository ?: synchronized(this) {
            addressAutocompleteRepository ?: AddressAutocompleteRepository(
                context.applicationContext,
            ).also { addressAutocompleteRepository = it }
        }

    fun homePreferencesStore(context: Context): HomePreferencesStore =
        homePreferencesStore ?: synchronized(this) {
            homePreferencesStore ?: HomePreferencesStore(
                context.applicationContext,
            ).also { homePreferencesStore = it }
        }

    private fun database(context: Context): PersonalHubDatabase =
        PersonalHubDatabase.get(context.applicationContext)

    private fun closeDataLayer() {
        synchronized(this) {
            contactsRepository = null
        }
    }

    /** Releases process caches for isolated database tests. */
    fun resetForTests() = closeDataLayer()

}
