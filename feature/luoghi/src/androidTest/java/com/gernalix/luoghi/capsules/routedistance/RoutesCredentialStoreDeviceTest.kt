package com.gernalix.luoghi.capsules.routedistance

import com.gernalix.luoghi.capsules.googlecredentials.GoogleCredentialKind
import com.gernalix.luoghi.capsules.googlecredentials.GoogleCredentialStore
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoutesCredentialStoreDeviceTest {
    private val alias = "personalhub.test.routes.${UUID.randomUUID()}"
    private val synthetic = "synthetic-routes-device-key"
    private lateinit var directory: File
    private lateinit var context: Context
    private var cleanupAllowed = false

    @Before fun setup() {
        check(Build.HARDWARE in setOf("goldfish", "ranchu")) { "Emulator-only credential fixture" }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName != "com.gernalix.personalhub") { "Isolated test package required" }
        directory = File(target.noBackupFilesDir, "routes-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        cleanupAllowed = true
    }

    @After fun cleanup() {
        if (!cleanupAllowed) return
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        directory.deleteRecursively()
    }

    private fun pending(mode: Int = 0x180): File = File(directory, "google-routes.pending").apply {
        writeText(synthetic)
        Os.chmod(path, mode)
    }

    @Test fun importsAndRestartsFromNativeKeystoreWithoutPlaintext() {
        val source = pending()
        assertEquals(synthetic, RoutesCredentialStore(context, alias).resolve { error("No legacy fallback") })
        assertFalse(source.exists())
        val encrypted = File(directory, "google-routes.enc")
        assertFalse(encrypted.readText().contains(synthetic))
        assertEquals(0x180, Os.stat(encrypted.path).st_mode and 0x1ff)
        assertEquals(synthetic, RoutesCredentialStore(context, alias).resolve { error("No legacy fallback") })
    }

    @Test fun unsafeImportPreservesSourceAndBlocksFallback() {
        val source = pending(0x1a4)
        var fallback = false
        assertThrows(IllegalArgumentException::class.java) {
            RoutesCredentialStore(context, alias).resolve { fallback = true; synthetic }
        }
        assertFalse(fallback)
        assertTrue(source.exists())
        assertFalse(File(directory, "google-routes.enc").exists())
    }

    @Test fun missingKeystoreKeyNeverUsesLegacy() {
        val store = RoutesCredentialStore(context, alias)
        store.save(synthetic)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        var fallback = false
        assertThrows(IllegalStateException::class.java) { store.resolve { fallback = true; synthetic } }
        assertFalse(fallback)
    }

    @Test fun retriesVerifiedImportLeftPendingAfterInterruption() {
        val store = RoutesCredentialStore(context, alias)
        store.save(synthetic)
        val source = pending()
        assertEquals(synthetic, store.resolve { error("No legacy fallback") })
        assertFalse(source.exists())
    }

    @Test fun rejectsSymlinkImportWithoutTouchingItsTarget() {
        val source = File(directory, "source").apply { writeText(synthetic); Os.chmod(path, 0x180) }
        Os.symlink(source.path, File(directory, "google-routes.pending").path)
        assertThrows(IllegalArgumentException::class.java) {
            RoutesCredentialStore(context, alias).resolve { error("No legacy fallback") }
        }
        assertEquals(synthetic, source.readText())
    }

    @Test fun unconfiguredMigrationKeepsExistingConsumer() {
        assertEquals(synthetic, RoutesCredentialStore(context, alias).resolve { synthetic })
        assertFalse(File(directory, "google-routes.enc").exists())
    }

    @Test fun unconfiguredProductionProviderHasNoPackagedCredential() {
        assertEquals("", RoutesCredentialStore(context, alias).resolve())
        assertEquals("", GoogleCredentialStore(context, GoogleCredentialKind.PLACES, alias).resolve())
    }

    @Test fun brokenEncryptedStateNeverFallsBack() {
        Os.symlink(File(directory, "absent").path, File(directory, "google-routes.enc").path)
        var fallback = false
        assertThrows(Exception::class.java) {
            RoutesCredentialStore(context, alias).resolve { fallback = true; synthetic }
        }
        assertFalse(fallback)
    }
    @Test fun placesUsesItsOwnImportAndSurvivesReopen() {
        val placesAlias = alias + ".places"
        try {
            val input = File(directory, "google-places.pending").apply { writeText(synthetic); Os.chmod(path, 0x180) }
            val store = GoogleCredentialStore(context, GoogleCredentialKind.PLACES, placesAlias)
            assertEquals(synthetic, store.resolve { error("No Places fallback") })
            assertFalse(input.exists())
            assertTrue(File(directory, "google-places.enc").isFile)
            assertFalse(File(directory, "google-routes.enc").exists())
            assertEquals(synthetic, GoogleCredentialStore(context, GoogleCredentialKind.PLACES, placesAlias)
                .resolve { error("No Places fallback") })
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(placesAlias) }
        }
    }

    @Test fun serviceKindsDoNotOverwriteEachOtherOrAcceptSwappedCiphertext() {
        val placesAlias = alias + ".places"
        val placesValue = "synthetic-places-device-key"
        try {
            val routes = RoutesCredentialStore(context, alias)
            val places = GoogleCredentialStore(context, GoogleCredentialKind.PLACES, placesAlias)
            routes.save(synthetic)
            places.save(placesValue)
            assertEquals(synthetic, routes.resolve { error("No Routes fallback") })
            assertEquals(placesValue, places.resolve { error("No Places fallback") })
            File(directory, "google-places.enc").writeBytes(File(directory, "google-routes.enc").readBytes())
            var fallback = false
            assertThrows(Exception::class.java) { places.resolve { fallback = true; placesValue } }
            assertFalse(fallback)
            assertEquals(synthetic, routes.resolve { error("Routes must remain intact") })
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(placesAlias) }
        }
    }

    @Test fun existingKeystoreAliasWithoutCiphertextNeverFallsBack() {
        val store = RoutesCredentialStore(context, alias)
        store.save(synthetic)
        check(File(directory, "google-routes.enc").delete())
        var fallback = false
        assertThrows(Exception::class.java) { store.resolve { fallback = true; synthetic } }
        assertFalse(fallback)
    }

}
