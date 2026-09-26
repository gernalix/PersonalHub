package com.gernalix.personalhub

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gernalix.personalhub.core.hubcontext.WorkflowyApiClient
import com.gernalix.personalhub.core.hubcontext.WorkflowyIntegrationSettings
import com.gernalix.personalhub.core.hubcontext.WorkflowyLinkPolicy
import com.gernalix.personalhub.core.hubcontext.WorkflowyHubBridge
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class WorkflowyLiveApiProbeTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun createUnderTodayAndDeleteFromDeepLink() {
        val config = WorkflowyIntegrationSettings.configuration(context)
        assertTrue(config.enabled)
        assertTrue(config.hasApiKey)
        assertEquals("today", config.target)

        val created = runBlocking {
            WorkflowyApiClient.createNode(context, "PH live API probe ${System.currentTimeMillis()}")
        }
        val token = apiKey()
        try {
            val createdGet = request("GET", "https://workflowy.com/api/v1/nodes/${created.id}", token)
            assertEquals(200, createdGet.first)
            val todayGet = request("GET", "https://workflowy.com/api/v1/nodes/today", token)
            assertEquals(200, todayGet.first)
            val createdParent = JSONObject(createdGet.second).getJSONObject("node").getString("parent_id")
            val todayId = JSONObject(todayGet.second).getJSONObject("node").getString("id")
            assertEquals(todayId, createdParent)

            val shortId = created.deepLink.substringAfterLast('/')
            runBlocking { WorkflowyApiClient.deleteNode(context, shortId) }
            assertEquals(404, request("GET", "https://workflowy.com/api/v1/nodes/$shortId", token).first)
            File(context.filesDir, "workflowy-live-delete.txt")
                .writeText("PASS\n${created.deepLink}\n")
        } finally {
            runCatching { runBlocking { WorkflowyApiClient.deleteNode(context, created.id) } }
        }
    }

    @Test
    fun createTrulyEmptyNodeProbe() {
        val token = apiKey()
        val body = JSONObject()
            .put("parent_id", "today")
            .put("name", "")
            .put("position", "top")
            .toString()
        val result = request("POST", "https://workflowy.com/api/v1/nodes", token, body)
        val out = File(context.filesDir, "workflowy-live-empty.txt")
        if (result.first in 200..299) {
            val id = JSONObject(result.second).getString("item_id")
            val link = WorkflowyLinkPolicy.deepLink(id)
            out.writeText("ACCEPTED\n$id\n$link\n")
        } else {
            out.writeText("REJECTED\n${result.first}\n${result.second.take(500)}\n")
        }
    }

    @Test
    fun focusEmptyNodeProbe() {
        val lines = File(context.filesDir, "workflowy-live-empty.txt").readLines()
        assertEquals("ACCEPTED", lines.first())
        assertTrue(WorkflowyHubBridge.open(context, lines[2]))
        Thread.sleep(1500)
    }

    @Test
    fun resolveDeepLinkAndDeleteEmptyProbe() {
        val lines = File(context.filesDir, "workflowy-live-empty.txt").readLines()
        val shortId = lines[2].substringAfterLast('/')
        val resolved = request("GET", "https://workflowy.com/api/v1/nodes/$shortId", apiKey())
        assertEquals(200, resolved.first)
        val fullId = JSONObject(resolved.second).getJSONObject("node").getString("id")
        runBlocking { WorkflowyApiClient.deleteNode(context, fullId) }
        assertEquals(404, request("GET", "https://workflowy.com/api/v1/nodes/$shortId", apiKey()).first)
        File(context.filesDir, "workflowy-live-empty.txt").delete()
    }

    private fun apiKey(): String {
        val method = WorkflowyIntegrationSettings::class.java.declaredMethods
            .first { it.name.startsWith("apiKey") && it.parameterTypes.size == 1 }
        method.isAccessible = true
        return method.invoke(WorkflowyIntegrationSettings, context) as String
    }

    private fun request(
        method: String,
        url: String,
        token: String,
        body: String? = null,
    ): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}
