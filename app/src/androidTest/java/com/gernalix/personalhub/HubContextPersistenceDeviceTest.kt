package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.contracts.database.HubContextType
import com.gernalix.personalhub.contracts.database.HubContextTypeField
import com.gernalix.personalhub.contracts.database.HubCreateRequest
import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityLifecycle
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubAdapterRegistry
import com.gernalix.personalhub.core.hubcontext.HubContextMemberDraft
import com.gernalix.personalhub.core.hubcontext.HubContextRepository
import com.gernalix.personalhub.core.hubcontext.ResourceHubAdapter
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HubContextPersistenceDeviceTest {
    @TableProbe("hub_resources")
    @Test fun resourcePersistsThroughHubAdapter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val adapter = ResourceHubAdapter(context)
        val marker = "QA914263Resource${UUID.randomUUID()}"
        val created = requireNotNull(adapter.create(HubCreateRequest(
            suggestedLabel = marker, extras = mapOf("kind" to "note", "value" to marker),
        )))
        val id = created.ref.canonicalId
        try {
            assertEquals(marker, owner.hubResourceDao().resource(id)?.value)
            owner.hubResourceDao().update(requireNotNull(owner.hubResourceDao().resource(id)).copy(value = "${marker}Edited"))
            assertEquals("${marker}Edited", owner.hubResourceDao().resource(id)?.value)
        } finally {
            adapter.delete(id)
            assertNull(owner.hubResourceDao().resource(id))
        }
    }

    private class QaAdapter : HubEntityAdapter {
        override val moduleId = "qa914263"
        override val entityKind = "fixture"
        override val capabilities = setOf("fixture")
        override suspend fun exists(canonicalId: String) = canonicalId.startsWith("QA914263")
        override suspend fun lifecycle(canonicalId: String) = HubEntityLifecycle.ACTIVE
        override suspend fun summaries(canonicalIds: Set<String>) = canonicalIds.associateWith { id ->
            HubEntitySummary(HubEntityRef(moduleId, entityKind, id), id)
        }
        override suspend fun search(query: String, limit: Int) = emptyList<HubEntitySummary>()
        override suspend fun openTarget(canonicalId: String) = null
    }

    @TableProbe("hub_context_types", "hub_context_type_fields", "hub_entity_bindings", "hub_contexts", "hub_context_members")
    @Test fun contextTypeBindingsAndMembersPersistThroughRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val repo = HubContextRepository(owner, HubAdapterRegistry(listOf(QaAdapter())))
        val marker = "QA914263Context${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        var contextId: String? = null
        val bindingIds = mutableListOf<String>()
        fun count(table: String, key: String, value: Any) = owner.openHelper.readableDatabase.query(
            "SELECT count(*) FROM $table WHERE $key=?", arrayOf(value),
        ).use { it.moveToFirst(); it.getLong(0) }
        try {
            repo.saveType(HubContextType(marker, marker, now, now), listOf(
                HubContextTypeField(marker, "people", 0, "People", acceptedModuleId = "qa914263", acceptedEntityKind = "fixture", minCardinality = 2),
            ))
            assertEquals(1L, count("hub_context_type_fields", "context_type_id", marker))
            val refs = (1..2).map { HubEntityRef("qa914263", "fixture", "$marker$it") }
            refs.forEach { bindingIds += repo.bind(it).id }
            assertEquals(2L, count("hub_entity_bindings", "module_id", "qa914263"))
            contextId = repo.createContext(bindingIds.map { HubContextMemberDraft(it, "people") }, marker, marker)
            assertEquals(marker, repo.context(contextId!!)?.context?.title)
            assertEquals(2L, count("hub_context_members", "context_id", contextId!!))
            repo.updateContext(contextId!!, bindingIds.map { HubContextMemberDraft(it, "people") }, marker, "${marker}Edited")
            assertEquals("${marker}Edited", repo.context(contextId!!)?.context?.title)
            repo.saveType(HubContextType(marker, "${marker}Edited", now, now), listOf(
                HubContextTypeField(marker, "people", 0, "People updated", acceptedModuleId = "qa914263", acceptedEntityKind = "fixture", minCardinality = 2),
            ))
            assertEquals("People updated", repo.type(marker)?.second?.single()?.label)
        } finally {
            contextId?.let { repo.deleteContext(it) }
            repo.deleteType(marker)
            bindingIds.forEach { owner.hubContextDao().deleteBinding(it) }
            assertNull(repo.type(marker))
            contextId?.let { assertEquals(0L, count("hub_contexts", "id", it)) }
        }
    }
}
