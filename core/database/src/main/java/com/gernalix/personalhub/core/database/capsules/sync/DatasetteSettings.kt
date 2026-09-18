package com.gernalix.personalhub.core.database.capsules.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DatasetteConfiguration(val baseUrl: String, val database: String, val table: String, val enabled: Boolean, val hasToken: Boolean)

data class DatasetteExplorerConfiguration(val baseUrl: String, val database: String) {
    val configured: Boolean get() = baseUrl.isNotBlank() && database.isNotBlank()
}

/** All connection values are runtime-only, AES-GCM encrypted with a non-exportable Android key. */
object DatasetteSettings {
    const val DEFAULT_EXPLORER_DATABASE = "personalhub_read"
    private const val ALIAS = "personalhub.datasette"
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "datasette.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(context: Context): JSONObject {
        val file = file(context)
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.readFully()
        return JSONObject(String(Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    private fun write(context: Context, value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val file = file(context)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun configuration(context: Context): DatasetteConfiguration {
        val value = read(context)
        return DatasetteConfiguration(value.optString("url"), value.optString("database"), value.optString("table"), value.optBoolean("enabled"), value.optString("token").isNotBlank())
    }
    fun validBaseUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
    }.getOrDefault(false)
    private fun validName(value: String) = value.matches(Regex("[A-Za-z0-9_-]+"))
    @Synchronized fun save(context: Context, url: String, database: String, table: String, newToken: String) {
        val base = url.trim().trimEnd('/')
        require(validBaseUrl(base) && validName(database) && validName(table)) { "Invalid connection settings" }
        val value = read(context)
        val token = newToken.ifBlank { value.optString("token") }
        require(token.isNotBlank() && token.none { it.isWhitespace() || it.code < 32 }) { "Invalid credential" }
        value.put("url", base).put("database", database).put("table", table).put("token", token).put("full", true)
        write(context, value)
    }
    @Synchronized fun explorerDatabase(context: Context): String =
        read(context).optString("explorer_database").trim().ifBlank { DEFAULT_EXPLORER_DATABASE }

    @Synchronized fun explorerConfiguration(context: Context): DatasetteExplorerConfiguration {
        val value = read(context)
        val base = value.optString("url").trim().trimEnd('/')
        val database = value.optString("explorer_database").trim().ifBlank { DEFAULT_EXPLORER_DATABASE }
        return if (validBaseUrl(base) && validName(database)) {
            DatasetteExplorerConfiguration(base, database)
        } else {
            DatasetteExplorerConfiguration("", database)
        }
    }

    @Synchronized fun explorerUrl(context: Context, table: String? = null): String? {
        val normalizedTable = table?.trim()?.takeIf { it.isNotEmpty() }
        require(normalizedTable == null || validName(normalizedTable)) { "Invalid explorer table" }
        return explorerConfiguration(context).takeIf { it.configured }?.let { configuration ->
            buildString {
                append(configuration.baseUrl).append('/').append(configuration.database).append('/')
                normalizedTable?.let(::append)
            }
        }
    }

    @Synchronized fun saveExplorerDatabase(context: Context, database: String) {
        val normalized = database.trim().ifBlank { DEFAULT_EXPLORER_DATABASE }
        require(validName(normalized)) { "Invalid explorer database" }
        write(context, read(context).put("explorer_database", normalized))
    }

    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        val value = read(context)
        if (enabled) require(configuration(context).let { validBaseUrl(it.baseUrl) && validName(it.database) && validName(it.table) && it.hasToken })
        value.put("enabled", enabled)
        if (enabled) value.put("full", true)
        write(context, value)
    }
    @Synchronized internal fun token(context: Context) = read(context).optString("token")
    @Synchronized internal fun deviceId(context: Context): String {
        val value = read(context)
        if (!value.has("device")) { value.put("device", UUID.randomUUID().toString()); write(context, value) }
        return value.getString("device")
    }
    @Synchronized internal fun nextTimestamp(context: Context): Long {
        val value = read(context)
        val next = maxOf(System.currentTimeMillis(), value.optLong("clock") + 1)
        write(context, value.put("clock", next))
        return next
    }
    @Synchronized internal fun needsFull(context: Context) = read(context).optBoolean("full")
    @Synchronized internal fun fullQueued(context: Context) = write(context, read(context).put("full", false))
    @Synchronized fun requireFull(context: Context) = write(context, read(context).put("full", true))
}
