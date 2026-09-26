package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.gernalix.personalhub.contracts.database.HubCreateRequest
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubResourceKinds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class WorkflowyIntegrationConfiguration(
    val enabled: Boolean,
    val hasApiKey: Boolean,
    val target: String,
)

data class WorkflowyCreatedNode(val id: String, val deepLink: String)

object WorkflowyIntegrationSettings {
    const val DEFAULT_TARGET = "today"
    private const val PREFS = "workflowy_integration"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGET = "target"
    private const val KEY_HAS_API_KEY = "has_api_key"
    private const val ALIAS = "personalhub.workflowy.api"
    private const val SECRET_FILE = "workflowy-api.enc"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun target(context: Context): String =
        prefs(context).getString(KEY_TARGET, DEFAULT_TARGET).orEmpty().trim().ifBlank { DEFAULT_TARGET }

    fun configuration(context: Context): WorkflowyIntegrationConfiguration =
        WorkflowyIntegrationConfiguration(
            enabled = isEnabled(context),
            hasApiKey = prefs(context).getBoolean(KEY_HAS_API_KEY, false),
            target = target(context),
        )

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun save(context: Context, apiKey: String, target: String) {
        val normalizedTarget = target.trim().ifBlank { DEFAULT_TARGET }
        require(validTarget(normalizedTarget)) { "Invalid Workflowy target" }
        val existing = runCatching { apiKey(context) }.getOrDefault("")
        val normalizedKey = apiKey.trim().ifBlank { existing }
        require(normalizedKey.isNotBlank() && normalizedKey.none { it.isISOControl() }) {
            "Invalid Workflowy API key"
        }
        writeSecret(context, normalizedKey)
        prefs(context).edit().putString(KEY_TARGET, normalizedTarget).putBoolean(KEY_HAS_API_KEY, true).apply()
    }

    fun clearApiKey(context: Context) {
        secretFile(context).baseFile.delete()
        prefs(context).edit().putBoolean(KEY_HAS_API_KEY, false).apply()
    }

    fun observeEnabled(context: Context, onChanged: (Boolean) -> Unit): () -> Unit {
        val preferences = prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED) onChanged(isEnabled(context))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onChanged(isEnabled(context))
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    internal fun apiKey(context: Context): String {
        val file = secretFile(context)
        if (!file.baseFile.exists()) return ""
        val bytes = file.readFully()
        require(bytes.size > 12) { "Invalid Workflowy credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    internal fun validTarget(value: String): Boolean =
        value.isNotBlank() && value.length <= 500 && value.none { it.isISOControl() || it.isWhitespace() }

    private fun writeSecret(context: Context, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val file = secretFile(context)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun secretFile(context: Context) =
        AtomicFile(File(context.applicationContext.noBackupFilesDir, SECRET_FILE))

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
}

object WorkflowyLinkPolicy {
    private val workflowyUrlRegex =
        Regex("""https?://(?:[A-Za-z0-9-]+\.)*workflowy\.com/[^\s<>"]+""", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): String? {
        val normalized = raw.trim().trimEnd('.', ',', ';', ')', ']', '}')
        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "workflowy.com" && !host.endsWith(".workflowy.com")) return null
        return normalized
    }

    fun extractFromSharedText(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        normalize(text)?.let { return it }
        return workflowyUrlRegex.find(text)?.value?.let(::normalize)
    }

    fun deepLink(nodeId: String): String {
        val normalized = nodeId.trim()
        require(normalized.length >= 12) { "Invalid Workflowy node id" }
        return "https://workflowy.com/#/" + normalized.takeLast(12)
    }

    fun isWorkflowyResource(summary: HubEntitySummary): Boolean =
        summary.ref.moduleId == "hub" &&
            summary.ref.entityKind == "resource" &&
            normalize(summary.attributes["value"].orEmpty()) != null
}

object WorkflowyApiClient {
    suspend fun createNode(context: Context, name: String): WorkflowyCreatedNode =
        withContext(Dispatchers.IO) {
            val text = name.trim()
            require(text.isNotBlank()) { "Workflowy note cannot be empty" }
            val config = WorkflowyIntegrationSettings.configuration(context)
            require(config.enabled) { "Workflowy integration is disabled" }
            require(config.hasApiKey) { "Workflowy API key is not configured" }
            val token = WorkflowyIntegrationSettings.apiKey(context)
            val body = JSONObject()
                .put("parent_id", config.target)
                .put("name", text)
                .put("position", "top")
                .toString()
            val connection = URL("https://workflowy.com/api/v1/nodes").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer " + token)
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("Workflowy API returned HTTP " + code)
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val nodeId = parseCreatedNodeId(response)
                WorkflowyCreatedNode(nodeId, WorkflowyLinkPolicy.deepLink(nodeId))
            } finally {
                connection.disconnect()
            }
        }

    suspend fun deleteNode(context: Context, nodeId: String) = withContext(Dispatchers.IO) {
        val id = nodeId.trim()
        require(id.length >= 12) { "Invalid Workflowy node id" }
        val token = WorkflowyIntegrationSettings.apiKey(context)
        val connection = URL("https://workflowy.com/api/v1/nodes/" + id).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "DELETE"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + token)
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Workflowy delete returned HTTP " + code)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseCreatedNodeId(raw: String): String {
        val id = JSONObject(raw).optString("item_id").trim()
        require(id.length >= 12) { "Workflowy API response has no valid item_id" }
        return id
    }


}

object WorkflowyHubBridge {

    suspend fun createAttachAndOpen(context: Context, anchor: HubEntityRef): Boolean {
        require(WorkflowyIntegrationSettings.isEnabled(context)) { "Workflowy integration is disabled" }
        val existing = HubContextRuntime.contexts(anchor)
            .flatMap { it.members }
            .filter { it.ref != anchor && WorkflowyLinkPolicy.isWorkflowyResource(it) }
            .distinctBy { it.attributes["value"] }
        if (existing.size == 1) return open(context, existing.single().attributes["value"].orEmpty())
        if (existing.size > 1) error("More than one Workflowy node is linked to this entity")

        val summary = HubContextRuntime.adapter(anchor.moduleId, anchor.entityKind)
            .summaries(setOf(anchor.canonicalId))[anchor.canonicalId]
            ?: error("PersonalHub entity could not be resolved")
        val created = createNote(context, anchor, summary.label)
        return open(context, created.deepLink)
    }
    suspend fun createNote(context: Context, anchor: HubEntityRef, text: String): WorkflowyCreatedNode {
        val created = WorkflowyApiClient.createNode(context, text)
        try {
            attachUrl(context, anchor, created.deepLink, labelFor(text))
        } catch (error: CancellationException) {
            withContext(NonCancellable) { runCatching { WorkflowyApiClient.deleteNode(context, created.id) } }
            throw error
        } catch (error: Exception) {
            runCatching { WorkflowyApiClient.deleteNode(context, created.id) }
            throw error
        }
        return created
    }

    suspend fun attachUrl(
        context: Context,
        anchor: HubEntityRef,
        rawUrl: String,
        label: String = "Workflowy",
    ): HubEntitySummary {
        require(WorkflowyIntegrationSettings.isEnabled(context)) { "Workflowy integration is disabled" }
        val normalized = WorkflowyLinkPolicy.normalize(rawUrl) ?: error("Invalid Workflowy URL")
        HubContextRuntime.contexts(anchor)
            .flatMap { it.members }
            .firstOrNull { it.ref != anchor && it.attributes["value"] == normalized }
            ?.let { return it }

        val created = createDetachedResource(normalized, label)
        try {
            HubContextRuntime.createContext(
                listOf(anchor to "", created.ref to ""),
                title = "Workflowy",
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) { deleteDetachedResource(created.ref) }
            throw error
        } catch (error: Exception) {
            runCatching { deleteDetachedResource(created.ref) }
            throw error
        }
        return created
    }

    suspend fun createDetachedResource(rawUrl: String, label: String = "Workflowy"): HubEntitySummary {
        val normalized = WorkflowyLinkPolicy.normalize(rawUrl) ?: error("Invalid Workflowy URL")
        val adapter = HubContextRuntime.adapter("hub", "resource")
        return adapter.create(
            HubCreateRequest(
                suggestedLabel = label,
                extras = mapOf("kind" to HubResourceKinds.WEB_URL, "value" to normalized),
            ),
        ) ?: error("Could not create Workflowy resource")
    }

    suspend fun deleteDetachedResource(ref: HubEntityRef) {
        if (ref.moduleId == "hub" && ref.entityKind == "resource") {
            (HubContextRuntime.adapter("hub", "resource") as? ResourceHubAdapter)?.delete(ref.canonicalId)
        }
    }

    fun open(context: Context, rawUrl: String): Boolean {
        val normalized = WorkflowyLinkPolicy.normalize(rawUrl) ?: return false
        val uri = Uri.parse(normalized)
        val explicit = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.workflowy.android")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(explicit); true }.getOrDefault(false)) return true
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    private fun labelFor(text: String): String =
        "Workflowy · " + text.trim().lineSequence().first().take(60)
}
