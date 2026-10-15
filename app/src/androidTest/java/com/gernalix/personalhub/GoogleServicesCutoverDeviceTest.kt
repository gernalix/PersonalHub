package com.gernalix.personalhub

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gernalix.luoghi.capsules.addressautocomplete.AddressAutocompleteRepository
import com.gernalix.luoghi.capsules.routedistance.HttpGoogleRoutesClient
import com.gernalix.luoghi.capsules.routedistance.RouteCoordinates
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit operational acceptance only: public synthetic inputs, no database or user counters. */
@RunWith(AndroidJUnit4::class)
class GoogleServicesCutoverDeviceTest {
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        assumeTrue(InstrumentationRegistry.getArguments().getString("googleCutoverAcceptance") == "true")
        check(it.packageName == "com.gernalix.personalhub") { "Canonical application required" }
    }

    private fun verifiedCustody(directory: File) {
        for (kind in listOf("places", "routes")) {
            check(!File(directory, "google-$kind.pending").exists()) { "Import still pending" }
            val state = File(directory, "google-$kind.enc")
            val metadata = Os.lstat(state.path)
            check(metadata.st_uid == android.os.Process.myUid() && metadata.st_mode and 0x1ff == 0x180)
            check(metadata.st_size > 28 && metadata.st_nlink == 1L)
        }
    }

    @Test fun productionConsumersUseProvisionedServices() = runBlocking {
        val context = context()
        val places = AddressAutocompleteRepository(context)
        val routes = HttpGoogleRoutesClient(context)
        assertTrue("Places custody unavailable", places.isConfigured)
        assertTrue("Routes custody unavailable", routes.isConfigured)
        verifiedCustody(context.noBackupFilesDir)
        withTimeout(60_000) {
            val suggestions = runCatching { places.search("Colosseum Rome") }
                .getOrElse { error("Places provider search failed") }
            assertTrue("Places returned no suggestions", suggestions.isNotEmpty())
            val resolved = runCatching { places.resolve(suggestions.first()) }
                .getOrElse { error("Places provider resolution failed") }
            assertTrue("Places resolution unavailable", resolved.latitude != null && resolved.longitude != null)
            val route = routes.bicycleRoute(
                RouteCoordinates(41.8902, 12.4922),
                RouteCoordinates(41.9028, 12.4964),
            )
            assertTrue("Routes returned no route", route != null && route.distanceMeters > 0 && route.durationSeconds > 0)
        }
    }

    /** Run in a second instrumentation process to prove Keystore readback after process restart. */
    @Test fun custodySurvivesProcessRestart() {
        val context = context()
        assertTrue("Places readback unavailable", AddressAutocompleteRepository(context).isConfigured)
        assertTrue("Routes readback unavailable", HttpGoogleRoutesClient(context).isConfigured)
        verifiedCustody(context.noBackupFilesDir)
    }
}
