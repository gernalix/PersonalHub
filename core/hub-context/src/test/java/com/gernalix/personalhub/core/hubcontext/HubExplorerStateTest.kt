package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubExplorerStateTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @After fun tearDown() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @Test fun breadcrumbScopeAndPaginationSurviveRecreation() {
        val start = HubEntityRef("people", "person", "giovanni")
        val scope = listOf(
            HubEntitySummary(start, "Giovanni"),
            HubEntitySummary(HubEntityRef("places", "place", "piazza"), "Piazza Savona"),
            HubEntitySummary(HubEntityRef("timer", "session", "session-1"), "12/02"),
        )
        val state = HubExplorerState(start, scope, initialized = true).also { it.limit = 120 }
        val snapshot = requireNotNull(with(HubExplorerState.Saver) { SaverScope { true }.save(state) })
        val restored = requireNotNull(HubExplorerState.Saver.restore(snapshot))
        assertEquals(scope, restored.scope)
        assertEquals(120, restored.limit)
    }

    @Test fun relatedSectionOrderAndVisibilityPersistAsPresentationOnly() {
        val owner = HubEntityRef("people", "person", "giovanni")
        val preferences = HubRelatedSectionPreferences(context, owner)
        preferences.save(listOf(HubRelatedSection("timer", "session"), HubRelatedSection("places", "place", visible = false)))
        val loaded = preferences.load(listOf(HubRelatedSection("places", "place"), HubRelatedSection("timer", "session"), HubRelatedSection("future", "resource")))
        assertEquals(listOf("timer/session", "places/place", "future/resource"), loaded.map { it.key })
        assertFalse(loaded[1].visible)
    }
}
