package com.gernalix.personalhub

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.contracts.database.SinceWhenSourceDescriptor
import com.gernalix.personalhub.contracts.database.SinceWhenTimestampSource
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.ui.launchSinceWhenCreate
import com.gernalix.personalhub.core.ui.reviewSinceWhenCreate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SinceWhenCreationSecurityTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val source = SinceWhenSourceDescriptor(
        "places/place", "synthetic-source", "Synthetic private source",
        listOf(SinceWhenTimestampSource("created", "Created", 1790000012345L, true)),
    )
    private val dao get() = PersonalHubDatabase.get(context).sinceWhenCounterDao()

    @Before fun createSyntheticDatabase() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
        PersonalHubDatabase.get(context).openHelper.writableDatabase.execSQL(
            "INSERT INTO hub_entities VALUES(?,'places/place','places','places',?,'TOMBSTONED',1,1)",
            arrayOf(source.entityId, source.entityId),
        )
    }

    @After fun closeSyntheticDatabase() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DB_NAME)
    }

    @Test fun externalEnabledFlagOnlyPreselectsUiAndRequiresConfirmation() {
        val target = HubDeepLinkContract.parse(HubDeepLinkContract.sinceWhenCreateUri(source, true))
            .target as HubDeepLinkContract.SinceWhenCreateTarget
        var finished = 0
        compose.setContent { MaterialTheme {
            SinceWhenCreateScreen(target.source, { finished++ }, startEnabled = target.startEnabled)
        } }
        val label = context.getString(R.string.since_when_create_action)
        compose.onNodeWithText(label).assertIsEnabled()
        assertTrue(runBlocking { dao.all().isEmpty() })
        assertEquals(0, finished)
        compose.onNodeWithText(label).performClick()
        awaitCreation { finished }
        assertEquals(source.entityId, runBlocking { dao.all().single().sourceEntityId })
    }

    @Test fun cancelExternalCreationLeavesNoRecord() {
        var finished = 0
        compose.setContent { MaterialTheme {
            SinceWhenCreateScreen(source, { finished++ }, startEnabled = true)
        } }
        compose.onNodeWithText(context.getString(R.string.since_when_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(1, finished)
        assertTrue(runBlocking { dao.all().isEmpty() })
    }

    @Test fun privateOptInCreatesOnceAndRecreationKeepsTheDuplicateGuard() {
        val showing = mutableStateOf(true)
        var finished = 0
        compose.setContent { MaterialTheme {
            if (showing.value) SinceWhenCreateScreen(source, { finished++ }, startEnabled = true, autoCreate = true)
        } }
        awaitCreation { finished }
        compose.runOnIdle { showing.value = false }
        compose.waitForIdle()
        compose.runOnIdle { showing.value = true }
        compose.onNodeWithText(context.getString(R.string.since_when_open_counter)).assertIsEnabled()
        assertEquals(1, runBlocking { dao.all().size })
        assertEquals(1, finished)
    }

    @Test fun bothInternalNavigationIntentsConfineTheInlineDescriptorToThisPackage() {
        val application = context.applicationContext as Application
        while (shadowOf(application).nextStartedActivity != null) { /* Drain the test host launch. */ }
        context.reviewSinceWhenCreate(source)
        val review = requireNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(context.packageName, review.`package`)
        assertEquals(Intent.ACTION_VIEW, review.action)
        assertFalse((HubDeepLinkContract.parse(review.data).target as HubDeepLinkContract.SinceWhenCreateTarget).startEnabled)

        context.launchSinceWhenCreate(source)
        val automatic = requireNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(context.packageName, automatic.`package`)
        assertEquals(HubDeepLinkContract.ACTION_CREATE_SINCE_WHEN_INTERNAL, automatic.action)
        val decoded = HubDeepLinkContract.parse(automatic.data).target as HubDeepLinkContract.SinceWhenCreateTarget
        assertEquals(source, decoded.source)
        assertTrue(decoded.startEnabled)
    }

    @Test fun onlyThePrivateHostActivityHasAutomaticCreationAuthority() {
        val manager = context.packageManager
        assertFalse(manager.getActivityInfo(ComponentName(context, SinceWhenCreateActivity::class.java), 0).exported)
        assertTrue(manager.getActivityInfo(ComponentName(context, HubDeepLinkActivity::class.java), 0).exported)
    }

    private fun awaitCreation(finished: () -> Int) {
        try {
            compose.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                runBlocking { dao.all().size == 1 } && finished() == 1
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("rows=${runBlocking { dao.all().size }} finished=${finished()}\n" +
                compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 20), failure)
        }
    }

}
