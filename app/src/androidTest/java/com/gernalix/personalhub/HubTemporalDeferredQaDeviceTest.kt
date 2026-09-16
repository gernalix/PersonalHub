package com.gernalix.personalhub

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.contracts.database.HubCreateRequest
import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityLifecycle
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubOpenTarget
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.HubTemporalKind
import com.gernalix.personalhub.core.hubcontext.HubTemporalPage
import com.gernalix.personalhub.core.hubcontext.HubTemporalProvider
import com.gernalix.personalhub.core.hubcontext.HubTemporalQuery
import com.gernalix.personalhub.core.hubcontext.HubTemporalRecord
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HubTemporalDeferredQaDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun homeSearchEpisodesAndFatigueAreReachableFromUi() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(
            android.os.Build.MODEL.contains("Pixel", ignoreCase = true) ||
                android.os.Build.MODEL.contains("sdk", ignoreCase = true)
        )
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)

        val now = System.currentTimeMillis()
        val episodeTitle = "QA episode ${now}"
        val provider = QaWordPulseAdapter(now)
        HubContextRuntime.initialize(context, listOf(provider))
        runBlocking {
            HubContextRuntime.createContext(
                listOf(
                    HubEntityRef("wordpulse", "entry", "word-a") to "",
                    HubEntityRef("wordpulse", "entry", "word-b") to "",
                ),
                title = episodeTitle,
            )
        }

        composeRule.setContent {
            PersonalHubTheme {
                Surface {
                    PersonalHubApp()
                }
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.home_context)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.home_context_help)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.home_search)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.home_search_help)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.home_activity_register)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.home_activity_help)).assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.home_search)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.temporal_saved_episodes)).assertIsDisplayed()
        composeRule.onNodeWithText(episodeTitle).assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.temporal_search)).performClick()
        composeRule.waitUntil(timeoutMillis = 7_000L) {
            composeRule
                .onAllNodesWithText(context.getString(R.string.temporal_wordpulse_fatigue_available, 80))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule
            .onNodeWithText(context.getString(R.string.temporal_wordpulse_fatigue_available, 80))
            .assertIsDisplayed()

        composeRule.onNodeWithText(episodeTitle).performClick()
        composeRule.onNodeWithTag("hub-composer-title").assertIsDisplayed()
        composeRule.onNodeWithText(episodeTitle).assertIsDisplayed()
    }

    private class QaWordPulseAdapter(private val nowMs: Long) : HubEntityAdapter, HubTemporalProvider {
        private val summaries = listOf(
            HubEntitySummary(HubEntityRef("wordpulse", "entry", "word-a"), "Focus"),
            HubEntitySummary(HubEntityRef("wordpulse", "entry", "word-b"), "Flow"),
        ).associateBy { it.ref.canonicalId }

        override val moduleId: String = "wordpulse"
        override val entityKind: String = "entry"
        override val capabilities: Set<String> = emptySet()
        override suspend fun exists(canonicalId: String): Boolean = canonicalId in summaries
        override suspend fun lifecycle(canonicalId: String): String = HubEntityLifecycle.ACTIVE
        override suspend fun summaries(canonicalIds: Set<String>): Map<String, HubEntitySummary> =
            summaries.filterKeys { it in canonicalIds }

        override suspend fun search(query: String, limit: Int): List<HubEntitySummary> =
            summaries.values.filter { it.label.contains(query, ignoreCase = true) }.take(limit)

        override suspend fun openTarget(canonicalId: String): HubOpenTarget? = null
        override suspend fun create(request: HubCreateRequest): HubEntitySummary? = null

        override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage =
            HubTemporalPage(
                listOf(
                    HubTemporalRecord(
                        moduleId = moduleId,
                        source = "qa",
                        stableId = "word-a",
                        kind = HubTemporalKind.POINT,
                        startMs = nowMs,
                        title = "Focus word",
                        entityRef = HubEntityRef(moduleId, entityKind, "word-a"),
                        attributes = mapOf("fatigueScore" to "80"),
                    )
                ).filter { it.overlaps(query.fromMs, query.toMs) },
            )
    }
}
