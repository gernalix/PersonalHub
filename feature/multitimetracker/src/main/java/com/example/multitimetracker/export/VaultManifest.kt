// v434
package com.example.multitimetracker.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * v434 – Simple per-vault manifest.json for the autoexports folder.
 *
 * We keep it intentionally minimal and append-only (best effort).
 * If writing fails, exports still exist on disk; the manifest is just a helper.
 */
object VaultManifest {

    private const val MANIFEST_FILE = "manifest.json"

    private fun ensureManifestFile(vaultDir: DocumentFile): DocumentFile? {
        val existing = vaultDir.findFile(MANIFEST_FILE)
        if (existing != null && existing.isFile) return existing
        return vaultDir.createFile("application/json", MANIFEST_FILE)
    }

    fun appendEntry(
        context: Context,
        vaultName: String,
        fileName: String,
        reason: String,
        createdAtEpochMs: Long
    ) {
        val vaultDir = VaultFolders.ensureNamedVaultDir(context, vaultName)
        val mf = ensureManifestFile(vaultDir) ?: return

        val current = readJsonArray(context, mf) ?: JSONArray()
        val entry = JSONObject()
            .put("file", fileName)
            .put("reason", reason)
            .put("createdAtEpochMs", createdAtEpochMs)

        current.put(entry)
        writeJsonArray(context, mf, current)
    }

    private fun readJsonArray(context: Context, doc: DocumentFile): JSONArray? {
        return runCatching {
            context.contentResolver.openInputStream(doc.uri)?.use { ins ->
                val bytes = ins.readBytes()
                val s = bytes.toString(Charsets.UTF_8).trim()
                if (s.isBlank()) return@runCatching JSONArray()
                JSONArray(s)
            }
        }.getOrNull()
    }

    private fun writeJsonArray(context: Context, doc: DocumentFile, arr: JSONArray) {
        runCatching {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                out.write(arr.toString(2).toByteArray(Charsets.UTF_8))
            }
        }
    }
}
