package com.gernalix.personalhub.core.database.capsules.gitdata

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.gernalix.personalhub.core.database.DatabaseGate
import com.gernalix.personalhub.core.database.HubActivityCapture
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object GitDataSync {
    internal const val WORK = "personalhub-git-data-sync"
    internal const val RECOVERY_WORK = "personalhub-git-data-recovery"
    internal const val PUSH_DELAY_MS = 60_000L

    private val operations = ReentrantLock(true)
    @Volatile private var started = false

    fun <T> pauseSync(block: () -> T): T = operations.withLock(block)

    @Synchronized
    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        val config = runCatching { GitDataSettings.configuration(app) }.getOrNull()
        if (config?.enabled == true && config.configured) {
            operations.withLock {
                installTracking(app, enqueueAll = needsFullSnapshot(app))
                scheduleRecovery(app)
                checkForChanges(app)
            }
        }
        started = true
    }

    fun save(context: Context, repositoryUrl: String, token: String) = operations.withLock {
        val app = context.applicationContext
        val previous = runCatching { GitDataSettings.configuration(app) }.getOrNull()
        val repository = requireNotNull(GitRepository.parse(repositoryUrl.trim().trimEnd('/'))) {
            "Use an HTTPS GitHub repository URL"
        }
        val effectiveToken = token.ifBlank {
            runCatching { GitDataSettings.token(app) }.getOrDefault("")
        }
        require(effectiveToken.isNotBlank()) { "A GitHub access token is required" }
        GitHubDataTransport(repository, effectiveToken).validatePrivateWritable()
        GitDataSettings.save(app, repositoryUrl, token)
        val current = GitDataSettings.configuration(app)
        val repositoryChanged = previous?.repositoryUrl != current.repositoryUrl
        if (repositoryChanged) {
            GitDataSettings.stateManifestFile(app).delete()
        }
        if (current.enabled) {
            installTracking(app, enqueueAll = repositoryChanged || needsFullSnapshot(app))
            scheduleRecovery(app)
            checkForChanges(app)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) = operations.withLock {
        val app = context.applicationContext
        GitDataSettings.setEnabled(app, enabled)
        val db = PersonalHubDatabase.get(app).openHelper.writableDatabase
        if (enabled) {
            HubActivityCapture.uninstall(db)
            installTracking(app, enqueueAll = true)
            scheduleRecovery(app)
            checkForChanges(app)
        } else {
            GitDataTracking.uninstall(db)
            HubActivityCapture.install(db, appVersion(app))
            WorkManager.getInstance(app).cancelUniqueWork(WORK)
            WorkManager.getInstance(app).cancelUniqueWork(RECOVERY_WORK)
            GitDataSettings.setRuntimeState(app, "off")
        }
    }

    fun status(context: Context): GitDataStatus = GitDataSettings.status(context)

    fun checkForChanges(context: Context) {
        val app = context.applicationContext
        val config = runCatching { GitDataSettings.configuration(app) }.getOrNull() ?: return
        if (!config.enabled || !config.configured) return
        WorkManager.getInstance(app).enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<GitDataSyncWorker>()
                .setInitialDelay(PUSH_DELAY_MS, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun syncNow(context: Context, force: Boolean = false) = operations.withLock {
        val app = context.applicationContext
        val config = requireConfiguration(app)
        GitDataSettings.setRuntimeState(app, "syncing")
        try {
            val transport = transport(app, config)
            val head = transport.remoteHead()
            val control = pullControl(app, transport, head.commitSha)
            val bundle = GitDataFormat.exportPending(app)
            if (bundle == null) {
                GitDataSettings.markPulled(app, head.commitSha)
                GitDataSettings.setRuntimeState(app, "complete")
                return@withLock
            }
            val safety = GitDataSafety.evaluate(bundle)
            if (safety.suspicious && !force) {
                GitDataSettings.recordAttention(
                    app,
                    requireNotNull(safety.reason) + ". Review History and use Force push if intentional.",
                )
                return@withLock
            }
            val files = bundle.files.toMutableMap()
            if (control == null) {
                files[GIT_CONTROL_MANIFEST] = emptyControlManifest(app)
                    .toString(2)
                    .toByteArray(Charsets.UTF_8)
            }
            val revision = transport.pushFiles(
                files = files,
                message = buildString {
                    val authors = bundle.events.map { it.author }.distinct().sorted()
                    append("PersonalHub data generation ").append(bundle.generation)
                    append("\n\nPH-Events: ").append(bundle.events.size)
                    append("\nPH-Authors: ").append(authors.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "state-only")
                    append("\nPH-Schema: ").append(PersonalHubDatabase.SCHEMA_VERSION)
                },
                expectedHead = head,
            )
            GitDataFormat.acknowledge(app, bundle, revision)
            GitDataSettings.markPushed(app, bundle.generation, revision)
        } catch (error: Throwable) {
            GitDataSettings.recordError(app, error)
            throw error
        }
    }

    fun forcePushNow(context: Context) = syncNow(context, force = true)

    /**
     * Cherry-picks one declarative data patch from any commit/branch/tag without merging Git trees.
     * This is the safe PersonalHub equivalent of accepting one change from a data proposal PR.
     */
    fun previewPatchFromRevision(
        context: Context,
        ref: String,
        patchId: String,
    ): GitPatchPreview = operations.withLock {
        val app = context.applicationContext
        val config = requireConfiguration(app)
        require(ref.matches(Regex("[A-Za-z0-9._/-]+"))) { "Invalid Git ref" }
        require(patchId.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid patch id" }
        val transport = transport(app, config)
        val controlBytes = transport.readFile(GIT_CONTROL_MANIFEST, ref)
        val control = JSONObject(String(controlBytes, Charsets.UTF_8)).also(::validateControl)
        val patches = control.optJSONArray("patches") ?: JSONArray()
        var match: JSONObject? = null
        for (i in 0 until patches.length()) {
            val candidate = patches.getJSONObject(i)
            if (candidate.getString("id") == patchId) {
                match = candidate
                break
            }
        }
        val spec = requireNotNull(match) { "Patch is not present in selected revision" }
        require(spec.optLong("minimum_app_version", 0L) <= appVersion(app)) {
            "Patch requires a newer PersonalHub app"
        }
        val bytes = transport.readFile(spec.getString("path"), ref)
        require(GitDataFormat.sha256(bytes) == spec.getString("sha256")) {
            "Patch hash mismatch"
        }
        GitPatchEngine.preview(app, bytes)
    }

    fun applyPatchFromRevision(context: Context, ref: String, patchId: String) = operations.withLock {
        val app = context.applicationContext
        val config = requireConfiguration(app)
        require(ref.matches(Regex("[A-Za-z0-9._/-]+"))) { "Invalid Git ref" }
        require(patchId.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid patch id" }
        val transport = transport(app, config)
        val controlBytes = transport.readFile(GIT_CONTROL_MANIFEST, ref)
        val control = JSONObject(String(controlBytes, Charsets.UTF_8)).also(::validateControl)
        val patches = control.optJSONArray("patches") ?: JSONArray()
        var match: JSONObject? = null
        for (i in 0 until patches.length()) {
            val candidate = patches.getJSONObject(i)
            if (candidate.getString("id") == patchId) {
                match = candidate
                break
            }
        }
        val spec = requireNotNull(match) { "Patch is not present in selected revision" }
        require(patchId !in GitDataSettings.appliedPatchIds(app)) { "Patch was already applied" }
        require(spec.optLong("minimum_app_version", 0L) <= appVersion(app)) {
            "Patch requires a newer PersonalHub app"
        }
        val bytes = transport.readFile(spec.getString("path"), ref)
        require(GitDataFormat.sha256(bytes) == spec.getString("sha256")) {
            "Patch hash mismatch"
        }
        val document = JSONObject(String(bytes, Charsets.UTF_8))
        require(document.getString("patch_id") == patchId) { "Patch id mismatch" }
        GitPatchEngine.apply(app, bytes)
        GitDataSettings.markPatchApplied(app, patchId)
        checkForChanges(app)
    }

    /**
     * Startup fallback for an APK-supported schema whose one-shot transform lives in the optional
     * data repository rather than permanent Kotlin. The caller owns the staging file and performs
     * atomic replacement only after its normal validation succeeds.
     */
    internal fun migrateStagingFromRemote(
        context: Context,
        file: File,
        from: Int,
        to: Int,
    ): Boolean = operations.withLock {
        if (from == to) return@withLock true
        val app = context.applicationContext
        val config = runCatching { GitDataSettings.configuration(app) }.getOrNull()
            ?: return@withLock false
        if (!config.enabled || !config.configured) return@withLock false
        val transport = transport(app, config)
        val head = transport.remoteHead()
        val bytes = transport.readFileOrNull(GIT_CONTROL_MANIFEST, head.commitSha)
            ?: return@withLock false
        val control = JSONObject(String(bytes, Charsets.UTF_8)).also(::validateControl)
        require(control.optLong("minimum_app_version", 0L) <= appVersion(app)) {
            "Remote Git control requires a newer PersonalHub app"
        }
        require(to <= PersonalHubDatabase.SCHEMA_VERSION) {
            "Remote migration targets a schema unsupported by this APK"
        }
        if (!GitRemoteMigrationEngine.canMigrate(control, from, to)) return@withLock false
        GitRemoteMigrationEngine.migrate(
            context = app,
            transport = transport,
            ref = head.commitSha,
            control = control,
            file = file,
            from = from,
            to = to,
        )
        true
    }

    fun pullNow(context: Context) = operations.withLock {
        val app = context.applicationContext
        val config = requireConfiguration(app)
        GitDataSettings.setRuntimeState(app, "pulling")
        try {
            val transport = transport(app, config)
            val head = transport.remoteHead()
            pullControl(app, transport, head.commitSha)
            GitDataSettings.markPulled(app, head.commitSha)
            GitDataSettings.setRuntimeState(app, "complete")
        } catch (error: Throwable) {
            GitDataSettings.recordError(app, error)
            throw error
        }
    }

    /**
     * Restores the complete logical state from a Git revision. DatabaseVault keeps the old database
     * recoverable and freezes the old application graph; the UI must restart the process on success.
     */
    fun restoreRevision(context: Context, revision: String) = operations.withLock {
        val app = context.applicationContext
        val config = requireConfiguration(app)
        // A radical restore must never strand the current local state outside Git.
        if (hasPendingHistory(app)) {
            syncNow(app)
            require(!hasPendingHistory(app)) {
                "Current PersonalHub state is not safely pushed; restore aborted"
            }
        }
        GitDataSettings.setRuntimeState(app, "restoring")
        try {
            val transport = transport(app, config)
            val head = transport.remoteHead()
            val controlBytes = transport.readFileOrNull(GIT_CONTROL_MANIFEST, head.commitSha)
            val control = controlBytes?.let {
                JSONObject(String(it, Charsets.UTF_8)).also(::validateControl)
            }
            GitStateRestorer.restore(
                context = app,
                transport = transport,
                revision = revision.trim(),
                control = control,
                controlRef = head.commitSha,
            )
            GitDataSettings.setRuntimeState(app, "complete")
        } catch (error: Throwable) {
            GitDataSettings.recordError(app, error)
            throw error
        }
    }

    /**
     * Called by every whole-database import/restore. It runs while DatabaseGate is privileged, so
     * tracking metadata is installed atomically before normal writers resume in the next process.
     */
    fun onDatabaseReplaced(context: Context) {
        val app = context.applicationContext
        val config = runCatching { GitDataSettings.configuration(app) }.getOrNull() ?: return
        if (!config.enabled || !config.configured) return
        GitDataSettings.stateManifestFile(app).delete()
        installTracking(app, enqueueAll = true)
        checkForChanges(app)
    }

    private fun hasPendingHistory(context: Context): Boolean = DatabaseGate.access {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        GitDataTracking.pending(db).isNotEmpty() || GitDataTracking.events(db).isNotEmpty()
    }

    private fun pullControl(
        context: Context,
        transport: GitHubDataTransport,
        ref: String,
    ): JSONObject? {
        val bytes = transport.readFileOrNull(GIT_CONTROL_MANIFEST, ref) ?: return null
        val control = JSONObject(String(bytes, Charsets.UTF_8))
        validateControl(control)
        val appVersion = appVersion(context)
        require(control.optLong("minimum_app_version", 0L) <= appVersion) {
            "Remote Git control requires a newer PersonalHub app"
        }
        require(
            control.optInt("target_schema_version", PersonalHubDatabase.SCHEMA_VERSION) <=
                PersonalHubDatabase.SCHEMA_VERSION,
        ) {
            "Remote Git control targets a newer database schema"
        }

        val applied = GitDataSettings.appliedPatchIds(context)
        val patches = control.optJSONArray("patches") ?: JSONArray()
        for (i in 0 until patches.length()) {
            val spec = patches.getJSONObject(i)
            val id = spec.getString("id")
            if (id in applied) continue
            require(id.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid patch id" }
            require(spec.optLong("minimum_app_version", 0L) <= appVersion) {
                "Patch requires a newer PersonalHub app"
            }
            val patchBytes = transport.readFile(spec.getString("path"), ref)
            require(GitDataFormat.sha256(patchBytes) == spec.getString("sha256")) {
                "Patch hash mismatch: $id"
            }
            val document = JSONObject(String(patchBytes, Charsets.UTF_8))
            require(document.getString("patch_id") == id) { "Patch id mismatch" }
            GitPatchEngine.apply(context, patchBytes)
            GitDataSettings.markPatchApplied(context, id)
        }
        return control
    }

    private fun validateControl(control: JSONObject) {
        require(control.getInt("format_version") == 1) { "Unsupported Git control format" }
        val migrations = control.optJSONArray("migrations") ?: JSONArray()
        for (i in 0 until migrations.length()) {
            val item = migrations.getJSONObject(i)
            require(item.getInt("from") < item.getInt("to")) { "Invalid migration edge" }
            require(item.getString("sha256").matches(Regex("[A-Fa-f0-9]{64}"))) {
                "Invalid migration hash"            }
            require(item.getString("path").startsWith("migrations/")) {
                "Migration must live under migrations/"
            }
        }
        val patches = control.optJSONArray("patches") ?: JSONArray()
        for (i in 0 until patches.length()) {
            val item = patches.getJSONObject(i)
            require(item.getString("path").startsWith("patches/")) {
                "Patch must live under patches/"
            }
            require(item.getString("sha256").matches(Regex("[A-Fa-f0-9]{64}"))) {
                "Invalid patch hash"
            }
        }
    }

    private fun emptyControlManifest(context: Context): JSONObject =
        JSONObject()
            .put("format_version", 1)
            .put("minimum_app_version", 0)
            .put("target_schema_version", PersonalHubDatabase.SCHEMA_VERSION)
            .put("migrations", JSONArray())
            .put("patches", JSONArray())
            .put("created_by", "PersonalHub")
            .put("created_at_ms", System.currentTimeMillis())

    private fun installTracking(context: Context, enqueueAll: Boolean) {
        DatabaseGate.access {
            val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
            GitHistoryStore.install(db)
            GitDataTracking.install(db, enqueueAll)
        }
    }

    private fun needsFullSnapshot(context: Context): Boolean {
        val cached = GitDataSettings.readCachedStateManifest(context) ?: return true
        return cached.optInt("schema_version", -1) != PersonalHubDatabase.SCHEMA_VERSION
    }

    private fun requireConfiguration(context: Context): GitDataConfiguration {
        val config = GitDataSettings.configuration(context)
        require(config.enabled && config.configured) { "Git data sync is disabled or incomplete" }
        return config
    }

    private fun transport(
        context: Context,
        configuration: GitDataConfiguration,
    ): GitHubDataTransport =
        GitHubDataTransport(
            repository = requireNotNull(configuration.repository),
            token = GitDataSettings.token(context),
        )

    private fun scheduleRecovery(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            RECOVERY_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GitDataRecoveryWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    private fun appVersion(context: Context): Long =
        runCatching {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
        }.getOrDefault(0L)
}

class GitDataSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result =
        try {
            GitDataSync.syncNow(applicationContext)
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
}

class GitDataRecoveryWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result =
        try {
            GitDataSync.syncNow(applicationContext)
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
}