package com.example.multitimetracker

import android.app.Application
import com.example.multitimetracker.capsules.remotesync.RemoteSyncScheduler
import com.example.multitimetracker.perf.StartupPerfTrace

class MultiTimeTrackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupPerfTrace.applicationOnCreate()
        RemoteSyncScheduler.ensurePeriodicRecovery(this)
    }
}
