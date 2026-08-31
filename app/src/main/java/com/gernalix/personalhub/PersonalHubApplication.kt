package com.gernalix.personalhub

import com.example.multitimetracker.capsules.remotesync.RemoteSyncScheduler
import com.example.multitimetracker.perf.StartupPerfTrace
import com.wordpulse.app.WordPulseApplication

class PersonalHubApplication : WordPulseApplication() {
    override fun onCreate() {
        super.onCreate()
        StartupPerfTrace.applicationOnCreate()
        RemoteSyncScheduler.ensurePeriodicRecovery(this)
    }
}
