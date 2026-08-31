// v461
package com.example.multitimetracker.capsules.system

import android.content.Context
import android.net.Uri
import com.example.multitimetracker.capsules.now.state.NowUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * NOW capsule boundary contract.
 *
 * Root/UI must consume the NOW feature through this public surface instead of
 * reaching into MainViewModel's internal method set.
 */
interface NowCapsuleAccess {
    fun uiStateFlow(): StateFlow<NowUiState>

    fun addTag(name: String)

    fun exportBackup(context: Context)
    fun importDbFromUri(context: Context, uri: Uri)
    fun setBackupRootFolder(context: Context, uri: Uri)
}
