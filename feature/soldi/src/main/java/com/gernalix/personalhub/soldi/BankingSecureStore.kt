package com.gernalix.personalhub.soldi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class BankingConfiguration(val baseUrl: String, val configured: Boolean)

internal class BankingSecureStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val credentialFile = AtomicFile(File(app.noBackupFilesDir, "soldi-banking-credential.enc"))
    private val cacheFile = AtomicFile(File(app.noBackupFilesDir, "soldi-banking-cache.enc"))

    fun configuration(): BankingConfiguration = BankingConfiguration(
        baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL },
        configured = credentialFile.baseFile.exists(),
    )

    fun saveConfiguration(baseUrl: String, token: String) = synchronized(LOCK) {
        val url = normalizeBaseUrl(baseUrl)
        validateToken(token)
        write(credentialFile, encrypt(token, CREDENTIAL_ALIAS))
        check(readCredential() == token) { "Bank credential verification failed" }
        prefs.edit().putString(KEY_BASE_URL, url).apply()
    }

    fun readCredential(): String {
        check(credentialFile.baseFile.exists()) { "Banking credential is not configured" }
        return decrypt(credentialFile.readFully(), CREDENTIAL_ALIAS).also(::validateToken)
    }

    fun saveCache(snapshot: BankingSnapshot) = synchronized(LOCK) {
        write(cacheFile, encrypt(BankingJson.encode(snapshot), CACHE_ALIAS))
    }

    fun readCache(): BankingSnapshot? = synchronized(LOCK) {
        if (!cacheFile.baseFile.exists()) null else BankingJson.decode(decrypt(cacheFile.readFully(), CACHE_ALIAS))
    }

    fun disconnect() = synchronized(LOCK) {
        credentialFile.delete(); cacheFile.delete(); prefs.edit().remove(KEY_BASE_URL).apply()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run {
            deleteEntry(CREDENTIAL_ALIAS); deleteEntry(CACHE_ALIAS)
        }
    }

    private fun encrypt(value: String, alias: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(alias, create = true)) }
        return cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(bytes: ByteArray, alias: String): String {
        require(bytes.size > IV_BYTES + 16) { "Invalid protected banking state" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(alias, create = false), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        }
        return cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun key(alias: String, create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Banking encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://mtt-sync.danielegalati.com/personalhub-banking/api/v1"
        private const val PREFS = "soldi_banking"
        private const val KEY_BASE_URL = "base_url"
        private const val CREDENTIAL_ALIAS = "personalhub.soldi.banking.credential"
        private const val CACHE_ALIAS = "personalhub.soldi.banking.cache"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private val LOCK = Any()

        fun normalizeBaseUrl(raw: String): String {
            val value = raw.trim().trimEnd('/')
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Banking URL must use HTTPS"
            }
            require(uri.host == "mtt-sync.danielegalati.com" && uri.path == "/personalhub-banking/api/v1") {
                "Banking URL must point to the PersonalHub read-only API"
            }
            return value
        }

        fun validateToken(value: String) {
            require(value.length in 16..512 && value.none { it.isWhitespace() || it.isISOControl() }) { "Invalid banking credential" }
        }
    }
}
