package com.gernalix.personalhub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityLauncherTest {
    @Test
    fun launcherActivityIsConfiguredToReturnToTheRootHome() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, activityInfo.launchMode)
        assertTrue(activityInfo.flags and ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH != 0)
    }

    @Test
    fun onlyMainLauncherIntentsTriggerTheHomeReset() {
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        assertTrue(launcherIntent.isPersonalHubLauncherIntent())
        assertFalse(Intent(Intent.ACTION_VIEW).isPersonalHubLauncherIntent())
        assertFalse(Intent(Intent.ACTION_MAIN).isPersonalHubLauncherIntent())
    }
}
