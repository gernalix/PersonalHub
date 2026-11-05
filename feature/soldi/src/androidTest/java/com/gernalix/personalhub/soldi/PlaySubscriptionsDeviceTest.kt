package com.gernalix.personalhub.soldi

import com.gernalix.personalhub.soldi.capsules.playsubscriptions.*
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class PlaySubscriptionsTestActivity : ComponentActivity()

/** Synthetic data in the isolated library-test package only. Never operates on the user's database. */
class PlaySubscriptionsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<PlaySubscriptionsTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun emulatorOnlyAndIsolated() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk") || Build.HARDWARE.contains("ranchu"))
        check(context.packageName == "com.gernalix.personalhub.soldi.test")
    }
    @Test fun sqliteImportIsIdempotentPreservesChangesAndDoesNotPostTransactions() {
        val store = PlaySubscriptionStore(context)
        val source = """[{"doc":{"docid":"synthetic-device-subscription","title":"QA Music"},"state":"ACTIVE","renewalDate":"2027-01-31T12:00:00Z","pricing":{"amount":"9.99","currencyCode":"EUR","period":"P1M"}}]"""
        val rows = PlaySubscriptionJson.parse(source)
        val db = com.gernalix.personalhub.core.database.PersonalHubDatabase.get(context)
        fun count() = db.openHelper.readableDatabase.query("SELECT count(*) FROM finance_transactions").use { it.moveToFirst(); it.getInt(0) }
        val before = count()
        store.import(rows); store.import(rows)
        assertEquals(1, PlaySubscriptionStore(context).read().subscriptions.count { it.key == rows.single().key })
        val cancelled = PlaySubscriptionJson.parse(source.replace("ACTIVE", "CANCELED"))
        store.import(cancelled)
        assertEquals("CANCELED", store.read().subscriptions.single { it.key == rows.single().key }.state)
        val preserved = store.read()
        assertTrue(runCatching { store.import(PlaySubscriptionJson.parse("[{}]")) }.isFailure)
        assertEquals(preserved, store.read())
        assertEquals(before, count())
        store.import(rows)
    }
    @Test fun rendersImportedForecastAndPersistsReminderChoice() {
        val store = PlaySubscriptionStore(context)
        store.import(PlaySubscriptionJson.parse("""[{"doc":{"docid":"synthetic-device-subscription","title":"QA Music"},"state":"ACTIVE","renewalDate":"2027-01-31T12:00:00Z","pricing":{"amount":"9.99","currencyCode":"EUR","period":"P1M"}}]"""))
        store.update { it.copy(reminders = false) }
        compose.setContent { SoldiTheme { PlaySubscriptionsScreen {} } }
        compose.onNodeWithText("Abbonamenti Google Play").assertIsDisplayed()
        compose.onNodeWithText("QA Music").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("9.99 EUR").assertIsDisplayed()
        compose.onNodeWithText("Attivo").assertIsDisplayed()
        compose.onNodeWithText("QA Music").performClick()
        compose.onNodeWithText("Periodicità: 1 mesi").assertIsDisplayed()
        compose.onNodeWithText("Chiudi").performClick()
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.waitUntil(10000) { store.read().reminders }
        compose.waitUntil(10000) { context.getSharedPreferences("soldi_alarm_keys", android.content.Context.MODE_PRIVATE).getStringSet("keys", emptySet()).orEmpty().any { it.startsWith("play:") } }
        compose.onNodeWithText("QA Music").performScrollTo()
        val image = java.io.File(requireNotNull(context.getExternalFilesDir(null)), "google-play-subscriptions.png")
        image.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(image.length() > 0)
        assertEquals(Instant.parse("2027-01-31T12:00:00Z"), store.read().subscriptions.single().renewal)
    }
    @Test fun resumableArchiveSyncIsThrottledAndOneTimeStopsAutomaticExports() = runBlocking {
        val store = PlaySubscriptionStore(context)
        val saved = store.read()
        var initiated = 0
        var polled = 0
        val fixedNow = Instant.parse("2026-11-05T12:00:00Z")
        val fake = object : PlayArchiveTransport {
            override fun request(path: String, token: String, body: String?): JSONObject {
                return if (body != null) {
                    assertEquals("portabilityArchive:initiate", path)
                    assertEquals("play.subscriptions", JSONObject(body).getJSONArray("resources").getString(0))
                    initiated++
                    JSONObject("""{"archiveJobId":"synthetic-job","accessType":"ACCESS_TYPE_ONE_TIME"}""")
                } else {
                    assertEquals("archiveJobs/synthetic-job/portabilityArchiveState", path)
                    polled++
                    if (polled == 1) JSONObject("""{"state":"IN_PROGRESS"}""")
                    else JSONObject("""{"state":"COMPLETE","urls":["synthetic-archive"]}""")
                }
            }
            override fun download(rawUrl: String): List<PlaySubscription> {
                assertEquals("synthetic-archive", rawUrl)
                return saved.subscriptions
            }
        }
        try {
            store.update { it.copy(job = null, initiatedAt = null, automatic = true) }
            val client = PlayPortabilityClient(context, fake) { fixedNow }
            assertEquals("pending", client.sync("synthetic-token").status)
            assertEquals("synthetic-job", store.read().job)
            val complete = client.sync("synthetic-token")
            assertEquals("complete", complete.status)
            assertFalse(complete.automatic)
            assertNull(complete.job)
            client.sync("synthetic-token")
            assertEquals(1, initiated); assertEquals(2, polled)
            assertEquals(saved.subscriptions, complete.subscriptions)
        } finally { store.update { saved } }
    }
    @Test fun missingFullSnapshotPreservesHistoryButExcludesUnconfirmedForecasts() {
        val store = PlaySubscriptionStore(context)
        val saved = store.read()
        try {
            store.import(emptyList(), fullSnapshot = true)
            assertEquals(saved.subscriptions, store.read().subscriptions)
            assertTrue(store.read().forecastSubscriptions.isEmpty())
            store.import(saved.subscriptions)
            assertEquals(saved.subscriptions, store.read().forecastSubscriptions)
        } finally { store.update { saved } }
    }
    @Test fun idlessSnapshotsAreIdempotentAndSupersededVersionsDoNotDoubleForecast() {
        val store = PlaySubscriptionStore(context)
        val saved = store.read()
        val source = """[{"subscription":{"doc":{"documentType":"Subscription","title":"Synthetic snapshot"},"state":"Active","renewalDate":"2027-01-31T12:00:00Z","pricing":[{"price":"DKK 10.00","period":{"unit":"MONTH","count":1},"repeatedPricing":true}]}}]"""
        val first = PlaySubscriptionJson.parse(source)
        val updated = PlaySubscriptionJson.parse(source.replace("10.00", "12.00"))
        try {
            store.import(first); store.import(first)
            assertEquals(1, store.read().subscriptions.count { it.key == first.single().key })
            store.import(updated)
            val current = PlaySubscriptionStore(context).read()
            assertTrue(first.single().key in current.unconfirmedKeys)
            assertFalse(updated.single().key in current.unconfirmedKeys)
            assertEquals(1, current.forecastSubscriptions.count { it.title == "Synthetic snapshot" })
            assertEquals(2, current.subscriptions.count { it.title == "Synthetic snapshot" })
        } finally { store.update { saved } }
    }

    @Test fun capsuleRetainsWorkerIdentityAndRunsDisabledCatalogWithoutAuthorization() = runBlocking {
        val store = PlaySubscriptionStore(context)
        val saved = store.read()
        try {
            store.update { it.copy(automatic = false, job = null, reminders = false) }
            assertTrue(PlaySubscriptionsCapsule.reminders(context).isEmpty())
            assertEquals(androidx.work.ListenableWorker.Result.success(), PlaySubscriptionsCapsule.runWorker(context, 0))
            assertEquals("com.gernalix.personalhub.soldi.PlaySubscriptionWorker", PlaySubscriptionWorker::class.java.name)
        } finally { store.update { saved } }
    }

}
