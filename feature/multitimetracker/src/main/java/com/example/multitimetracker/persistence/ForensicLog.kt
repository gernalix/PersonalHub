package com.example.multitimetracker.persistence

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant

object ForensicLog {
    private const val LOG_FILE = "forensic_events.jsonl"

    fun record(
        context: Context,
        component: String,
        action: String,
        sourceFile: String? = null,
        destFile: String? = null,
        beforeCounts: CriticalDataCounts? = null,
        counts: CriticalDataCounts? = null,
        details: JSONObject? = null,
        error: String? = null,
        throwable: Throwable? = null,
    ) {
        val nowMs = System.currentTimeMillis()
        val line = JSONObject()
            .put("timestamp", Instant.ofEpochMilli(nowMs).toString())
            .put("component", component)
            .put("action", action)
            .put("sourceFile", sourceFile ?: JSONObject.NULL)
            .put("destFile", destFile ?: JSONObject.NULL)
            .put("beforeCriticalCounts", beforeCounts?.toJson() ?: JSONObject.NULL)
            .put("criticalCounts", counts?.toJson() ?: JSONObject.NULL)
            .put("details", details ?: JSONObject.NULL)
            .put("error", error ?: throwable?.message ?: JSONObject.NULL)
            .put("stacktrace", throwable?.stackTraceToString() ?: JSONObject.NULL)
            .toString() + "\n"

        runCatching {
            File(context.filesDir, LOG_FILE).appendText(line, Charsets.UTF_8)
        }
    }
}
