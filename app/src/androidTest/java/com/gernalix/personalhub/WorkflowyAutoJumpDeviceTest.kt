package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.hubcontext.WorkflowyHubBridge
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkflowyAutoJumpDeviceTest {
    @Test
    fun workflowyDeepLinkUsesRealHandlerOnPhysicalDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(!android.os.Build.FINGERPRINT.startsWith("generic") && !android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        assertTrue(WorkflowyHubBridge.open(context, "https://workflowy.com/#/59d823cea257"))
    }
}
