package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityLifecycle
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubOpenTarget
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubTagBacklinksTest {
    private lateinit var context: Context
    private lateinit var database: PersonalHubDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = PersonalHubDatabase.get(context)
        HubContextRuntime.initialize(context, listOf(
            FakeAdapter("people", "person"),
            FakeAdapter("places", "place"),
            FakeAdapter("soldi", "transaction"),
        ))
    }

    @After fun tearDown() = runBlocking {
        database.hubContextDao().contextsByType(HubContextRuntime.FINANCE_TRANSACTION_CONTEXT_TYPE).forEach {
            runCatching { database.hubContextDao().deleteContext(it.id) }
        }
    }

    @Test fun transactionIsBacklinkedFromPersonPlaceAndEveryTagAndKeepsOriginalTarget() = runBlocking {
        val transaction = HubEntityRef("soldi", "transaction", "tx-backlink")
        val person = HubEntityRef("people", "person", "person-a")
        val place = HubEntityRef("places", "place", "place-p")
        val first = requireNotNull(HubContextRuntime.tags().create(HubTagNamespaces.SOLDI, "Trip backlink").tag)
        val second = requireNotNull(HubContextRuntime.tags().create(HubTagNamespaces.SOLDI, "Shared backlink").tag)
        try {
            HubContextRuntime.saveFinanceTransactionLinksIfInitialized(transaction.canonicalId, person.canonicalId, place.canonicalId)
            HubContextRuntime.tags().assign(transaction, listOf(first.id, second.id))

            assertTrue(transaction in HubContextRuntime.linked(person).map { it.ref })
            assertTrue(transaction in HubContextRuntime.linked(place).map { it.ref })
            assertTrue(transaction in HubContextRuntime.linked(HubEntityRef("tags", "tag", first.id)).map { it.ref })
            assertTrue(transaction in HubContextRuntime.linked(HubEntityRef("tags", "tag", second.id)).map { it.ref })
            assertEquals(setOf(person, place, HubEntityRef("tags", "tag", first.id), HubEntityRef("tags", "tag", second.id)), HubContextRuntime.linked(transaction).map { it.ref }.toSet())
            assertEquals("personalhub://soldi/transaction/tx-backlink", HubContextRuntime.adapter("soldi", "transaction").openTarget(transaction.canonicalId)?.uri)
        } finally {
            HubContextRuntime.tags().clear(transaction)
            HubContextRuntime.tags().deleteUnused(first.id)
            HubContextRuntime.tags().deleteUnused(second.id)
        }
    }

    @Test fun universalFacetSearchCombinesTypedProvidersAndNamespaceScopedTags() = runBlocking {
        val tag = requireNotNull(HubContextRuntime.tags().create(HubTagNamespaces.SOLDI, "Alpha trip").tag)
        try {
            val all = HubContextRuntime.searchFacets("Alpha", HubTagNamespaces.SOLDI)
            assertEquals(setOf("people", "places", "soldi", "tags"), all.map { it.summary.ref.moduleId }.toSet())
            assertTrue(all.first { it.summary.ref.moduleId == "tags" }.isTag)

            val tagOnly = HubContextRuntime.searchFacets("#Alpha", HubTagNamespaces.SOLDI)
            assertEquals(listOf("tags"), tagOnly.map { it.summary.ref.moduleId }.distinct())
            assertEquals(tag.id, tagOnly.single().summary.ref.canonicalId)
        } finally {
            HubContextRuntime.tags().deleteUnused(tag.id)
        }
    }

    private class FakeAdapter(override val moduleId: String, override val entityKind: String) : HubEntityAdapter {
        override val capabilities = setOf("facet")
        override suspend fun exists(canonicalId: String) = true
        override suspend fun lifecycle(canonicalId: String) = HubEntityLifecycle.ACTIVE
        override suspend fun summaries(canonicalIds: Set<String>) = canonicalIds.associateWith { id ->
            HubEntitySummary(HubEntityRef(moduleId, entityKind, id), id)
        }
        override suspend fun search(query: String, limit: Int) = listOf(HubEntitySummary(HubEntityRef(moduleId, entityKind, "$moduleId-$query"), query))
        override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://$moduleId/$entityKind/$canonicalId")
    }
}
