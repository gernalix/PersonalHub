package com.example.multitimetracker.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

/**
 * Writes a lightweight JSON metadata file alongside CSV exports.
 *
 * Purpose:
 * - smoke-check that an export folder/zip is not empty
 * - make troubleshooting easier (timestamp, zip name)
 */
object BackupMetaWriter {

    private const val META_FILE_NAME = "backup_meta.json"

    fun write(
        context: Context,
        dir: DocumentFile,
        exportedAtMs: Long,
        zipFileName: String,
        backupSignature: String?
    ) {
        // Overwrite if present.
        dir.findFile(META_FILE_NAME)?.delete()

        val doc = dir.createFile("application/json", META_FILE_NAME) ?: return

        val json = JSONObject()
            .put("exportedAtMs", exportedAtMs)
            .put("zipFile", zipFileName)
            .put("backupSignature", backupSignature ?: JSONObject.NULL)
            .toString(2)

        context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }
}
