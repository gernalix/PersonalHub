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

/**
 * Versioned Git history/backup around the authoritative local personalhub.db.
 *
 * Background sync exports local state and reads only verified control metadata. It never silently
 * hydrates the live database from Git. Whole-database inbound replacement is restricted to the
 * explicit restoreRevision() path; declarative patches also require an explicit apply action.
 */
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
            val (_, head) = transport.remoteHeadOrNull()
            val control = head?.let { pullControl(app, transport, it.commitSha) }
            val bundle = GitDataFormat.exportPending(app)
            if (bundle == null) {
                val revision = if (head == null) {
                    transport.pushFiles(
                        files = mapOf(
                            GIT_CONTROL_MANIFEST to emptyControlManifest(app)
                                .toString(2)
                                .toByteArray(Charsets.UTF_8),
                        ),
                        message = "Initialize PersonalHub data",
                    )
                } else {
                    head.commitSha
                }
                GitDataSettings.markPulled(app, revision)
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
    data class PatchReview(
        val patchId: String, val revision: String, val sha256: String, val author: String,
        val reason: String?, val schemaVersion: Int, val minimumAppVersion: Long,
        val operations: Int, val tables: List<String>, val alreadyApplied: Boolean = false,
    )

    private fun verifiedPatch(context: Context, ref: String, patchId: String): Pair<PatchReview, ByteArray> {
        require(ref.matches(Regex("[A-Za-z0-9._/-]+"))) { "Invalid Git ref" }
        require(patchId.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid patch id" }
        val revision = GitHistory.resolveRevision(context, ref).sha
        return verifiedPatch(context, revision, patchId, transport(context, requireConfiguration(context)))
    }

    /** Same verified loader used by review, preview and apply; transport is injectable for isolated QA. */
    internal fun verifiedPatch(context: Context, revision: String, patchId: String, git: GitHubDataTransport): Pair<PatchReview, ByteArray> {
        val control = JSONObject(String(git.readFile(GIT_CONTROL_MANIFEST, revision), Charsets.UTF_8)).also(::validateControl)
        val patches = control.optJSONArray("patches") ?: JSONArray()
        val matches = (0 until patches.length()).map { patches.getJSONObject(it) }.filter { it.getString("id") == patchId }
        require(matches.size == 1) { "Patch is missing or ambiguous in selected revision" }
        val spec = matches.single()
        val minimum = maxOf(spec.optLong("minimum_app_version",0), control.optLong("minimum_app_version",0))
        require(minimum <= appVersion(context)) { "Patch requires a newer PersonalHub app" }
        val bytes = git.readFile(spec.getString("path"), revision)
        val hash = GitDataFormat.sha256(bytes)
        require(hash == spec.getString("sha256")) { "Patch hash mismatch" }
        val patch = JSONObject(String(bytes, Charsets.UTF_8))
        require(patch.getString("patch_id") == patchId) { "Patch id mismatch" }
        require(patch.getInt("format_version") == 1) { "Unsupported patch format" }
        val operations = patch.getJSONArray("operations")
        val tables = (0 until operations.length()).map { operations.getJSONObject(it).getString("table") }.distinct().sorted()
        return PatchReview(patchId, revision, hash, patch.optString("author").ifBlank { "chatgpt" },
            patch.optString("reason").takeIf(String::isNotBlank), patch.getInt("schema_version"), minimum,
            operations.length(), tables, GitDataTracking.isPatchApplied(PersonalHubDatabase.get(context).openHelper.readableDatabase,patchId)) to bytes
    }

    fun describePatchFromRevision(context: Context, ref: String, patchId: String): PatchReview = operations.withLock {
        verifiedPatch(context.applicationContext,ref,patchId).first
    }

    fun previewPatchFromRevision(context: Context, ref: String, patchId: String): GitPatchPreview = operations.withLock {
        val (review, bytes) = verifiedPatch(context.applicationContext,ref,patchId)
        GitPatchEngine.preview(context.applicationContext,bytes).copy(verifiedRevision=review.revision,sha256=review.sha256)
    }

    fun applyPatchFromRevision(context: Context, ref: String, patchId: String, expectedSha256: String? = null) = operations.withLock {
        val app = context.applicationContext
        val (review, bytes) = verifiedPatch(app,ref,patchId)
        require(expectedSha256 == null || expectedSha256 == review.sha256) { "Patch changed after preview" }
        require(patchId !in GitDataSettings.appliedPatchIds(app)) { "Patch was already applied" }
        GitPatchEngine.apply(app,bytes,review.revision)
        GitDataSettings.markPatchApplied(app,patchId)
        checkForChanges(app)
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

    internal fun pullControl(
        context: Context,
        transport: GitHubDataTransport,
        ref: String,
    ): JSONObject? {
        val bytes = transport.readFileOrNull(GIT_CONTROL_MANIFEST, ref)
        if (bytes == null) {
            GitDataSettings.recordPendingPatches(context, emptyList())
            return null
        }
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
        val pending = mutableListOf<String>()
        val patches = control.optJSONArray("patches") ?: JSONArray()
        for (i in 0 until patches.length()) {
            val spec = patches.getJSONObject(i)
            val id = spec.getString("id")
            if (id in applied || GitDataTracking.isPatchApplied(PersonalHubDatabase.get(context).openHelper.readableDatabase,id)) continue
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
            require(document.getInt("schema_version") == PersonalHubDatabase.SCHEMA_VERSION) {
                "Patch targets a different database schema"
            }
            pending += id
        }
        GitDataSettings.recordPendingPatches(context, pending)
        return control
    }

    private fun validateControl(control: JSONObject) {
        require(control.getInt("format_version") == 1) { "Unsupported Git control format" }
        val patches = control.optJSONArray("patches") ?: JSONArray()
        val ids = mutableSetOf<String>()
        for (i in 0 until patches.length()) {
            val item = patches.getJSONObject(i)
            require(ids.add(item.getString("id"))) { "Duplicate patch id" }
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
