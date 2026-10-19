package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gernalix.luoghi.capsules.mapviewer.MapViewerCapsule
import com.gernalix.luoghi.capsules.mapviewer.OsmAndReference
import com.gernalix.luoghi.data.PlaceRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Shared real external-map scenario; concrete runners retain distinct device guards. */
abstract class OsmAndExploreQaScenario {
    protected abstract fun verifyTarget(context: Context)
    @Test fun externalMapShowsTemporaryReferenceAndReturnsToPersonalHub() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        verifyTarget(context)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val repository = PlaceRepository(context)
        val ids = mutableListOf<String>()
        try {
            ids += repository.savePlace(null, "PH QA East", null, 55.6761, 12.5733, 75.0, null, "Temporary QA")
            ids += repository.savePlace(null, "PH QA West", null, 55.6761, 12.5633, 75.0, null, "Temporary QA")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
            val personalHubTask = context.getSystemService(android.app.ActivityManager::class.java).appTasks
                .first { it.taskInfo?.baseActivity?.packageName == context.packageName }
            context.startActivity(MapViewerCapsule.exploreMapIntent(context,
                OsmAndReference(55.6761, 12.5683, "PH QA Reference"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("The real OsmAnd map did not open", device.wait(Until.hasObject(By.pkg("net.osmand")), 25_000))
            assertNotNull("Reference label missing in real OsmAnd", device.wait(Until.findObject(By.textContains("PH QA Reference")), 10_000))
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "osmand-reference.png")))
            assertTrue("OsmAnd settings cover the map; a reference sheet alone cannot prove markers",
                !device.hasObject(By.text("Plugins")) && !device.hasObject(By.text("Nothing found")))
            device.pressBack()
            // OsmAnd owns its own pre-existing task/back stack. Returning through Recents
            // must resume PH's waiting map entry and release the temporary layer.
            personalHubTask.moveToFront()
            assertTrue("Return to PersonalHub failed", device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
        } finally {
            ids.forEach { repository.deletePlace(it) }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class OsmAndExploreQaDeviceTest : OsmAndExploreQaScenario() {
    override fun verifyTarget(context: Context) {
        check(context.packageName.matches(Regex("com\\.gernalix\\.personalhub\\.qa[0-9]*")))
        check(Build.MODEL.contains("sdk", ignoreCase = true))
    }
}

@RunWith(AndroidJUnit4::class)
class OsmAndExplorePixelQaDeviceTest : OsmAndExploreQaScenario() {
    override fun verifyTarget(context: Context) {
        check(context.packageName == "com.gernalix.personalhub.qa")
        check(Build.MANUFACTURER.equals("Google", true) && Build.MODEL == "Pixel 8a")
        check(InstrumentationRegistry.getArguments().getString("physicalQaAuthorized") == "true")
    }

    @Test fun optionalMapAccessShowsTheMapOrActionablePermissionInstructions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        verifyTarget(context)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
        val task = context.getSystemService(android.app.ActivityManager::class.java).appTasks
            .first { it.taskInfo?.baseActivity?.packageName == context.packageName }
        context.startActivity(MapViewerCapsule.exploreMapIntent(context,
            OsmAndReference(55.6761, 12.5683, "PH QA permission reference"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val help = context.getString(com.gernalix.luoghi.R.string.places_osmand_permission_help,
            context.applicationInfo.loadLabel(context.packageManager))
        assertTrue("Expected either the real optional map or actionable permission guidance",
            device.wait(Until.hasObject(By.pkg("net.osmand")), 5_000) ||
                device.wait(Until.hasObject(By.text(help)), 20_000))
        if (device.hasObject(By.text(help))) {
            assertNotNull(device.findObject(By.text(context.getString(
                com.gernalix.luoghi.R.string.places_osmand_allow_map_access))))
        }
        task.moveToFront()
        device.pressBack()
    }
}
