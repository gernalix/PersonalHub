package com.gernalix.personalhub

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Choreographer
import androidx.work.Configuration
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.core.database.HubAutoExport
import com.example.multitimetracker.perf.StartupPerfTrace
import com.gernalix.personalhub.core.database.capsules.sync.DatasetteSync
import com.wordpulse.app.WordPulseApplication
import com.example.multitimetracker.hub.TimerSessionHubAdapter
import com.gernalix.luoghi.hub.PlacesHubAdapter
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.ResourceHubAdapter
import com.gernalix.personalhub.soldi.hub.SoldiTransactionHubAdapter
import com.gernalix.sostanze.hub.SubstanceHubAdapter
import com.gernalix.sostanze.hub.SubstanceIntakeHubAdapter
import com.supercontacts.app.hub.PeopleHubAdapter
import com.wordpulse.app.hub.WordSessionHubAdapter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PersonalHubApplication : WordPulseApplication(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        if (android.app.Application.getProcessName() != packageName) { super.onCreate(); return }
        StartupPerfTrace.applicationOnCreate()
        DatabaseVault.recoverInterruptedImport(this)
        super.onCreate()
        HubContextRuntime.initialize(this, listOf(
            PeopleHubAdapter(this), TimerSessionHubAdapter(this), PlacesHubAdapter(this),
            SoldiTransactionHubAdapter(this), SubstanceHubAdapter(this), SubstanceIntakeHubAdapter(this), WordSessionHubAdapter(this),
            ResourceHubAdapter(this),
        ))
        PostFirstFrameStartup.install(this)
    }
}

private object PostFirstFrameStartup {
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "personalhub-post-frame-startup").apply { isDaemon = true } }

    fun install(app: Application) {
        if (started.get()) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!started.compareAndSet(false, true)) return
                app.unregisterActivityLifecycleCallbacks(this)
                Choreographer.getInstance().postFrameCallback {
                    executor.execute {
                        DatabaseVault.cleanupOrphanedPreImportBackups(app)
                        HubAutoExport.start(app)
                        DatasetteSync.start(app)
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
}
