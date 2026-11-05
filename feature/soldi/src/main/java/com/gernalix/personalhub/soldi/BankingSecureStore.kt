package com.gernalix.personalhub.soldi

import com.gernalix.personalhub.core.database.DatabaseGate
import com.gernalix.personalhub.core.database.DatabasePreferences
import androidx.core.content.edit
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class BankingConfiguration(val baseUrl: String, val configured: Boolean)

internal class BankingSecureStore(context: Context) {
    private val app = context.applicationContext
    private val databasePrefs = DatabasePreferences(app, "soldi.banking.v1")
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val credentialFile = AtomicFile(File(app.noBackupFilesDir, "soldi-banking-credential.enc"))
    private val cacheFile = AtomicFile(File(app.noBackupFilesDir, "soldi-banking-cache.enc"))
    private val reconciliationFile = AtomicFile(File(app.noBackupFilesDir, "soldi-banking-reconciliation.enc"))

    fun configuration(): BankingConfiguration = BankingConfiguration(
        baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL },
        configured = credentialFile.baseFile.exists(),
    )

    fun saveConfiguration(baseUrl: String, token: String) = synchronized(LOCK) {
        val url = normalizeBaseUrl(baseUrl)
        validateToken(token)
        write(credentialFile, encrypt(token, CREDENTIAL_ALIAS))
        check(readCredential() == token) { "Bank credential verification failed" }
        prefs.edit { putString(KEY_BASE_URL, url)}
    }

    fun readCredential(): String {
        check(credentialFile.baseFile.exists()) { "Banking credential is not configured" }
        return decrypt(credentialFile.readFully(), CREDENTIAL_ALIAS).also(::validateToken)
    }

    fun saveCache(snapshot: BankingSnapshot) = DatabaseGate.access {
        check(databasePrefs.edit().putString("cache", BankingJson.encode(snapshot)).commit())
    }

    fun readCache(): BankingSnapshot? = DatabaseGate.access {
        val saved = databasePrefs.getString("cache", null)
        if (saved != null) return@access BankingJson.decode(saved)
        if (!cacheFile.baseFile.exists()) return@access null
        val legacy = BankingJson.decode(decrypt(cacheFile.readFully(), CACHE_ALIAS))
        saveCache(legacy)
        check(BankingJson.encode(readCache()!!) == BankingJson.encode(legacy))
        cacheFile.delete()
        legacy
    }

    fun reconciliationState(): BankingReconciliationState = DatabaseGate.access {
        val saved = databasePrefs.getString("reconciliation", null)
        if (saved != null) return@access decodeReconciliation(saved)
        if (!reconciliationFile.baseFile.exists()) return@access BankingReconciliationState()
        val legacy = decodeReconciliation(decrypt(reconciliationFile.readFully(), RECONCILIATION_ALIAS))
        check(databasePrefs.edit().putString("reconciliation", encodeReconciliation(legacy)).commit())
        check(reconciliationState() == legacy)
        reconciliationFile.delete()
        legacy
    }

    fun updateReconciliationState(
        transform: (BankingReconciliationState) -> BankingReconciliationState,
    ): BankingReconciliationState = DatabaseGate.access {
        val next = transform(reconciliationState())
        require(next.matchedTransactions.values.distinct().size == next.matchedTransactions.size)
        check(databasePrefs.edit().putString("reconciliation", encodeReconciliation(next)).commit())
        next
    }

    fun disconnect() = DatabaseGate.access {
        synchronized(LOCK) {
            credentialFile.delete(); cacheFile.delete(); reconciliationFile.delete(); prefs.edit { remove(KEY_BASE_URL)}
            check(databasePrefs.edit().clear().commit())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run {
                deleteEntry(CREDENTIAL_ALIAS); deleteEntry(CACHE_ALIAS); deleteEntry(RECONCILIATION_ALIAS)
            }
        }
    }

    private fun encodeReconciliation(value: BankingReconciliationState): String = JSONObject().apply {
        put("account_links", JSONObject(value.accountLinks))
        put("alerts", JSONObject().apply {
            put("enabled", value.alertPreferences.enabled)
            put("threshold", value.alertPreferences.threshold.toPlainString())
        })
        put("accepted_suggestions", JSONObject(value.acceptedSuggestions))
        put("matched_transactions", JSONObject(value.matchedTransactions))
        put("observed_alerts", JSONObject(value.observedAlerts))
        put("notified_alerts", JSONObject(value.notifiedAlerts))
    }.toString()

    private fun decodeReconciliation(body: String): BankingReconciliationState {
        val root = JSONObject(body)
        fun stringMap(key: String): Map<String, String> = root.optJSONObject(key)?.let { value ->
            value.keys().asSequence().associateWith { name -> value.getString(name) }
        }.orEmpty()
        val alerts = root.optJSONObject("alerts")
        return BankingReconciliationState(
            accountLinks = stringMap("account_links"),
            alertPreferences = BankingAlertPreferences(
                enabled = alerts?.optBoolean("enabled", false) ?: false,
                threshold = alerts?.optString("threshold")?.takeIf(String::isNotBlank)?.let(::BigDecimal)
                    ?: BigDecimal.ZERO,
            ),
            acceptedSuggestions = stringMap("accepted_suggestions"),
            matchedTransactions = stringMap("matched_transactions"),
            observedAlerts = stringMap("observed_alerts"),
            notifiedAlerts = stringMap("notified_alerts"),
        )
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
        private const val RECONCILIATION_ALIAS = "personalhub.soldi.banking.reconciliation"
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
