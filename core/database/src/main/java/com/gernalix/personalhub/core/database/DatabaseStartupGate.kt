package com.gernalix.personalhub.core.database

import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

data class DatabaseStartupStatus(
    val ready: Boolean,
    val currentVersion: Int?,
    val requiredVersion: Int,
    val reason: String?,
)

object DatabaseStartupGate {
    fun status(context: Context): DatabaseStartupStatus {
        val app = context.applicationContext
        val file = app.getDatabasePath(PersonalHubDatabase.DB_NAME)
        val currentVersion = if (file.isFile) {
            runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
            }.getOrNull()
        } else null
        val requiredVersion = PersonalHubDatabase.SCHEMA_VERSION
        if (file.isFile && currentVersion != requiredVersion) {
            return DatabaseStartupStatus(
                ready = false,
                currentVersion = currentVersion,
                requiredVersion = requiredVersion,
                reason = if (currentVersion == null) {
                    "The PersonalHub database is unreadable. Existing data was preserved."
                } else {
                    "Database schema $currentVersion is installed, but this PersonalHub build requires schema $requiredVersion. Existing data was preserved."
                },
            )
        }
        val ready = DatabaseVault.ensureStartupReady(app)
        return DatabaseStartupStatus(
            ready = ready,
            currentVersion = currentVersion,
            requiredVersion = requiredVersion,
            reason = if (ready) null else DatabaseVault.error(app),
        )
    }

    fun blockIfNotReady(activity: Activity): Boolean {
        val status = status(activity)
        if (status.ready) return false

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val current = status.currentVersion?.toString() ?: "unreadable"
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(TextView(activity).apply {
            text = "Database update required"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(activity).apply {
            text = buildString {
                append("PersonalHub blocked this screen before any module could access the database.\n\n")
                append("Installed schema: $current\n")
                append("Required schema: ${status.requiredVersion}\n\n")
                append(status.reason ?: "The database is not ready.")
                append("\n\nRun the external database migration, then reopen PersonalHub.")
            }
            textSize = 16f
            setPadding(0, dp(16), 0, dp(20))
        })
        root.addView(Button(activity).apply {
            text = "Close PersonalHub"
            setOnClickListener { activity.finishAffinity() }
        })
        activity.setContentView(root)
        return true
    }
}
