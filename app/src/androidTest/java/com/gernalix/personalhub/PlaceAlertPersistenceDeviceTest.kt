package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.core.alerts.AlertTargetKind
import com.gernalix.personalhub.core.alerts.AlertMatchMode
import com.gernalix.personalhub.core.alerts.AlertTrigger
import com.gernalix.personalhub.core.alerts.PlaceAlertDraft
import com.gernalix.personalhub.core.alerts.PlaceAlertRepository
import com.gernalix.personalhub.core.alerts.UnifiedPlaceAlertsProvider
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.alerts.AlertFiringEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceAlertPersistenceDeviceTest {
    @TableProbe("alert_rules", "alert_rule_targets", "alert_firings")
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
            assertEquals(setOf(tagId), repo.targets(listOf(ruleId))[ruleId])
            val firedAt = System.currentTimeMillis()
            owner.alertDao().recordSuccessfulDelivery(
                AlertFiringEntity(
                    id = UUID.randomUUID().toString(),
                    ruleId = ruleId,
                    domain = "places",
                    trigger = AlertTrigger.PLACE_CHECK_IN.name,
                    entityId = placeId,
                    entityLabel = marker,
                    tagNames = marker,
                    delivery = "notification",
                    message = marker,
                    firedAt = firedAt,
                ),
                keepEnabled = true,
            )
            val firing = owner.alertDao().recentFirings("places", 1).single()
            assertEquals(placeId, firing.entityId)
            assertEquals(marker, firing.tagNames)
            assertEquals(firedAt, owner.alertDao().getRule(ruleId)?.lastFiredAt)
            assertTrue(repo.update(ruleId, draft.copy(message = "${marker}Edited", trigger = AlertTrigger.PLACE_CHECK_OUT)))
            assertEquals("${marker}Edited", owner.alertDao().getRule(ruleId)?.message)
            assertEquals(1L, owner.openHelper.readableDatabase.query(
                "SELECT count(*) FROM alert_rule_targets WHERE rule_id=?", arrayOf(ruleId),
            ).use { it.moveToFirst(); it.getLong(0) })
            assertTrue(repo.delete(ruleId))
            assertEquals(false, owner.alertDao().getRule(ruleId)?.enabled)

            val provider = UnifiedPlaceAlertsProvider(context, owner)
            val entityDraft = PlaceAlertDraft(marker, AlertTrigger.PLACE_BOTH, AlertTargetKind.ENTITY,
                placeId = placeId, enabled = false)
            assertTrue(provider.save(null, entityDraft, false))
            val entity = provider.snapshot().rules.single { it.message == marker }
            assertEquals(placeId, entity.entityId)
            assertEquals(false, entity.enabled)
            assertTrue(provider.setEnabled(entity.id, true))
            assertTrue(provider.save(entity.id, entityDraft.copy(message = "${marker}Edited"), true))
            assertEquals(true, provider.snapshot().rules.single { it.id == entity.id }.enabled)

            val tagsDraft = PlaceAlertDraft(marker, AlertTrigger.PLACE_CHECK_OUT, AlertTargetKind.TAGS,
                placeTagIds = setOf(tagId), matchMode = AlertMatchMode.ANY)
            assertTrue(provider.save(null, tagsDraft, true))
            val tagged = provider.snapshot().rules.single { it.message == marker }
            assertEquals(null, tagged.entityId)
            assertEquals(AlertMatchMode.ANY.name, tagged.matchMode)
            assertEquals(setOf(tagId), provider.snapshot().targets[tagged.id])
            assertTrue(provider.save(tagged.id, tagsDraft.copy(matchMode = AlertMatchMode.ALL), true))
            assertEquals(AlertMatchMode.ALL.name, provider.snapshot().rules.single { it.id == tagged.id }.matchMode)
            assertTrue(provider.delete(entity.id))
            assertTrue(provider.delete(tagged.id))
            assertTrue(provider.snapshot().rules.isEmpty())
        } finally {
            owner.close()
            context.deleteDatabase(name)
        }
    }
}
