// v395
package com.example.multitimetracker.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * v395 – Simple index registry for SAF artifacts.
 *
 * Purpose:
 * - Make the folder human-friendly (the app can list "snapshots/exports" with reasons)
 * - Enable reliable cleanup without guessing from filenames
 *
 * This is intentionally lightweight:
 * - Best-effort append-only (we rewrite the JSON array)
 * - If index is missing/corrupted, we recreate it
 */
object VaultIndex {

    private const val MAX_ENTRIES = 5_000 // safety cap

    enum class Kind { VAULT_SNAPSHOT, VAULT_CURRENT, EXPORT_ZIP, LOG }

    fun record(
        context: Context,
        kind: Kind,
        file: DocumentFile,
        vaultName: String?,
        reason: String,
        statsJson: JSONObject? = null,
        sha256Hex: String? = null
    ) {
        val root = VaultFolders.ensureRoot(context)
        val indexDoc = root.index ?: return

        val now = System.currentTimeMillis()
        val entry = JSONObject()
            .put("t_ms", now)
            .put("kind", kind.name)
            .put("name", file.name ?: "")
            .put("uri", file.uri.toString())
            .put("reason", reason)
            .put("vault", vaultName ?: JSONObject.NULL)
            .put("size", runCatching { file.length() }.getOrDefault(-1L))
            .put("sha256", sha256Hex ?: JSONObject.NULL)
            .put("stats", statsJson ?: JSONObject.NULL)

        val arr = readArrayOrEmpty(context, indexDoc)
        arr.put(entry)

        // Cap size (drop oldest)
        val capped = if (arr.length() > MAX_ENTRIES) {
            val start = arr.length() - MAX_ENTRIES
            val out = JSONArray()
            for (i in start until arr.length()) out.put(arr.get(i))
            out
        } else arr

        writeArray(context, indexDoc, capped)
    }

    fun computeSha256Hex(context: Context, file: DocumentFile): String? {
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            } ?: return null
            md.digest().joinToString("") { b -> "%02x".format(b) }
        }.getOrNull()
    }

    private fun readArrayOrEmpty(context: Context, doc: DocumentFile): JSONArray {
        return runCatching {
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                val text = input.bufferedReader().readText()
                val trimmed = text.trim()
                if (trimmed.isEmpty()) return JSONArray()
                JSONArray(trimmed)
            } ?: JSONArray()
        }.getOrDefault(JSONArray())
    }

    private fun writeArray(context: Context, doc: DocumentFile, arr: JSONArray) {
        runCatching {
            context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
                out.writer().use { w -> w.write(arr.toString(2)) }
            }
        }
    }
}
