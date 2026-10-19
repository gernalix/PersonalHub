package com.gernalix.personalhub

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.room.withTransaction
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventDraft
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private const val PREFS = "app-update-acknowledgement"
    private lateinit var coordinator: ResumedUpdateCheck

    fun install(app: Application) {
        coordinator = ResumedUpdateCheck(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            pendingVersion = {
                withContext(Dispatchers.IO) {
                    val info = app.packageManager.getPackageInfo(app.packageName, 0)
                    val version = info.longVersionCode
                    val prefs = app.getSharedPreferences(PREFS, 0)
                    val previous = prefs.getLong("observed-version", 0L)
                    if (previous != version) {
                        val wasUpdate = previous > 0 || info.lastUpdateTime != info.firstInstallTime
                        if (wasUpdate) {
                            recordAppUpdate(PersonalHubDatabase.get(app), version, previous.takeIf { it > 0 })
                        }
                        check(prefs.edit().putLong("observed-version", version).apply {
                            // A fresh installation is the baseline, not an invented update.
                            if (!wasUpdate) putLong("acknowledged-version", version)
                        }.commit())
                    }
                    version.takeIf { prefs.getLong("acknowledged-version", 0L) != it }
                }
            },
            showPrompt = { activity, pending ->
                if (dialog == null) {
                    dialog = AlertDialog.Builder(activity)
                        .setMessage("app aggiornata alla v$pending")
                        .setCancelable(false)
                        .setPositiveButton("OK") { _, _ ->
                            app.getSharedPreferences(PREFS, 0).edit()
                                .putLong("acknowledged-version", pending).apply()
                        }
                        .create().also { shown ->
                            shown.setOwnerActivity(activity)
                            shown.setOnDismissListener { if (dialog === shown) dialog = null }
                            shown.show()
                        }
                }
            },
        )
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is ComponentActivity) coordinator.onResume(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is ComponentActivity) coordinator.onPause(activity)
        if (dialog?.ownerActivity === activity) { dialog?.dismiss(); dialog = null }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** The database gate recreates the first Activity: finish the check in the process,
 * then present on the currently resumed Activity rather than the discarded one. */
internal class ResumedUpdateCheck(
    private val scope: CoroutineScope,
    private val pendingVersion: suspend () -> Long?,
    private val showPrompt: (ComponentActivity, Long) -> Unit,
) {
    private var resumed: ComponentActivity? = null
    private var checking = false

    fun onResume(activity: ComponentActivity) {
        resumed = activity
        if (checking) return
        checking = true
        scope.launch {
            try {
                val pending = pendingVersion()
                val target = resumed
                if (pending != null && target != null && !target.isFinishing && !target.isDestroyed &&
                    target.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    showPrompt(target, pending)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No acknowledgement on failure: the next foreground entry retries.
                android.util.Log.w("PersonalHubUpdate", "Installed-version recording will be retried")
            } finally {
                checking = false
            }
        }
    }

    fun onPause(activity: ComponentActivity) {
        if (resumed === activity) resumed = null
    }
}
