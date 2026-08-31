package com.supercontacts.app.data.repository

import android.content.Context
import com.supercontacts.app.data.backup.SuperContactsBackupManager
import com.supercontacts.app.data.local.SuperContactsDatabase
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
    private var backupManager: SuperContactsBackupManager? = null
    @Volatile
    private var homePreferencesStore: HomePreferencesStore? = null

    private val dataLayerGeneration = MutableStateFlow(0)

    val generation: StateFlow<Int> = dataLayerGeneration.asStateFlow()

    fun contactsRepository(context: Context): ContactsRepository =
        contactsRepository ?: synchronized(this) {
            contactsRepository ?: ContactsRepository(
                database(context),
                backupManager(context),
            ).also { contactsRepository = it }
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

    fun backupManager(context: Context): SuperContactsBackupManager =
        backupManager ?: synchronized(this) {
            backupManager ?: SuperContactsBackupManager(
                context = context.applicationContext,
                closeDataLayer = ::closeDataLayer,
                notifyDataLayerChanged = ::notifyDataLayerChanged,
            ).also { backupManager = it }
        }

    fun homePreferencesStore(context: Context): HomePreferencesStore =
        homePreferencesStore ?: synchronized(this) {
            homePreferencesStore ?: HomePreferencesStore(
                context.applicationContext,
            ).also { homePreferencesStore = it }
        }

    private fun database(context: Context): SuperContactsDatabase =
        SuperContactsDatabase.getInstance(context.applicationContext)

    private fun closeDataLayer() {
        synchronized(this) {
            contactsRepository = null
            SuperContactsDatabase.closeInstance()
        }
    }

    private fun notifyDataLayerChanged() {
        dataLayerGeneration.value += 1
    }
}
