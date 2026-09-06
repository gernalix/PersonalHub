package com.gernalix.personalhub.core.hubcontext

import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityRef

class HubAdapterRegistry(adapters: Collection<HubEntityAdapter>) {
    private val byKind = adapters.associateBy { it.moduleId to it.entityKind }
        .also { require(it.size == adapters.size) { "Duplicate Hub adapter kind" } }

    fun adapter(ref: HubEntityRef): HubEntityAdapter =
        requireNotNull(byKind[ref.moduleId to ref.entityKind]) { "Unregistered Hub entity kind" }

    fun all(): Collection<HubEntityAdapter> = byKind.values
}
