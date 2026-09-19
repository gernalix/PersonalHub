package com.supercontacts.app.data.repository

import android.content.Context
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppContainer {
    @Volatile
    private var contactsRepository: ContactsRepository? = null
    @Volatile
    private var addressAutocompleteRepository: AddressAutocompleteRepository? = null
    @Volatile
    private var contactPhotoStore: ContactPhotoStore? = null
    @Volatile
    private var homePreferencesStore: HomePreferencesStore? = null

    private val dataLayerGeneration = MutableStateFlow(0)

    val generation: StateFlow<Int> = dataLayerGeneration.asStateFlow()

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

    fun contactPhotoStore(context: Context): ContactPhotoStore =
        contactPhotoStore ?: synchronized(this) {
            contactPhotoStore ?: ContactPhotoStore(
                context.applicationContext,
            ).also { contactPhotoStore = it }
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

    private fun notifyDataLayerChanged() {
        dataLayerGeneration.value += 1
    }
}
