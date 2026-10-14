package com.gernalix.luoghi.capsules.routedistance

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RoutesCredentialStoreTest {
    private val synthetic = "synthetic-routes-key-for-test"
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTripUsesRandomizedAuthenticatedEncryption() {
        val key = key()
        val first = RoutesCredentialEnvelope.encrypt(synthetic, key)
        val second = RoutesCredentialEnvelope.encrypt(synthetic, key)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.UTF_8).contains(synthetic))
        assertEquals(synthetic, RoutesCredentialEnvelope.decrypt(first, key))
    }

    @Test fun rejectsTamperedCiphertextAndWrongKey() {
        val key = key()
        val bytes = RoutesCredentialEnvelope.encrypt(synthetic, key)
        val modified = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { RoutesCredentialEnvelope.decrypt(modified, key) }
        assertThrows(AEADBadTagException::class.java) { RoutesCredentialEnvelope.decrypt(bytes, key()) }
    }

    @Test fun brokenConfiguredProviderNeverCallsLegacy() {
        var legacyCalls = 0
        assertThrows(IllegalStateException::class.java) {
            RoutesCredentialPolicy.resolve(true, { error("Unavailable keystore") }, { legacyCalls++; synthetic })
        }
        assertEquals(0, legacyCalls)
        assertThrows(IllegalArgumentException::class.java) {
            RoutesCredentialPolicy.resolve(true, { "invalid" }, { legacyCalls++; synthetic })
        }
        assertEquals(0, legacyCalls)
    }

    @Test fun unconfiguredMigrationMayUseExistingConsumer() {
        assertEquals(synthetic, RoutesCredentialPolicy.resolve(false, { error("Must not read") }, { synthetic }))
        assertEquals(synthetic, RoutesCredentialPolicy.resolve(true, { synthetic }, { error("Must not fall back") }))
    }

    @Test fun rejectsInvalidValuesWithoutIncludingThemInErrors() {
        val invalid = "private-secret-with-newline\n"
        val error = assertThrows(IllegalArgumentException::class.java) { RoutesCredentialPolicy.validate(invalid) }
        assertFalse(error.message.orEmpty().contains(invalid))
        assertThrows(IllegalArgumentException::class.java) { RoutesCredentialEnvelope.decrypt(byteArrayOf(), key()) }
    }

    @Test fun interruptedAtomicWriteStillCountsAsConfigured() {
        val directory = Files.createTempDirectory("routes-credential-test").toFile()
        try {
            val base = directory.resolve("routes.enc")
            assertFalse(RoutesCredentialPolicy.hasState(base))
            for (suffix in listOf("", ".bak", ".new")) {
                val state = directory.resolve("routes.enc$suffix")
                state.writeBytes(byteArrayOf(1))
                assertTrue(RoutesCredentialPolicy.hasState(base))
                state.delete()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
