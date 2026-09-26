package com.gernalix.personalhub

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gernalix.personalhub.core.hubcontext.WorkflowyApiClient
import com.gernalix.personalhub.core.hubcontext.WorkflowyIntegrationSettings
import com.gernalix.personalhub.core.hubcontext.WorkflowyLinkPolicy
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
