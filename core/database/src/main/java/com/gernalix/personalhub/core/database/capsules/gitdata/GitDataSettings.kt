package com.gernalix.personalhub.core.database.capsules.gitdata

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GitRepository(val owner: String, val name: String) {
    companion object {
        fun parse(value: String): GitRepository? = runCatching {
            val uri = URI(value.trim())
            require(uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true))
            require(uri.userInfo == null && uri.query == null && uri.fragment == null)
            val parts = uri.path.trim('/').split('/').filter { it.isNotBlank() }
            require(parts.size == 2)
            val repo = parts[1].removeSuffix(".git")
            require(parts[0].matches(Regex("[A-Za-z0-9_.-]+")))
            require(repo.matches(Regex("[A-Za-z0-9_.-]+")))
            GitRepository(parts[0], repo)
        }.getOrNull()
    }
}

data class GitDataConfiguration(
    val repositoryUrl: String,
    val enabled: Boolean,
    val hasToken: Boolean,
) {
    val repository: GitRepository? get() = GitRepository.parse(repositoryUrl)
    val configured: Boolean get() = repository != null && hasToken
}

data class GitDataStatus(
    val enabled: Boolean,
    val configured: Boolean,
    val repositoryUrl: String,
    val runtimeState: String,
    val lastPushAt: Long,
    val lastPullAt: Long,
    val lastPushedGeneration: Long,
    val lastRemoteRevision: String?,
    val lastError: String?,
    val pendingPatchIds: List<String>,
)

object GitDataSettings {
    private const val ALIAS = "personalhub.gitdata"
    private const val STATUS_PREFS = "personalhub_git_data_status"
    private const val STATE_MANIFEST = "git-data-state-manifest.json"

    private fun encryptedFile(context: Context) =
        AtomicFile(File(context.noBackupFilesDir, "git-data.enc"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun read(context: Context): JSONObject {
        val file = encryptedFile(context)
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.readFully()
        require(bytes.size > 12) { "Invalid Git data settings" }
        return JSONObject(
            String(
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        key(),
                        GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                    )
                }.doFinal(bytes.copyOfRange(12, bytes.size)),
                Charsets.UTF_8,
            ),
        )
    }

    private fun write(context: Context, value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val file = encryptedFile(context)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun statusPrefs(context: Context) =
        context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun configuration(context: Context): GitDataConfiguration {
        val value = read(context)
        return GitDataConfiguration(
            repositoryUrl = value.optString("repository_url"),
            enabled = value.optBoolean("enabled"),
            hasToken = value.optString("token").isNotBlank(),
        )
    }

    @Synchronized
    fun save(context: Context, repositoryUrl: String, newToken: String) {
        val normalized = repositoryUrl.trim().trimEnd('/')
        require(GitRepository.parse(normalized) != null) {
            "Use an HTTPS GitHub repository URL"
        }
        val value = read(context)
        val token = newToken.ifBlank { value.optString("token") }
        require(token.isNotBlank() && token.none { it.isWhitespace() || it.code < 32 }) {
            "A GitHub access token is required for push"
        }
        value.put("repository_url", normalized).put("token", token)
        write(context, value)
    }

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        val value = read(context)
        if (enabled) {
            val configuration = configuration(context)
            require(configuration.configured) { "Configure the Git repository first" }
        }
        value.put("enabled", enabled)
        write(context, value)
    }

    @Synchronized
    internal fun token(context: Context): String = read(context).optString("token")

    internal fun stateManifestFile(context: Context) =
        File(context.noBackupFilesDir, STATE_MANIFEST)

    internal fun readCachedStateManifest(context: Context): JSONObject? =
        stateManifestFile(context).takeIf { it.isFile }?.let {
            runCatching { JSONObject(it.readText()) }.getOrNull()
        }

    internal fun writeCachedStateManifest(context: Context, manifest: JSONObject) {
        val target = stateManifestFile(context)
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun status(context: Context): GitDataStatus {
        val config = runCatching { configuration(context) }.getOrElse {
            GitDataConfiguration("", false, false)
        }
        val prefs = statusPrefs(context)
        return GitDataStatus(
            enabled = config.enabled,
            configured = config.configured,
            repositoryUrl = config.repositoryUrl,
            runtimeState = prefs.getString("runtime_state", "idle") ?: "idle",
            lastPushAt = prefs.getLong("last_push_at", 0L),
            lastPullAt = prefs.getLong("last_pull_at", 0L),
            lastPushedGeneration = prefs.getLong("last_pushed_generation", -1L),
            lastRemoteRevision = prefs.getString("last_remote_revision", null),
            lastError = prefs.getString("last_error", null),
            pendingPatchIds = prefs.getStringSet("pending_patch_ids", emptySet())
                .orEmpty()
                .sorted(),
        )
    }

    internal fun setRuntimeState(context: Context, state: String) {
        statusPrefs(context).edit().putString("runtime_state", state).apply()
    }

    internal fun recordError(context: Context, error: Throwable) {
        statusPrefs(context).edit()
            .putString("runtime_state", "retry")
            .putString("last_error", error.message ?: error.javaClass.simpleName)
            .apply()
    }

    internal fun recordAttention(context: Context, message: String) {
        statusPrefs(context).edit()
            .putString("runtime_state", "attention")
            .putString("last_error", message)
            .apply()
    }

    internal fun markPushed(context: Context, generation: Long, revision: String) {
        statusPrefs(context).edit()
            .putString("runtime_state", "complete")
            .putLong("last_push_at", System.currentTimeMillis())
            .putLong("last_pushed_generation", generation)
            .putString("last_remote_revision", revision)
            .remove("last_error")
            .apply()
    }

    internal fun markPulled(context: Context, revision: String) {
        statusPrefs(context).edit()
            .putLong("last_pull_at", System.currentTimeMillis())
            .putString("last_remote_revision", revision)
            .remove("last_error")
            .apply()
    }

    internal fun recordPendingPatches(context: Context, ids: Collection<String>) {
        statusPrefs(context).edit()
            .putStringSet("pending_patch_ids", ids.toSortedSet())
            .apply()
    }

    @Synchronized
    internal fun appliedPatchIds(context: Context): Set<String> {
        val array = read(context).optJSONArray("applied_patches") ?: return emptySet()
        return buildSet {
            for (i in 0 until array.length()) add(array.getString(i))
        }
    }

    @Synchronized
    internal fun replaceAppliedPatchIds(context: Context, ids: Collection<String>) {
        val value = read(context)
        val array = JSONArray()
        ids.toSortedSet().forEach(array::put)
        value.put("applied_patches", array)
        write(context, value)
    }

    @Synchronized
    internal fun markPatchApplied(context: Context, patchId: String) {
        val value = read(context)
        val existing = appliedPatchIds(context).toMutableSet()
        existing += patchId
        val array = JSONArray()
        existing.sorted().forEach(array::put)
        value.put("applied_patches", array)
        write(context, value)
        val pending = statusPrefs(context)
            .getStringSet("pending_patch_ids", emptySet())
            .orEmpty()
            .toMutableSet()
        pending.remove(patchId)
        statusPrefs(context).edit().putStringSet("pending_patch_ids", pending).apply()
    }
}
