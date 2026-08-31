// v460
package com.example.multitimetracker

import android.content.Context
import com.example.multitimetracker.persistence.DataIntegrityGate
import com.example.multitimetracker.persistence.MultiDbVaults
import com.example.multitimetracker.persistence.PersistentSaveOrigin
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.persistence.SqliteVault
import com.example.multitimetracker.perf.StartupPerfTrace
import com.example.multitimetracker.util.AppRestarter
import com.example.multitimetracker.model.HomeLoadState

internal class MainViewModelRecoveryCoordinator(
    private val snapshotCoordinator: MainViewModelSnapshotCoordinator,
    private val isInitialized: () -> Boolean,
    private val setInitialized: (Boolean) -> Unit,
    private val integrityBlock: () -> DataIntegrityGate.GateResult?,
    private val setIntegrityBlock: (DataIntegrityGate.GateResult?) -> Unit,
    private val setImportVerificationReport: (String?) -> Unit,
    private val showVaultActivationVerified: (Context, String) -> Unit,
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

        val pendingStartupRestoreSource = SqliteVault.consumePendingStartupRestore(context)
        val snapshotLoaded = try {
            var loaded = StartupPerfTrace.section("load_persisted_snapshot") {
                snapshotCoordinator.loadPersistedSnapshotIfAvailable(context)
            }
            if (!loaded && SqliteVault.restoreInstrumentationSafetyBackupIfCurrentEmpty(context)) {
                loaded = StartupPerfTrace.section("load_instrumentation_safety_backup") {
                    snapshotCoordinator.loadPersistedSnapshotIfAvailable(context)
                }
            }
            loaded
        } catch (error: Throwable) {
            snapshotCoordinator.markPersistenceFailed()
            setIntegrityBlock(initializationFailedBlock(context, error))
            setHomeLoadState(HomeLoadState.Error)
            StartupPerfTrace.mark("initialize_snapshot_read_failed=${error::class.java.simpleName}")
            return
        }
        if (snapshotLoaded) {
            setInitialized(true)
            reportPendingStartupRestoreIfNeeded(
                context = context,
                pendingSource = pendingStartupRestoreSource,
                snapshotLoaded = true,
            )
            reportPendingVaultActivationIfNeeded(context)
            AppRestarter.cancelPendingRestart()
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
        reportPendingStartupRestoreIfNeeded(
            context = context,
            pendingSource = pendingStartupRestoreSource,
            snapshotLoaded = false,
        )
        reportPendingVaultActivationIfNeeded(context)
        AppRestarter.cancelPendingRestart()
        StartupPerfTrace.mark("initialize_done")
    }

    fun tryRecoverFromPreImportBackup(context: Context): Boolean {
        val ok = runCatching { SqliteVault.restoreInternalDbFromPreImportBackup(context) }.getOrDefault(false)
        if (!ok) {
            setIntegrityBlock(recoveryFailedBlock(context))
            return false
        }
        if (ok) {
            val gate = DataIntegrityGate.runCriticalChecks(context)
            if (!gate.ok) {
                setIntegrityBlock(gate)
                return false
            }
            val runtimeFailure = restoreRecoveredRuntime(context)
            if (runtimeFailure != null) {
                setIntegrityBlock(runtimeFailure)
                return false
            }
            setIntegrityBlock(null)
        }
        return ok
    }

    fun tryRecoverFromUserFolder(context: Context): Boolean {
        val ok = runCatching { SqliteVault.overwriteInternalDbFromUserFolder(context) }.isSuccess
        if (!ok) {
            setIntegrityBlock(recoveryFailedBlock(context))
            return false
        }
        if (ok) {
            val gate = DataIntegrityGate.runCriticalChecks(context)
            if (!gate.ok) {
                setIntegrityBlock(gate)
                return false
            }
            val runtimeFailure = restoreRecoveredRuntime(context)
            if (runtimeFailure != null) {
                setIntegrityBlock(runtimeFailure)
                return false
            }
            setIntegrityBlock(null)
        }
        return ok
    }

    private fun recoveryFailedBlock(context: Context): DataIntegrityGate.GateResult {
        val unreadable = context.getString(R.string.import_snapshot_unreadable)
        return DataIntegrityGate.GateResult(
            ok = false,
            blockingTitle = context.getString(R.string.integrity_gate_title),
            blockingBody = unreadable,
            technicalReport = unreadable,
        )
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

        if (AppRestarter.consumeBackgroundPersistSkip()) {
            clearRunningUsageWithoutPersist()
            return
        }

        val delta = (nowMs - start).coerceAtLeast(0L)
        applyBackgroundUsageDelta(delta)
    }

    private fun restoreRecoveredRuntime(context: Context): DataIntegrityGate.GateResult? {
        if (SnapshotStore.load(context) == null) {
            val unreadable = context.getString(R.string.import_snapshot_unreadable)
            return DataIntegrityGate.GateResult(
                ok = false,
                blockingTitle = context.getString(R.string.integrity_gate_title),
                blockingBody = unreadable,
                technicalReport = unreadable,
            )
        }
        setInitialized(false)
        snapshotCoordinator.reloadFromSnapshot(context)
        initialize(context)

        val initFailure = integrityBlock()
        if (initFailure?.ok == false) return initFailure

        val runtimeFailure = snapshotCoordinator.verifyCurrentPersistedSnapshotActivated(context) ?: return null
        return DataIntegrityGate.GateResult(
            ok = false,
            blockingTitle = context.getString(R.string.integrity_gate_title),
            blockingBody = runtimeFailure,
            technicalReport = runtimeFailure,
        )
    }

    private fun reportPendingVaultActivationIfNeeded(context: Context) {
        val notice = MultiDbVaults.consumePendingActivationNotice(
            context = context,
            activeSignature = snapshotCoordinator.computeBackupSignature(),
        ) ?: return

        val runtimeFailure = snapshotCoordinator.verifyCurrentPersistedSnapshotActivated(context)
        val activeVaultMatches = MultiDbVaults.getActiveVaultName(context).equals(notice.vaultName, ignoreCase = true)
        if (activeVaultMatches && runtimeFailure == null) {
            showVaultActivationVerified(context, notice.vaultName)
            return
        }

        val baseMessage = context.getString(
            R.string.multidb_switch_runtime_verify_failed_fmt,
            notice.vaultName,
        )
        setImportVerificationReport(
            if (runtimeFailure.isNullOrBlank()) baseMessage else baseMessage + "\n\n" + runtimeFailure
        )
    }

    private fun reportPendingStartupRestoreIfNeeded(
        context: Context,
        pendingSource: String?,
        snapshotLoaded: Boolean,
    ) {
        if (pendingSource.isNullOrBlank()) return
        if (!snapshotLoaded) {
            setImportVerificationReport(context.getString(R.string.import_snapshot_unreadable))
            return
        }
        setImportVerificationReport(snapshotCoordinator.verifyCurrentPersistedSnapshotActivated(context))
    }
}
