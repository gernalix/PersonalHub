package com.gernalix.luoghi.capsules.googlecredentials

import android.content.Context
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class GoogleCredentialKind(val fileStem: String, val keyAlias: String) {
    ROUTES("google-routes", "personalhub.luoghi.routes"),
    PLACES("google-places", "personalhub.luoghi.places"),
}

/** Google service custody; encrypted state never belongs to the application database or backups. */
internal class GoogleCredentialStore(
    context: Context,
    kind: GoogleCredentialKind,
    private val keyAlias: String = kind.keyAlias,
) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "${kind.fileStem}.enc"))
    private val pending = File(context.applicationContext.noBackupFilesDir, "${kind.fileStem}.pending")

    fun resolve(legacy: () -> String): String = synchronized(LOCK) {
        importPending()
        val configured = GoogleCredentialPolicy.hasState(file.baseFile) ||
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias)
        GoogleCredentialPolicy.resolve(configured, { read() }, legacy)
    }

    fun save(value: String) = synchronized(LOCK) {
        GoogleCredentialPolicy.validate(value)
        val bytes = GoogleCredentialEnvelope.encrypt(value, encryptionKey(create = true))
        val output = file.startWrite()
        try {
            Os.fchmod(output.fd, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        check(read() == value) { "Google service credential verification failed" }
    }

    private fun read(): String = file.openRead().use { input ->
        val bytes = ByteArray(541)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        GoogleCredentialEnvelope.decrypt(bytes.copyOf(size), encryptionKey(create = false))
    }

    private fun importPending() {
        val present = try {
            Os.lstat(pending.path)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return else throw error
        }
        require(OsConstants.S_ISREG(present.st_mode) && present.st_uid == Process.myUid() &&
            present.st_mode and 0x1ff == (OsConstants.S_IRUSR or OsConstants.S_IWUSR) && present.st_nlink == 1L) {
            "Unsafe Google service credential import"
        }
        val descriptor = Os.open(pending.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        val source = FileInputStream(descriptor)
        source.use {
            val opened = Os.fstat(descriptor)
            check(opened.st_ino == present.st_ino && opened.st_dev == present.st_dev) {
                "Google service credential import changed"
            }
            val bytes = ByteArray(513)
            var size = 0
            while (size < bytes.size) {
                val count = source.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            require(size in 16..512) { "Invalid Google service credential import" }
            val value = bytes.copyOf(size).toString(Charsets.UTF_8)
            save(value)
            check(read() == value) { "Google service credential import verification failed" }
            val current = Os.lstat(pending.path)
            check(current.st_ino == opened.st_ino && current.st_dev == opened.st_dev) {
                "Google service credential import changed"
            }
            Os.remove(pending.path)
        }
    }

    private fun encryptionKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create) { "Google service credential encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        val LOCK = Any()
    }
}

internal object GoogleCredentialPolicy {
    fun hasState(base: File): Boolean =
        listOf(base, File(base.path + ".bak"), File(base.path + ".new")).any {
            try {
                Files.readAttributes(it.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                true
            } catch (_: NoSuchFileException) {
                false
            }
        }

    fun resolve(configured: Boolean, read: () -> String, legacy: () -> String): String =
        if (configured) read().also(::validate) else legacy()

    fun validate(value: String) {
        require(value.length in 16..512 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) {
            "Invalid Google service credential"
        }
    }
}

internal object GoogleCredentialEnvelope {
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(value: String, key: SecretKey): ByteArray {
        GoogleCredentialPolicy.validate(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        check(cipher.iv.size == IV_BYTES) { "Invalid Google service encryption IV" }
        return cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(bytes: ByteArray, key: SecretKey): String {
        require(bytes.size in (IV_BYTES + TAG_BITS / 8 + 16)..(IV_BYTES + TAG_BITS / 8 + 512)) {
            "Invalid encrypted Google service credential"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        }
        return cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
            .also(GoogleCredentialPolicy::validate)
    }
}
