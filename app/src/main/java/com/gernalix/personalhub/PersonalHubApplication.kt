package com.gernalix.personalhub

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Trace
import android.util.Log
import android.view.Choreographer
import androidx.work.Configuration
import com.example.multitimetracker.api.TimerStartupApi
import com.example.multitimetracker.hub.TimerSessionHubAdapter
import com.gernalix.luoghi.hub.PlacesHubAdapter
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.core.database.HubAutoExport
import com.gernalix.personalhub.core.database.capsules.sync.DatasetteSync
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSync
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.ResourceHubAdapter
import com.gernalix.personalhub.soldi.hub.SoldiTransactionHubAdapter
import com.gernalix.personalhub.workflowydays.WorkflowyDaysSync
import com.gernalix.personalhub.salute.hub.HealthHubAdapter
import com.gernalix.sostanze.hub.SubstanceHubAdapter
import com.gernalix.sostanze.hub.SubstanceIntakeHubAdapter
import com.supercontacts.app.hub.PeopleHubAdapter
import com.wordpulse.app.hub.WordSessionHubAdapter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

class PersonalHubApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() != packageName) return

        traceStartup("PH.recoverInterruptedImport") {
            DatabaseVault.recoverInterruptedImport(this)
        }
        val startupReady = traceStartup("PH.ensureStartupReady") {
            DatabaseVault.ensureStartupReady(this)
        }
        if (!startupReady) return

        traceStartup("PH.timerStartupHook") {
            TimerStartupApi.applicationOnCreate()
        }
        traceStartup("PH.hubRuntimeInit") {
            HubContextRuntime.initialize(
                this,
                listOf(
                    PeopleHubAdapter(this),
                    TimerSessionHubAdapter(this),
                    PlacesHubAdapter(this),
                    SoldiTransactionHubAdapter(this),
                    SubstanceHubAdapter(this),
                    HealthHubAdapter(this, "event"),
                    HealthHubAdapter(this, "sample"),
                    HealthHubAdapter(this, "measurement"),
                    HealthHubAdapter(this, "journal"),
                    SubstanceIntakeHubAdapter(this),
                    WordSessionHubAdapter(this),
                    ResourceHubAdapter(this),
                ),
            )
        }
        traceStartup("PH.postFirstFrameInstall") {
            PostFirstFrameStartup.install(this)
        }
    }
}

private object PostFirstFrameStartup {
    private const val TAG = "PersonalHubStartup"
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(
            {
                // These maintenance tasks are not latency-sensitive. Keep them below the UI
                // thread in scheduler priority so post-display repair/sync cannot win CPU time
                // from focus dispatch, drawing or the user's first interaction.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            },
            "personalhub-post-frame-startup",
        ).apply { isDaemon = true }
    }

    fun install(app: Application) {
        if (started.get()) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!started.compareAndSet(false, true)) return
                app.unregisterActivityLifecycleCallbacks(this)
                val timerOpening = activity is com.example.multitimetracker.MainActivity
                // A Choreographer callback runs at the start of a frame. Waiting for a second
                // callback guarantees that the first resumed Activity has had one frame to draw
                // before maintenance work starts. The worker itself runs at Android background
                // priority; no arbitrary time delay is used to hide startup work.
                Choreographer.getInstance().postFrameCallback {
                    Choreographer.getInstance().postFrameCallback {
                        executor.execute {
                            if (timerOpening && !TimerStartupApi.awaitFirstUsableScreen()) return@execute
                            runStep("profile runtime restore", "PH.bg.profileRuntime") {
                                runBlocking { ProfileRuntimeCoordinator.restoreActiveProfile(app) }
                            }
                            runStep("legacy Timer tag/session repair", "PH.bg.timerRepair") {
                                TimerStartupApi.repairLegacyTagSessionsAfterHostDatabaseRecovery(app)
                            }
                            runStep("database backup cleanup", "PH.bg.backupCleanup") {
                                DatabaseVault.cleanupOrphanedPreImportBackups(app)
                            }
                            runStep("auto-export", "PH.bg.autoExport") {
                                HubAutoExport.start(app)
                            }
                            runStep("Datasette sync", "PH.bg.datasetteSync") {
                                DatasetteSync.start(app)
                            }
                            runStep("Git data sync", "PH.bg.gitDataSync") {
                                GitDataSync.start(app)
                            }
                            runStep("Workflowy days sync", "PH.bg.workflowySync") {
                                WorkflowyDaysSync.ensureScheduled(app)
                            }
                        }
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun runStep(name: String, traceSection: String, block: () -> Unit) {
        runCatching {
            traceStartup(traceSection, block)
        }.onFailure { error ->
            Log.e(TAG, "$name failed", error)
        }
    }
}

private inline fun <T> traceStartup(section: String, block: () -> T): T {
    Trace.beginSection(section)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
