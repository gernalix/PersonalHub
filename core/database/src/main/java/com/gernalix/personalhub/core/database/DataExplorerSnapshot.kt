package com.gernalix.personalhub.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class DataExplorerSnapshot(
    val file: File,
    val generation: Long,
)

/**
 * Produces a detached, validated snapshot for read-only data exploration.
 * The live Room database and its WAL are never exposed to WebView/Datasette Lite.
 */
object DataExplorerSnapshots {
    private const val DIRECTORY = "datasette-explorer"

    fun directory(context: Context): File =
        File(context.cacheDir, DIRECTORY).apply {
            if (!exists()) check(mkdirs()) { "Cannot create Datasette explorer cache" }
            require(isDirectory) { "Datasette explorer cache is not a directory" }
        }

    fun create(context: Context): DataExplorerSnapshot = DatabaseGate.access {
        val directory = directory(context)
        directory.listFiles()?.forEach { file ->
            if (file.isFile) check(file.delete()) { "Cannot clear previous Datasette snapshot" }
        }

        val source = context.getDatabasePath(PersonalHubDatabase.DB_NAME)
        val temporary = File(directory, "personalhub-${UUID.randomUUID()}.tmp")
        val target = File(directory, PersonalHubDatabase.DB_NAME)
        try {
            val database = PersonalHubDatabase.get(context).openHelper.writableDatabase
            database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                require(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                    "Database busy; explorer snapshot will retry"
                }
            }
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            SQLiteDatabase.openDatabase(
                temporary.path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { copy ->
                copy.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                    require(cursor.moveToFirst() && cursor.getString(0).equals("delete", ignoreCase = true))
                }
            }
            val generation = DatabaseVault.validate(context, temporary)
            if (target.exists()) check(target.delete()) { "Cannot replace previous Datasette snapshot" }
            check(temporary.renameTo(target)) { "Cannot publish Datasette snapshot" }
            DataExplorerSnapshot(target, generation)
        } finally {
            temporary.delete()
        }
    }

    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }
}
