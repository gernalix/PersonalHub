package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.core.alerts.AlertTargetKind
import com.gernalix.personalhub.core.alerts.AlertTrigger
import com.gernalix.personalhub.core.alerts.PlaceAlertDraft
import com.gernalix.personalhub.core.alerts.PlaceAlertRepository
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceAlertPersistenceDeviceTest {
    @TableProbe("alert_rules", "alert_place_tag_targets")
    @Test fun alertRuleAndTagTargetsPersistThroughRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val name = "qa914263-alert-${UUID.randomUUID()}.db"
        val owner = PersonalHubDatabase.openTemporary(context, name)
        try {
            val marker = "QA914263Alert${UUID.randomUUID()}"
            val places = PlaceRepository(context, owner)
            val placeId = places.savePlace(null, marker, null, null, null, 75.0, null, marker)
            places.setPlaceTags(placeId, listOf(marker))
            val tagId = places.tagsForPlace(placeId).single().id
            val repo = PlaceAlertRepository(context, owner)
            val draft = PlaceAlertDraft(marker, AlertTrigger.PLACE_CHECK_IN, AlertTargetKind.TAGS, placeTagIds = setOf(tagId))
            val ruleId = repo.create(draft)
            assertEquals(marker, owner.alertDao().getRule(ruleId)?.message)
            assertEquals(setOf(tagId), repo.placeTagTargets(listOf(ruleId))[ruleId])
            assertTrue(repo.update(ruleId, draft.copy(message = "${marker}Edited", trigger = AlertTrigger.PLACE_CHECK_OUT)))
            assertEquals("${marker}Edited", owner.alertDao().getRule(ruleId)?.message)
            assertEquals(1L, owner.openHelper.readableDatabase.query(
                "SELECT count(*) FROM alert_place_tag_targets WHERE rule_id=?", arrayOf(ruleId),
            ).use { it.moveToFirst(); it.getLong(0) })
            assertTrue(repo.delete(ruleId))
            assertEquals(false, owner.alertDao().getRule(ruleId)?.enabled)
        } finally {
            owner.close()
            context.deleteDatabase(name)
        }
    }
}
