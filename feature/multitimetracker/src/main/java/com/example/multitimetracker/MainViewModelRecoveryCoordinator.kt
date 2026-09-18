// v460
package com.example.multitimetracker

import android.content.Context
import com.example.multitimetracker.persistence.DataIntegrityGate
import com.example.multitimetracker.persistence.PersistentSaveOrigin
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.perf.StartupPerfTrace
import com.example.multitimetracker.model.HomeLoadState

internal class MainViewModelRecoveryCoordinator(
    private val snapshotCoordinator: MainViewModelSnapshotCoordinator,
    private val isInitialized: () -> Boolean,
    private val setInitialized: (Boolean) -> Unit,
    private val integrityBlock: () -> DataIntegrityGate.GateResult?,
    private val setIntegrityBlock: (DataIntegrityGate.GateResult?) -> Unit,
    private val resetToFreshInstallState: (Long) -> Unit,
    private val setHomeLoadState: (HomeLoadState) -> Unit,
    private val appForegroundStartMs: () -> Long?,
    private val stateAppUsageRunningSinceMs: () -> Long?,
    private val clearForegroundUsageTracking: () -> Unit,
    private val clearRunningUsageWithoutPersist: () -> Unit,
    private val applyBackgroundUsageDelta: (Long) -> Unit,
) {

    fun initialize(context: Context) {
        if (isInitialized()) return
        snapshotCoordinator.markPersistenceLoading()

        StartupPerfTrace.mark("initialize_start")
        val fastHomeApplied = StartupPerfTrace.section("startup_home_fast_path") {
            snapshotCoordinator.loadStartupHomeFromSessionTables(context)
        }
        if (!fastHomeApplied) setHomeLoadState(HomeLoadState.Loading)
        val gate = StartupPerfTrace.section("integrity_gate") {
            DataIntegrityGate.runCriticalChecks(context)
        }
        if (!gate.ok) {
            snapshotCoordinator.markPersistenceFailed()
            setIntegrityBlock(gate)
            setHomeLoadState(HomeLoadState.Error)
            StartupPerfTrace.mark("initialize_error state=${HomeLoadState.Error.name}")
            return
        }
        setIntegrityBlock(null)

        val snapshotLoaded = try {
            StartupPerfTrace.section("load_persisted_snapshot") {
                snapshotCoordinator.loadPersistedSnapshotIfAvailable(context)
            }
        } catch (error: Throwable) {
            snapshotCoordinator.markPersistenceFailed()
            setIntegrityBlock(initializationFailedBlock(context, error))
            setHomeLoadState(HomeLoadState.Error)
            StartupPerfTrace.mark("initialize_snapshot_read_failed=${error::class.java.simpleName}")
            return
        }
        if (snapshotLoaded) {
            setInitialized(true)
            return
        }

        resetToFreshInstallState(System.currentTimeMillis())
        StartupPerfTrace.mark("state_applied home_load_state=${HomeLoadState.ReadyEmpty.name} running_sessions=0")
        snapshotCoordinator.markPersistenceReady()
        snapshotCoordinator.rememberCurrentStateAsPersisted()
        snapshotCoordinator.clearPersistenceFailureReport()
        try {
            snapshotCoordinator.persistOrThrow(
                showFailureUi = true,
                origin = PersistentSaveOrigin.INITIALIZATION,
            )
        } catch (error: Throwable) {
            snapshotCoordinator.markPersistenceFailed()
            setIntegrityBlock(initializationFailedBlock(context, error))
            setHomeLoadState(HomeLoadState.Error)
            return
        }
        setInitialized(true)
        snapshotCoordinator.scheduleAutoBackup()
        StartupPerfTrace.mark("initialize_done")
    }

    private fun initializationFailedBlock(
        context: Context,
        error: Throwable,
    ): DataIntegrityGate.GateResult {
        val detail = "Persistent snapshot load failed: ${error::class.java.simpleName}: ${error.message}"
        return DataIntegrityGate.GateResult(
            ok = false,
            blockingTitle = context.getString(R.string.integrity_gate_title),
            blockingBody = context.getString(R.string.integrity_gate_body),
            technicalReport = detail,
        )
    }

    fun clearIntegrityBlock() {
        setIntegrityBlock(null)
    }

    fun onAppBackground(nowMs: Long) {
        val start = appForegroundStartMs() ?: stateAppUsageRunningSinceMs() ?: return
        clearForegroundUsageTracking()

        val delta = (nowMs - start).coerceAtLeast(0L)
        applyBackgroundUsageDelta(delta)
    }

}
