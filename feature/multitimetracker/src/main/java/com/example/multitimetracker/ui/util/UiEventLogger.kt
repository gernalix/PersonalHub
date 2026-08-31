package com.example.multitimetracker.ui.util

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.multitimetracker.persistence.AuditLogSqlite
import org.json.JSONObject

/**
 * Logs tiny UI micro-events (especially toasts) into the Audit Log to make debugging reproducible.
 *
 * Design goals:
 * - Never throw (logging must not crash the app).
 * - Keep payload small but useful (screen, reason, message).
 */
@OptIn(com.example.multitimetracker.util.CapsuleWriteApi::class)
object UiEventLogger {

    fun toastLogged(
        context: Context,
        message: String,
        screen: String,
        reason: String,
        payload: JSONObject? = null,
        length: Int = Toast.LENGTH_SHORT
    ) {
        runCatching { Toast.makeText(context, message, length).show() }
            .onFailure { Log.e("UiEventLogger", "toast show failed", it) }

        val p = (payload ?: JSONObject())
            .put("screen", screen)
            .put("reason", reason)
            .put("message", message)

        runCatching {
            AuditLogSqlite.insert(
                context = context,
                action = "UI_TOAST",
                summary = message,
                payload = p,
                isSystem = true
            )
        }.onFailure { Log.e("UiEventLogger", "audit insert failed", it) }
    }
}
