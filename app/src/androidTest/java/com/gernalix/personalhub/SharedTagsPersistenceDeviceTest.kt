package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubSavedTagFilter
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.core.hubcontext.SharedTagEngine
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class SharedTagsPersistenceDeviceTest {
    @TableProbe("hub_tags", "hub_tag_aliases", "hub_tag_assignments", "hub_tag_parents", "hub_saved_tag_filters")
    @Test fun sharedDefinitionsAliasesAssignmentsHierarchyAndFiltersPersist() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val database = PersonalHubDatabase.get(context)
        val engine = SharedTagEngine(database)
        val marker = "QA522084${UUID.randomUUID()}"
        val parent = requireNotNull(engine.create(HubTagNamespaces.PEOPLE, "${marker}Parent").tag)
        val child = requireNotNull(engine.create(HubTagNamespaces.PEOPLE, "${marker}Child").tag)
        val target = HubEntityRef("people", "person", marker)
        val filterId = "people:$marker"
        try {
            engine.addAlias(child.id, "${marker}Alias")
            engine.addParent(child.id, parent.id)
            engine.assign(target, listOf(child.id))
            val now = System.currentTimeMillis()
            engine.saveFilter(HubSavedTagFilter(filterId, HubTagNamespaces.PEOPLE, marker, "{\"include\":[\"${child.id}\"]}", now, now))
            assertEquals(child.id, engine.search(HubTagNamespaces.PEOPLE, "${marker}Alias").single().id)
            assertEquals(listOf(child.id), engine.tags(target).map { it.id })
            assertTrue(database.openHelper.readableDatabase.query("SELECT count(*) FROM hub_tag_parents WHERE child_tag_id=? AND parent_tag_id=?", arrayOf(child.id, parent.id)).use { it.moveToFirst(); it.getLong(0) == 1L })
            assertEquals(filterId, database.hubTagDao().filter(filterId)?.id)
        } finally {
            engine.deleteFilter(filterId)
            engine.remove(target, listOf(child.id))
            engine.deleteUnused(child.id)
            engine.deleteUnused(parent.id)
            database.hubContextDao().binding(target.moduleId, target.entityKind, target.canonicalId)?.let { database.hubContextDao().deleteBinding(it.id) }
        }
    }

    @TableProbe("hub_tags", "hub_tag_aliases", "hub_tag_assignments", "hub_tag_parents", "hub_saved_tag_filters")
    @Test fun prepareSharedTagGraphAndRunCanonicalBackupImport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val marker = "QA522084RoundTrip${UUID.randomUUID()}"
        val target = HubEntityRef("soldi", "transaction", marker)
        val filterId = "soldi:$marker"
        val database = PersonalHubDatabase.get(context)
        val engine = SharedTagEngine(database)
        val tag = requireNotNull(engine.create(HubTagNamespaces.SOLDI, marker).tag)
        val parent = requireNotNull(engine.create(HubTagNamespaces.SOLDI, "${marker}Parent").tag)
        engine.addAlias(tag.id, "${marker}Alias")
        engine.addParent(tag.id, parent.id)
        engine.assign(target, listOf(tag.id))
        val now = System.currentTimeMillis()
        engine.saveFilter(HubSavedTagFilter(filterId, HubTagNamespaces.SOLDI, marker, "{\"include\":[\"${tag.id}\"]}", now, now))
        val source = java.io.File(context.cacheDir, "shared-tags-roundtrip.db")
        val metadata = java.io.File(context.cacheDir, "shared-tags-roundtrip.json")
        DatabaseVault.backupCurrent(context).also { backup -> backup.copyTo(source, overwrite = true); backup.delete() }
        metadata.writeText(JSONObject().put("marker", marker).put("tag", tag.id).put("parent", parent.id).put("filter", filterId).toString())
        engine.deleteFilter(filterId)
        engine.remove(target, listOf(tag.id))
        engine.removeAlias(tag.id, "${marker}Alias")
        engine.deleteUnused(tag.id)
        engine.deleteUnused(parent.id)
        DatabaseVault.importDatabaseFile(context, source)
        // DatabaseVault intentionally requires a process restart before the imported DB is read.
    }

    @TableProbe("hub_tags", "hub_tag_aliases", "hub_tag_assignments", "hub_tag_parents", "hub_saved_tag_filters")
    @Test fun verifySharedTagGraphAfterImportProcessRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val source = java.io.File(context.cacheDir, "shared-tags-roundtrip.db")
        val metadata = java.io.File(context.cacheDir, "shared-tags-roundtrip.json")
        val data = JSONObject(metadata.readText())
        val marker = data.getString("marker")
        val tagId = data.getString("tag")
        val parentId = data.getString("parent")
        val filterId = data.getString("filter")
        val target = HubEntityRef("soldi", "transaction", marker)
        val database = PersonalHubDatabase.get(context)
        val engine = SharedTagEngine(database)
        try {
            assertEquals(tagId, engine.search(HubTagNamespaces.SOLDI, "${marker}Alias").single().id)
            assertEquals(listOf(tagId), engine.tags(target).map { it.id })
            assertTrue(database.openHelper.readableDatabase.query(
                "SELECT 1 FROM hub_tag_parents WHERE child_tag_id=? AND parent_tag_id=?",
                arrayOf(tagId, parentId),
            ).use { it.moveToFirst() })
            assertEquals(filterId, database.hubTagDao().filter(filterId)?.id)
        } finally {
            engine.deleteFilter(filterId)
            engine.remove(target, listOf(tagId))
            engine.deleteUnused(tagId)
            engine.deleteUnused(parentId)
            database.hubContextDao().binding(target.moduleId, target.entityKind, target.canonicalId)?.let { database.hubContextDao().deleteBinding(it.id) }
            source.delete()
            metadata.delete()
        }
    }
}
