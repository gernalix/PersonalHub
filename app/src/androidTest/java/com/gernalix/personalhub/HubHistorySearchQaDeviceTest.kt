package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gernalix.personalhub.capsules.shortcuts.HubModule
import com.gernalix.personalhub.capsules.shortcuts.LauncherShortcutsCapsule
import com.gernalix.personalhub.core.database.HubActivityStatus
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventStore
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class HubHistorySearchQaDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun globalHistorySearchFiltersHiddenDiffAndUndoAreLive() {
        val context = qaContext()
        val seed = seedReversiblePlaceUpdate(context)
        val semanticUpdate = MutationEventStore.recent(seed.database.openHelper.readableDatabase)
            .single { it.eventType == "places.place.updated" && it.entityId == seed.placeId }
        assertTrue(semanticUpdate.beforeJson.orEmpty().contains(seed.hiddenBeforeNote))
        assertTrue(semanticUpdate.afterJson.orEmpty().contains("hidden-after-"))
        composeRule.setContent {
            PersonalHubTheme {
                Surface {
                    PersonalHubApp()
                }
            }
        }

        val searchLabel = context.getString(R.string.home_history_search)
        composeRule.onNodeWithTag("home-grid")
            .performScrollToNode(hasText(searchLabel))
        composeRule.onNodeWithText(searchLabel).performClick()
        composeRule.onNodeWithTag("history-module-filter").assertIsDisplayed()
        waitForText(seed.placeName)
        composeRule.onNodeWithText(historyDayLabel(System.currentTimeMillis())).assertIsDisplayed()

        composeRule.onNodeWithTag("history-query")
            .performTextReplacement(seed.hiddenBeforeNote)
        waitForText(seed.placeName)

        val places = context.getString(R.string.module_places)
        composeRule.onNodeWithTag("history-module-filter").performClick()
        composeRule.onNode(hasText(places) and hasClickAction()).performClick()
        waitForText(seed.placeName, present = false)
        // The same popup must remain open across multiple checkbox selections.
        composeRule.onNode(hasText(places) and hasClickAction()).assertIsDisplayed().performClick()
        waitForText(seed.placeName)
        composeRule.onNodeWithText(context.getString(android.R.string.ok)).performScrollTo().performClick()
        composeRule.onNodeWithTag("history-from")
            .performTextReplacement("2099-01-01 00:00")
        waitForText(seed.placeName, present = false)
        composeRule.onNodeWithTag("history-from").performTextClearance()
        waitForText(seed.placeName)

        composeRule.waitUntil(timeoutMillis = 7_000L) {
            runCatching { composeRule.onNodeWithTag("history-undo").assertIsEnabled(); true }.getOrDefault(false)
        }
        val target = com.gernalix.personalhub.core.database.capsules.mutationevents.MutationUndoResolver.resolve(
            seed.database.openHelper.readableDatabase, semanticUpdate.transactionId, false,
        )
        assertEquals(com.gernalix.personalhub.core.database.capsules.mutationevents.MutationUndoTarget.Activity(seed.updateActivityId), target)
        composeRule.onNodeWithTag("history-undo").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 7_000L) {
            runBlocking {
                seed.database.activityDao().byId(seed.updateActivityId)?.status ==
                    HubActivityStatus.REVERTED
            }
        }

        val db = seed.database.openHelper.readableDatabase
        val restored = db.query(
            "SELECT address, notes FROM places WHERE uuid=? LIMIT 1",
            arrayOf(seed.placeId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0) to cursor.getString(1)
        }
        assertEquals(seed.beforeAddress, restored.first)
        assertEquals(seed.hiddenBeforeNote, restored.second)
        val compensation = runBlocking {
            seed.database.activityDao().page("places", 1, null, null, 20)
                .firstOrNull { it.revertsActivityId == seed.updateActivityId }
        }
        assertNotNull("Undo must append a compensating history event", compensation)

        composeRule.onNodeWithText(context.getString(R.string.activity_back)).performClick()
        composeRule.onNodeWithTag("home-grid")
            .performScrollToNode(hasText(context.getString(R.string.settings_title)))
        composeRule.onNodeWithText(context.getString(R.string.settings_title)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.git_history_title))
            .assertDoesNotExist()
    }

    @Test
    fun compactHomeShowsEveryModuleAndKeepsTheSavedLayout() {
        val context = qaContext()
        val preferences = context.getSharedPreferences("home_layout", Context.MODE_PRIVATE)
        val previous = preferences.getBoolean("compact", false)
        preferences.edit().putBoolean("compact", true).commit()
        try {
            composeRule.setContent { PersonalHubTheme { Surface { PersonalHubApp() } } }
            HubModule.entries.forEach { composeRule.onNodeWithContentDescription(context.getString(it.titleRes)).assertIsDisplayed() }
            listOf(R.string.home_context, R.string.home_tags, R.string.home_history_search,
                R.string.since_when_title, com.gernalix.personalhub.core.ui.R.string.countdown_title, R.string.settings_title).forEach {
                composeRule.onNodeWithContentDescription(context.getString(it)).assertIsDisplayed()
            }
            listOf(R.string.home_alerts to R.string.home_alerts_it, R.string.home_notifications to R.string.home_notifications_it).forEach { (english, italian) ->
                composeRule.onNodeWithContentDescription(com.gernalix.personalhub.core.alerts.AlertText.get(context, english, italian)).assertIsDisplayed()
            }
            composeRule.onNodeWithText(context.getString(com.gernalix.personalhub.core.ui.R.string.home_layout_extended)).assertIsDisplayed()
            composeRule.onNodeWithTag("home-layout-switch").performClick()
            composeRule.runOnIdle { assertTrue(!preferences.getBoolean("compact", true)) }
            composeRule.onNodeWithTag("home-layout-switch").performClick()
            composeRule.onNodeWithContentDescription(context.getString(R.string.settings_title)).assertIsDisplayed()
            composeRule.runOnIdle { assertTrue(preferences.getBoolean("compact", false)) }
        } finally { preferences.edit().putBoolean("compact", previous).commit() }
    }

    @Test
    fun auditButtonIsSeparateFromTheHumanHistoryTile() {
        val context = qaContext()
        composeRule.setContent {
            PersonalHubTheme {
                Surface {
                    PersonalHubApp()
                }
            }
        }

        composeRule.onNodeWithTag("home-audit-button").assertIsDisplayed().performClick()
        composeRule.onNodeWithText(context.getString(R.string.audit_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("history-module-filter").assertDoesNotExist()
    }

    @Test
    fun moduleScopeIsImmutableAndHasNoModuleFilter() {
        val context = qaContext()
        PersonalHubDatabase.get(context)
        val module = mutableStateOf("places")

        composeRule.setContent {
            PersonalHubTheme {
                Surface {
                    HubHistorySearchScreen(
                        onBack = {},
                        scopeModuleId = module.value,
                    )
                }
            }
        }

        composeRule.onNodeWithTag("history-module-filter").assertDoesNotExist()
        composeRule.onNodeWithText(
            "${context.getString(R.string.activity_title)} · " +
                context.getString(R.string.module_places),
        ).assertIsDisplayed()

        composeRule.runOnIdle { module.value = "timer" }
        composeRule.onNodeWithTag("history-module-filter").assertDoesNotExist()
        composeRule.onNodeWithText(
            "${context.getString(R.string.activity_title)} · " +
                context.getString(R.string.module_timer),
        ).assertIsDisplayed()
    }

    @Test
    fun hubAndSettingsEntryPointsExposeTheirScopedHistoryTabs() {
        val context = qaContext()
        val preferences = context.getSharedPreferences("home_layout", Context.MODE_PRIVATE)
        val previous = preferences.getBoolean("compact", false)
        preferences.edit().putBoolean("compact", false).commit()
        val generation = mutableStateOf(0)
        try {
            composeRule.setContent { PersonalHubTheme { Surface { PersonalHubApp(generation.value) } } }
            listOf(R.string.home_context to R.string.app_name, R.string.settings_title to R.string.settings_title,
                R.string.since_when_title to R.string.since_when_title,
                com.gernalix.personalhub.core.ui.R.string.countdown_title to com.gernalix.personalhub.core.ui.R.string.countdown_title).forEach { (entry, scopeTitle) ->
                composeRule.onNodeWithTag("home-grid").performScrollToNode(hasText(context.getString(entry)))
                composeRule.onNodeWithText(context.getString(entry)).performClick()
                composeRule.onNodeWithTag("module-history-tab").assertIsDisplayed().performClick()
                composeRule.onNodeWithText("${context.getString(R.string.activity_title)} · ${context.getString(scopeTitle)}").assertIsDisplayed()
                composeRule.onNodeWithTag("history-module-filter").assertDoesNotExist()
                composeRule.runOnIdle { generation.value += 1 }
            }
        } finally { preferences.edit().putBoolean("compact", previous).commit() }
    }

    @Test
    fun zz_allOwnerActivitiesExposeTheirSharedHistoryTabWithTheCorrectScope() {
        val context = qaContext()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val entries = HubModule.entries.map { module ->
            LauncherShortcutsCapsule.moduleIntent(context, module) to context.getString(module.titleRes)
        } + listOf(
            com.gernalix.personalhub.notifications.api.NotificationsRoute.intent(context) to
                com.gernalix.personalhub.core.alerts.AlertText.get(context, R.string.home_notifications, R.string.home_notifications_it),
            Intent(context, HubTagsActivity::class.java) to "Tags",
        )
        entries.forEachIndexed { index, (intent, name) ->
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Deny an optional QA-only runtime permission instead of importing user data.
            device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile(".*:id/permission_deny_button"))
                .pkg(java.util.regex.Pattern.compile("com\\.(google\\.android|android)\\.permissioncontroller"))), 500)?.click()
            device.findObject(By.pkg(context.packageName).text(context.getString(com.example.multitimetracker.R.string.notification_permission_not_now)))?.click()
            device.waitForIdle()
            try {
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodesWithTag("module-history-tab").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals("The QA app must own the foreground before input", context.packageName, device.currentPackageName)
                composeRule.onNodeWithTag("module-history-tab").assertIsDisplayed().performClick()
                val expected = "${context.getString(R.string.activity_title)} · $name"
                composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
                composeRule.onNodeWithText(expected).assertIsDisplayed()
                composeRule.onNodeWithTag("history-module-filter").assertDoesNotExist()
            } catch (failure: Throwable) {
                if (device.currentPackageName == context.packageName) {
                    runCatching { device.takeScreenshot(java.io.File(context.cacheDir, "module-history-failure-$index.png")) }
                }
                throw failure
            }
            device.pressBack()
            device.pressHome()
        }
    }

    @Test
    fun zz_placesAndTimerEntryPointsOpenTheSharedScopedScreen() {
        val context = qaContext()
        PersonalHubDatabase.get(context)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        context.startActivity(
            LauncherShortcutsCapsule.moduleIntent(context, HubModule.PLACES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val placesSearch = device.wait(
            Until.findObject(
                By.desc(context.getString(com.gernalix.luoghi.R.string.history_search)),
            ),
            7_000,
        )
        assertNotNull("Places shared History/Search entry is missing", placesSearch)
        placesSearch!!.visibleBounds.let { bounds ->
            device.click(bounds.centerX(), bounds.centerY())
        }
        assertNotNull(
            device.wait(
                Until.findObject(
                    By.text(
                        "${context.getString(R.string.activity_title)} · " +
                            context.getString(R.string.module_places),
                    ),
                ),
                7_000,
            ),
        )
        device.pressBack()
        device.waitForIdle()
        context.startActivity(
            LauncherShortcutsCapsule.moduleIntent(context, HubModule.TIMER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        // A clean Android 13+ install shows Timer's local-reminder primer on first launch.
        // Dismiss it by its actual action before looking for the History/Search entry.
        device.wait(
            Until.findObject(By.text(context.getString(com.example.multitimetracker.R.string.notification_permission_title))),
            7_000,
        )?.let {
            val notNow = device.findObject(
                By.text(context.getString(com.example.multitimetracker.R.string.notification_permission_not_now)),
            )
            assertNotNull("Timer local-reminder primer has no dismiss action", notNow)
            notNow!!.click()
        }
        assertNull(
            "Legacy Timer Timeline must not be reachable",
            device.wait(Until.findObject(By.text("Timeline")), 1_000),
        )
        val timerHistory = device.wait(
            Until.findObject(
                By.text(context.getString(com.example.multitimetracker.R.string.history_search)),
            ),
            7_000,
        )
        assertNotNull("Timer shared History/Search entry is missing", timerHistory)
        timerHistory!!.visibleBounds.let { bounds ->
            device.click(bounds.centerX(), bounds.centerY())
        }
        assertNotNull(
            device.wait(
                Until.findObject(
                    By.text(
                        "${context.getString(R.string.activity_title)} · " +
                            context.getString(R.string.module_timer),
                    ),
                ),
                7_000,
            ),
        )
        device.pressBack()
        device.pressHome()
    }

    private fun qaContext(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".qa")) {
            "History/Search QA must run against the isolated .qa package"
        }
        check(
            android.os.Build.MODEL.contains("Pixel", ignoreCase = true) ||
                android.os.Build.MODEL.contains("sdk", ignoreCase = true),
        ) {
            "History/Search QA requires an isolated Pixel QA package"
        }
        return context
    }

    private fun seedReversiblePlaceUpdate(context: Context): PlaceSeed {
        val database = PersonalHubDatabase.get(context)
        val db = database.openHelper.writableDatabase
        val suffix = System.currentTimeMillis().toString()
        val placeId = UUID.randomUUID().toString()
        val placeName = "QA History Place $suffix"
        val beforeAddress = "Before address $suffix"
        val afterAddress = "After address $suffix"
        val hiddenBeforeNote = "hidden-before-$suffix"
        val hiddenAfterNote = "hidden-after-$suffix"
        val now = System.currentTimeMillis()

        db.execSQL(
            """
            INSERT INTO places(
                uuid,nickname,address,lat,lon,radius_m,notes,source_app,
                created_at,updated_at,archived,first_check_in_at_place
            ) VALUES(?,?,?,NULL,NULL,NULL,?,'qa',?,?,0,NULL)
            """.trimIndent(),
            arrayOf(
                placeId,
                placeName,
                beforeAddress,
                hiddenBeforeNote,
                now,
                now,
            ),
        )
        db.execSQL(
            "DELETE FROM hub_activity_log WHERE module_id='places' AND entity_id=?",
            arrayOf(placeId),
        )
        db.execSQL(
            "UPDATE places SET address=?, notes=?, updated_at=? WHERE uuid=?",
            arrayOf(afterAddress, hiddenAfterNote, now + 1, placeId),
        )

        val update = runBlocking {
            database.activityDao().page("places", 1, null, null, 20)
                .single {
                    it.action == "place_updated" &&
                        it.entityId == placeId
                }
        }
        assertTrue(update.reversible)
        return PlaceSeed(
            database = database,
            placeId = placeId,
            placeName = placeName,
            beforeAddress = beforeAddress,
            hiddenBeforeNote = hiddenBeforeNote,
            updateActivityId = update.id,
        )
    }

    private fun waitForText(text: String, present: Boolean = true) {
        composeRule.waitUntil(timeoutMillis = 7_000L) {
            val found = composeRule
                .onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            found == present
        }
    }

    private data class PlaceSeed(
        val database: PersonalHubDatabase,
        val placeId: String,
        val placeName: String,
        val beforeAddress: String,
        val hiddenBeforeNote: String,
        val updateActivityId: String,
    )
}
