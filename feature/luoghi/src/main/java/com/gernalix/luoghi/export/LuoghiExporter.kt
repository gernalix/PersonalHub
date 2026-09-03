package com.gernalix.luoghi.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.RoomDatabase
import com.gernalix.luoghi.AppPatchVersion
import com.gernalix.luoghi.BuildConfig
import com.gernalix.luoghi.capsules.safexport.SyncStatusStore
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.DatabaseMutationCoordinator
import com.gernalix.luoghi.backup.RestoreSafetyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

object LuoghiExporter {
    private const val TAG = "LuoghiExporter"
    const val DB_FILE = "luoghi.db"
    const val BAK_FILE = "luoghi.db.bak"
    const val MANIFEST_FILE = "luoghi_manifest.json"
    const val TMP_FILE = "luoghi.db.tmp"
    const val BAK_TMP_FILE = "luoghi.db.bak.tmp"
    const val GENERATION_PREFIX = "luoghi_generation_"
    private const val GENERATIONS_TO_KEEP = 3
    private val preRestoreNameFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    sealed class Result {
        data object NotConfigured : Result()
        data object Suppressed : Result()
        data class Success(val exportedFiles: List<String>) : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun exportNow(context: Context): Result {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (com.gernalix.personalhub.core.database.DatabaseVault.exportNow(context)) Result.Success(listOf("personalhub.db")) else Result.NotConfigured
            }.getOrElse { Result.Failure(it.message ?: it.javaClass.simpleName) }
        }
    }

    internal suspend fun exportLocked(context: Context): Result = exportNow(context)

}
