package com.gernalix.personalhub

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import androidx.work.Configuration
import com.example.multitimetracker.api.TimerStartupApi
import com.example.multitimetracker.hub.TimerSessionHubAdapter
import com.gernalix.luoghi.hub.PlacesHubAdapter
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.core.database.HubAutoExport
import com.gernalix.personalhub.core.database.capsules.sync.DatasetteSync
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.ResourceHubAdapter
import com.gernalix.personalhub.soldi.hub.SoldiTransactionHubAdapter
import com.gernalix.personalhub.workflowydays.WorkflowyDaysSync
import com.gernalix.sostanze.hub.SubstanceHubAdapter
import com.gernalix.sostanze.hub.SubstanceIntakeHubAdapter
import com.supercontacts.app.hub.PeopleHubAdapter
import com.wordpulse.app.hub.WordSessionHubAdapter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PersonalHubApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() != packageName) return
        DatabaseVault.recoverInterruptedImport(this)
        if (!DatabaseVault.ensureStartupReady(this)) return
        TimerStartupApi.applicationOnCreate()
        TimerStartupApi.repairLegacyTagSessionsAfterHostDatabaseRecovery(this)
        HubContextRuntime.initialize(
            this,
            listOf(
                PeopleHubAdapter(this),
                TimerSessionHubAdapter(this),
                PlacesHubAdapter(this),
                SoldiTransactionHubAdapter(this),
                SubstanceHubAdapter(this),
                SubstanceIntakeHubAdapter(this),
                WordSessionHubAdapter(this),
                ResourceHubAdapter(this),
            ),
        )
        PostFirstFrameStartup.install(this)
    }
}

private object PostFirstFrameStartup {
    private const val TAG = "PersonalHubStartup"
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "personalhub-post-frame-startup").apply { isDaemon = true }
    }

    fun install(app: Application) {
        if (started.get()) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!started.compareAndSet(false, true)) return
                app.unregisterActivityLifecycleCallbacks(this)
                Choreographer.getInstance().postFrameCallback {
                    executor.execute {
                        runStep("database backup cleanup") { DatabaseVault.cleanupOrphanedPreImportBackups(app) }
                        runStep("auto-export") { HubAutoExport.start(app) }
                        runStep("Datasette sync") { DatasetteSync.start(app) }
                        runStep("Workflowy days sync") { WorkflowyDaysSync.ensureScheduled(app) }
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

    private fun runStep(name: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            Log.e(TAG, "$name failed", error)
        }
    }
}
