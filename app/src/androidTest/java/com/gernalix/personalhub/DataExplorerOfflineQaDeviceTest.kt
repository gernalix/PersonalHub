package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gernalix.personalhub.contracts.database.DataExplorerContract
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataExplorerOfflineQaDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @After
    fun cleanup() {
        PersonalHubDatabase.closeInstance()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
    }

    @Test
    fun localExplorerRunsOfflineWithFkAndBacklinkNavigation() {
        check(context.packageName.endsWith(".qa"))
        check(
            android.os.Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
                android.os.Build.FINGERPRINT.contains("generic", ignoreCase = true),
        ) { "Offline Data Explorer QA must run on the emulator" }
        assertOfflineMode()
        seedDatabase()

        val intent = Intent(context, DataExplorerActivity::class.java)
            .putExtra(DataExplorerContract.EXTRA_TABLE, "contact_fields")
        ActivityScenario.launch<DataExplorerActivity>(intent).use { scenario ->
            val open = device.wait(
                Until.findObject(By.text(context.getString(R.string.data_explorer_local))),
                15_000,
            ) ?: error("Missing local Data Explorer button")
            open.click()
            device.waitForIdle()
            val webView = awaitWebView(scenario, 30_000)
            val tableBody = awaitBodyContains(webView, "Ada Example", 120_000)
            assertTrue(tableBody.contains("contact_fields", ignoreCase = true))
            val fkHref = evalString(
                webView,
                """(() => {
                    const links = Array.from(document.querySelectorAll('a'));
                    const target = links.find(a =>
                        (a.textContent || '').includes('Ada Example') &&
                        (a.getAttribute('href') || '').includes('/contacts/')
                    );
                    return target ? target.getAttribute('href') : '';
                })()""",
            )
            assertTrue("Missing labelled contact FK link: $fkHref", fkHref.contains("/contacts/"))
            assertEquals(
                "clicked",
                evalString(
                    webView,
                    """(() => {
                        const target = Array.from(document.querySelectorAll('a')).find(a =>
                            (a.textContent || '').includes('Ada Example') &&
                            (a.getAttribute('href') || '').includes('/contacts/')
                        );
                        if (!target) return 'missing';
                        target.click();
                        return 'clicked';
                    })()""",
                ),
            )
            awaitHashContains(webView, "/personalhub_read/contacts/", 20_000)
            val contactBody = awaitBodyContains(webView, "Ada Example", 20_000)
            assertTrue(contactBody.contains("related", ignoreCase = true))
            val backlinkHref = evalString(
                webView,
                """(() => {
                    const target = Array.from(document.querySelectorAll('a')).find(a =>
                        (a.getAttribute('href') || '').includes('/contact_fields')
                    );
                    return target ? target.getAttribute('href') : '';
                })()""",
            )
            assertTrue("Missing contact_fields backlink: $backlinkHref", backlinkHref.contains("/contact_fields"))
            assertEquals(
                "clicked",
                evalString(
                    webView,
                    """(() => {
                        const target = Array.from(document.querySelectorAll('a')).find(a =>
                            (a.getAttribute('href') || '').includes('/contact_fields')
                        );
                        if (!target) return 'missing';
                        target.click();
                        return 'clicked';
                    })()""",
                ),
            )
            awaitHashContains(webView, "/personalhub_read/contact_fields", 20_000)
            awaitBodyContains(webView, "Ada Example", 20_000)
        }
        assertOfflineMode()
    }

    private fun assertOfflineMode() {
        assertTrue(
            "Validated Internet became available while offline Data Explorer was running",
            !hasValidatedInternet(),
        )
    }

    private fun hasValidatedInternet(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
    }

    private fun seedDatabase() {
        PersonalHubDatabase.closeInstance()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO contacts(id,public_id,created_at,updated_at) VALUES(1,'person-1',1000,1000)",
        )
        db.execSQL(
            "INSERT INTO contact_fields(" +
                "id,contact_id,field_type,value,added_at,position,is_primary" +
                ") VALUES(10,1,'name','Ada Example',1000,0,1)",
        )
    }

    private fun awaitWebView(
        scenario: ActivityScenario<DataExplorerActivity>,
        timeoutMs: Long,
    ): WebView {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val result = AtomicReference<WebView?>()
            scenario.onActivity { activity ->
                result.set(findWebView(activity.window.decorView))
            }
            result.get()?.let { return it }
            Thread.sleep(200)
        }
        error("WebView did not appear")
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
    private fun evalString(webView: WebView, script: String): String {
        val raw = AtomicReference<String>()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                raw.set(value ?: "null")
                latch.countDown()
            }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "Timed out evaluating WebView JavaScript" }
        val value = raw.get()
        if (value == "null") return ""
        return JSONArray("[$value]").getString(0)
    }

    private fun awaitBodyContains(webView: WebView, text: String, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var body = ""
        while (System.currentTimeMillis() < deadline) {
            body = evalString(webView, "document.body ? document.body.innerText : ''")
            if (body.contains("Offline Data Explorer error")) {
                android.util.Log.e("DataExplorerOfflineQA", body.take(4_000))
                throw AssertionError(body)
            }
            if (body.contains(text)) return body
            Thread.sleep(400)
        }
        error("WebView body never contained '$text'. Last body: ${body.take(1000)}")
    }

    private fun awaitHashContains(webView: WebView, text: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var hash = ""
        while (System.currentTimeMillis() < deadline) {
            hash = evalString(webView, "location.hash")
            if (hash.contains(text)) return
            Thread.sleep(200)
        }
        error("Location hash never contained '$text': $hash")
    }
}
