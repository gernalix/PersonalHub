package com.gernalix.personalhub

import com.example.multitimetracker.capsules.remotesync.RemoteSyncScheduler
import com.example.multitimetracker.perf.StartupPerfTrace
import com.wordpulse.app.WordPulseApplication

class PersonalHubApplication : WordPulseApplication() {
    override fun onCreate() {
        if (android.app.Application.getProcessName() != packageName) { super.onCreate(); return }
        com.gernalix.personalhub.core.database.DatabaseVault.recoverInterruptedImport(this)
        super.onCreate()
        com.gernalix.personalhub.core.database.HubAutoExport.start(this)
        StartupPerfTrace.applicationOnCreate()
        RemoteSyncScheduler.ensurePeriodicRecovery(this)
    }
}
