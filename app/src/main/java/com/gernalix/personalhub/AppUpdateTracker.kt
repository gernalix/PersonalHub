package com.gernalix.personalhub

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventDraft
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One installed-version fact feeds both Audit and the shared History; acknowledgement is local UI state. */
internal suspend fun recordAppUpdate(db: PersonalHubDatabase, version: Long, previousVersion: Long?): Boolean = db.withTransaction {
    require(version > 0)
    val sql = db.openHelper.writableDatabase
    val alreadyRecorded = sql.query(
        "SELECT 1 FROM mutation_events WHERE event_type='app.version.updated' AND entity_id=? LIMIT 1",
        arrayOf(version.toString()),
    ).use { it.moveToFirst() }
    if (alreadyRecorded) return@withTransaction false
    MutationEventStore.append(sql, MutationEventDraft(
        transactionId = "app-update:$version",
        module = "settings",
        eventType = "app.version.updated",
        entityType = "app_version",
        entityId = version.toString(),
        actorType = "system",
        actorSource = "package-update",
        beforeJson = JSONObject().put("version", previousVersion ?: JSONObject.NULL).toString(),
        afterJson = JSONObject().put("version", version).toString(),
        contextJson = JSONObject().put("name", "app aggiornata alla v$version").toString(),
    ))
    true
}

internal object AppUpdateTracker : Application.ActivityLifecycleCallbacks {
    private var dialog: AlertDialog? = null
    private var checking = false
    private const val PREFS = "app-update-acknowledgement"

    fun install(app: Application) { app.registerActivityLifecycleCallbacks(this) }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is ComponentActivity || checking || dialog != null) return
        checking = true
        activity.lifecycleScope.launch {
            try {
                val pending = withContext(Dispatchers.IO) {
                    val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    val version = info.longVersionCode
                    val prefs = activity.getSharedPreferences(PREFS, 0)
                    val previous = prefs.getLong("observed-version", 0L)
                    if (previous != version) {
                        val wasUpdate = previous > 0 || info.lastUpdateTime != info.firstInstallTime
                        if (wasUpdate) {
                            recordAppUpdate(PersonalHubDatabase.get(activity), version, previous.takeIf { it > 0 })
                        }
                        check(prefs.edit().putLong("observed-version", version).apply {
                            // A fresh installation is the baseline, not an invented update.
                            if (!wasUpdate) putLong("acknowledged-version", version)
                        }.commit())
                    }
                    version.takeIf { prefs.getLong("acknowledged-version", 0L) != it }
                }
                if (pending != null && !activity.isFinishing &&
                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    dialog = AlertDialog.Builder(activity)
                        .setMessage("app aggiornata alla v$pending")
                        .setCancelable(false)
                        .setPositiveButton("OK") { _, _ ->
                            activity.getSharedPreferences(PREFS, 0).edit()
                                .putLong("acknowledged-version", pending).apply()
                        }
                        .create().also { shown ->
                            shown.setOnDismissListener { if (dialog === shown) dialog = null }
                            shown.show()
                        }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preserve the unacknowledged update for retry; never crash or claim a saved event.
                android.util.Log.w("PersonalHubUpdate", "Installed-version recording will be retried")
            } finally {
                checking = false
            }
        }
    }

    override fun onActivityPaused(activity: Activity) { dialog?.dismiss(); dialog = null }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
