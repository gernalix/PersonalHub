package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

        composeRule.onNodeWithTag("history-query")
            .performTextReplacement(seed.hiddenBeforeNote)
        waitForText(seed.placeName)

        val places = context.getString(R.string.module_places)
        composeRule.onNode(hasText(places) and hasClickAction()).performClick()
        waitForText(seed.placeName, present = false)
        composeRule.onNode(hasText(places) and hasClickAction()).performClick()
        waitForText(seed.placeName)
        composeRule.onNodeWithTag("history-from")
            .performTextReplacement("2099-01-01 00:00")
        waitForText(seed.placeName, present = false)
        composeRule.onNodeWithTag("history-from").performTextClearance()
        waitForText(seed.placeName)

        composeRule.onNodeWithTag("history-undo").performClick()
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
