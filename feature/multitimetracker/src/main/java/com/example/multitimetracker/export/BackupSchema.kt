package com.example.multitimetracker.export

import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for backup/import files.
 *
 * Export writes [MANIFEST_FILE] generated from [entries].
 * Import (CsvImporter) uses this manifest when present.
 */
object BackupSchema {
    const val MANIFEST_FILE = "manifest.json"

    /**
     * Bump this when you make backward-incompatible changes to the export set or format.
     * (Minor/optional additions do not need a bump.)
     */
    const val SCHEMA_VERSION = 3

    data class Entry(
        val name: String,
        val required: Boolean,
        val handlerId: String
    )

    /**
     * NOTE: Keep this list in sync with CsvExporter.exportAllToDirectory.
     * The exporter writes all these files.
     */
    val entries: List<Entry> = listOf(
        Entry(name = "dict.json", required = false, handlerId = "dict_v1"),
        // Runtime: alerts (time-fence rules) + chains.
        Entry(name = "runtime.json", required = false, handlerId = "runtime_v1"),
        Entry(name = "sessions.csv", required = true, handlerId = "sessions_v1"),
        Entry(name = "totals.csv", required = false, handlerId = "totals_ignore"),
        Entry(name = "tag_sessions.csv", required = true, handlerId = "tag_sessions_v1"),
        Entry(name = "tag_totals.csv", required = false, handlerId = "tag_totals_ignore"),
        Entry(name = "app_usage.csv", required = false, handlerId = "app_usage_v1"),
        Entry(name = "quick_events.json", required = false, handlerId = "quick_events_v2")
    )

    fun buildManifestJson(exportedAtMs: Long = System.currentTimeMillis()): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("exportedAt", exportedAtMs)
        val files = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("name", e.name)
            o.put("required", e.required)
            o.put("handler", e.handlerId)
            files.put(o)
        }
        root.put("files", files)
        return root.toString(2) + "\n"
    }

    data class ParsedManifest(
        val schemaVersion: Int,
        val files: List<Entry>
    )

    fun parseManifestJson(text: String): ParsedManifest {
        val root = JSONObject(text)
        val schemaVersion = root.optInt("schemaVersion", 0)
        val filesArr = root.optJSONArray("files") ?: JSONArray()
        val out = ArrayList<Entry>(filesArr.length())
        for (i in 0 until filesArr.length()) {
            val o = filesArr.optJSONObject(i) ?: continue
            out.add(
                Entry(
                    name = o.optString("name"),
                    required = o.optBoolean("required", false),
                    handlerId = o.optString("handler")
                )
            )
        }
        return ParsedManifest(schemaVersion = schemaVersion, files = out)
    }
}
