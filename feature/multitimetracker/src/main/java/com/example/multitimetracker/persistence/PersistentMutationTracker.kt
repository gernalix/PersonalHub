package com.example.multitimetracker.persistence

import android.content.Context
import com.example.multitimetracker.capsules.remotesync.RemoteSyncScheduler
import com.example.multitimetracker.export.BackupFolderStore
import java.util.concurrent.Executors

object PersistentMutationTracker {
    private const val AUTOEXPORT_DEBOUNCE_MS = 1_200L
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mtt-autoexport").apply { isDaemon = true }
    }
    private val lock = Any()
    @Volatile private var pendingExport: Boolean = false
    @Volatile private var workerQueued: Boolean = false
    @Volatile private var generation: Long = 0L
    @Volatile internal var beforeExportForTests: (() -> Unit)? = null
    @Volatile internal var afterExportForTests: (() -> Unit)? = null

    fun record(context: Context, source: String) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        SyncStatusStore.markDatabaseMutation(appContext, source, now)
        requestExport(appContext)
        RemoteSyncScheduler.requestDebounced(appContext)
    }

    fun requestExport(context: Context) {
        val appContext = context.applicationContext
        if (BackupFolderStore.getTreeUri(appContext) == null) return

        synchronized(lock) {
            pendingExport = true
            if (workerQueued) return
            workerQueued = true
        }

        val workerGeneration = synchronized(lock) { generation }
        executor.execute {
            runSingleFlightLoop(appContext, workerGeneration)
        }
    }

    private fun runSingleFlightLoop(appContext: Context, workerGeneration: Long) {
        try {
            Thread.sleep(AUTOEXPORT_DEBOUNCE_MS)
            if (workerGeneration != generation) return
            while (true) {
                if (workerGeneration != generation) return
                synchronized(lock) {
                    pendingExport = false
                }

                beforeExportForTests?.invoke()
                try {
                    SqliteVault.exportToUserFolderIfConfigured(appContext, force = true)
                } finally {
                    afterExportForTests?.invoke()
                }

                val status = SyncStatusStore.read(appContext)
                val hasPending = synchronized(lock) { pendingExport }
                val dirtyAfterExport =
                    status.lastSuccessfulExportAtMs + SyncStatusStore.SYNC_TOLERANCE_MS < status.lastDatabaseMutationAtMs
                val failed = status.lastExportStatus == SyncStatusStore.ExportStatus.FAILED
                if (hasPending || (!failed && dirtyAfterExport)) {
                    continue
                }
                break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            val restart = synchronized(lock) {
                if (workerGeneration != generation) {
                    return@synchronized false
                }
                workerQueued = false
                if (pendingExport) {
                    workerQueued = true
                    true
                } else {
                    false
                }
            }
            if (restart) {
                val nextGeneration = synchronized(lock) { generation }
                executor.execute {
                    runSingleFlightLoop(appContext, nextGeneration)
                }
            }
        }
    }

    internal fun debounceMsForTests(): Long = AUTOEXPORT_DEBOUNCE_MS

    internal fun resetForTests() {
        synchronized(lock) {
            generation += 1L
            pendingExport = false
            workerQueued = false
            beforeExportForTests = null
            afterExportForTests = null
        }
    }
}
