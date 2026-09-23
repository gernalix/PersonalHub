package com.gernalix.personalhub.core.hubcontext

import androidx.room.withTransaction
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.HubActivityEntity
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.time.Instant
import java.util.UUID

data class HubContextMemberDraft(val bindingId: String, val role: String = "")
data class HubResolvedContextMember(val member: HubContextMember, val summary: HubEntitySummary)
data class HubContextView(
    val context: HubContext,
    val members: List<HubEntitySummary>,
    val resolvedMembers: List<HubResolvedContextMember> = emptyList(),
)
data class HubExplorerCandidate(val summary: HubEntitySummary, val compatibleContextCount: Int)
data class HubExplorerFacet(val moduleId: String, val entityKind: String, val candidates: List<HubExplorerCandidate>)
data class HubExplorerResult(val scope: List<HubEntitySummary>, val facets: List<HubExplorerFacet>)

class HubContextRepository(
    private val database: PersonalHubDatabase,
    private val adapters: HubAdapterRegistry,
    private val onBindingBatch: ((Int) -> Unit)? = null,
    private val appVersion: Long = 0,
) {
    private val dao = database.hubContextDao()

    suspend fun bind(ref: HubEntityRef): HubEntityBinding = database.withTransaction {
        dao.binding(ref.moduleId, ref.entityKind, ref.canonicalId)?.let { return@withTransaction it }
        val adapter = adapters.adapter(ref)
        require(adapter.exists(ref.canonicalId)) { "Canonical entity does not exist" }
        val lifecycle = adapter.lifecycle(ref.canonicalId)
        require(lifecycle in HubEntityLifecycle.values)
        HubEntityBinding(UUID.randomUUID().toString(), ref.moduleId, ref.entityKind, ref.canonicalId, lifecycle, now())
            .also { dao.insertBinding(it) }
    }

    suspend fun binding(ref: HubEntityRef): HubEntityBinding? = dao.binding(ref.moduleId, ref.entityKind, ref.canonicalId)

    suspend fun createContext(
        members: List<HubContextMemberDraft>,
        typeId: String? = null,
        title: String? = null,
    ): String = database.withTransaction {
        validateMembers(members, typeId)
        val id = UUID.randomUUID().toString()
        val timestamp = now()
        dao.insertContext(HubContext(id, typeId, title?.trim()?.takeIf(String::isNotEmpty), timestamp, timestamp))
        dao.insertMembers(members.mapIndexed { index, member -> HubContextMember(id, member.bindingId, member.role.trim(), index) })
        id
    }

    suspend fun updateContext(
        contextId: String,
        members: List<HubContextMemberDraft>,
        typeId: String? = null,
        title: String? = null,
    ) = database.withTransaction {
        val existing = requireNotNull(dao.context(contextId)) { "Context not found" }
        val normalizedTitle = title?.trim()?.takeIf(String::isNotEmpty)
        validateMembers(members, typeId)
        dao.deleteMembers(contextId)
        dao.insertMembers(members.mapIndexed { index, member -> HubContextMember(contextId, member.bindingId, member.role.trim(), index) })
        require(dao.updateContext(contextId, typeId, normalizedTitle, now()) == 1)
        // HubActivityCapture already records title/type changes through hub_contexts. When only the
        // member set changes that trigger intentionally stays quiet, so record the semantic episode
        // edit here rather than exposing one technical row per hub_context_member mutation.
        if (existing.contextTypeId == typeId && existing.title == normalizedTitle) {
            recordMemberOnlyContextUpdate(contextId, normalizedTitle)
        }
    }

    suspend fun context(contextId: String): HubContextView? {
        val context = dao.context(contextId) ?: return null
        return views(listOf(context)).single()
    }

    suspend fun removeMember(contextId: String, member: HubContextMemberDraft) = database.withTransaction {
        val context = requireNotNull(dao.context(contextId)) { "Context not found" }
        require(dao.memberCount(contextId) > 2) { "Removing this member would invalidate the Context" }
        require(dao.removeMember(contextId, member.bindingId, member.role.trim()) == 1) { "Context member not found" }
        recordMemberOnlyContextUpdate(contextId, context.title)
    }

    suspend fun deleteContext(contextId: String) = database.withTransaction {
        require(dao.deleteContext(contextId) == 1) { "Context not found" }
    }

    suspend fun replaceContextForAnchor(anchor: HubEntityRef, typeId: String, companions: List<HubEntityRef>): String? {
        return replaceContextForAnchorWithRoles(anchor, typeId, companions.map { it to "participant" })
    }

    suspend fun replaceContextForAnchorWithRoles(
        anchor: HubEntityRef,
        typeId: String,
        companions: List<Pair<HubEntityRef, String>>,
    ): String? {
        val anchorBinding = bind(anchor)
        val companionBindings = companions.distinct().map { (ref, role) ->
            bind(ref) to role.trim().also { require(it.isNotEmpty()) }
        }
        return database.withTransaction {
            dao.contextsForEntityAndType(anchorBinding.id, typeId).forEach { dao.deleteContext(it.id) }
            if (companionBindings.isEmpty()) return@withTransaction null
            val id = UUID.randomUUID().toString()
            val timestamp = now()
            dao.insertContext(HubContext(id, typeId, null, timestamp, timestamp))
            dao.insertMembers(
                listOf(HubContextMember(id, anchorBinding.id, "anchor", 0)) +
                    companionBindings.mapIndexed { index, (binding, role) -> HubContextMember(id, binding.id, role, index + 1) },
            )
            id
        }
    }

    suspend fun refreshLifecycle(bindingId: String) = database.withTransaction {
        val binding = requireNotNull(dao.binding(bindingId))
        val lifecycle = adapters.adapter(HubEntityRef(binding.moduleId, binding.entityKind, binding.canonicalId)).lifecycle(binding.canonicalId)
        require(lifecycle in HubEntityLifecycle.values)
        dao.setLifecycle(bindingId, lifecycle, now())
    }

    suspend fun canonicalDeleted(bindingId: String) = database.withTransaction {
        requireNotNull(dao.binding(bindingId))
        if (dao.membershipCount(bindingId) == 0) dao.deleteBinding(bindingId)
        else dao.setLifecycle(bindingId, HubEntityLifecycle.DELETED, now())
    }

    suspend fun saveType(type: HubContextType, fields: List<HubContextTypeField>) = saveType(type, fields, systemWrite = false)

    suspend fun saveSystemType(type: HubContextType, fields: List<HubContextTypeField>) =
        saveType(type.copy(locked = true), fields, systemWrite = true)

    private suspend fun saveType(type: HubContextType, fields: List<HubContextTypeField>, systemWrite: Boolean) = database.withTransaction {
        val existing = dao.type(type.id)
        require(systemWrite || (existing?.locked != true && !type.locked)) { "System Context Types are locked" }
        require(!systemWrite || type.locked)
        require(type.name.isNotBlank())
        require(fields.map { it.fieldId }.distinct().size == fields.size)
        require(fields.map { it.position }.toSet() == fields.indices.toSet())
        fields.forEach {
            require(it.contextTypeId == type.id && it.label.isNotBlank() && it.minCardinality >= 0)
            val maximum = it.maxCardinality
            require(maximum == null || maximum >= it.minCardinality)
            require((it.acceptedModuleId == null) == (it.acceptedEntityKind == null))
            require(it.acceptedCapability != null || it.acceptedEntityKind != null)
        }
        dao.upsertType(type)
        dao.deleteTypeFields(type.id)
        dao.insertTypeFields(fields)
    }

    suspend fun deleteType(typeId: String) = database.withTransaction {
        val type = requireNotNull(dao.type(typeId)) { "Context Type not found" }
        require(!type.locked) { "System Context Types are locked" }
        require(dao.deleteType(typeId) == 1)
    }

    suspend fun types(): List<HubContextType> = dao.types()
    suspend fun type(typeId: String): Pair<HubContextType, List<HubContextTypeField>>? =
        dao.type(typeId)?.let { it to dao.typeFields(typeId)
        }

    suspend fun saveCombinationAsType(contextId: String, name: String): String {
        require(name.isNotBlank())
        val view = requireNotNull(context(contextId)) { "Context not found" }
        val id = UUID.randomUUID().toString()
        val timestamp = now()
        val groups = view.resolvedMembers.groupBy { Triple(it.summary.ref.moduleId, it.summary.ref.entityKind, it.member.role) }
        saveType(
            HubContextType(id, name.trim(), timestamp, timestamp),
            groups.entries.mapIndexed { index, (key, members) ->
                HubContextTypeField(
                    id, "field-$index", index,
                    key.third.ifBlank { members.first().summary.ref.entityKind },
                    key.third, key.first, key.second,
                    minCardinality = 0,
                    maxCardinality = if (members.size > 1) null else 1,
                )
            },
        )
        return id
    }

    suspend fun contextsFor(bindingId: String) = dao.contextsForEntity(bindingId)

    suspend fun viewsFor(ref: HubEntityRef): List<HubContextView> {
        val anchor = binding(ref) ?: return emptyList()
        return views(dao.contextsForEntity(anchor.id))
    }

    suspend fun viewsByType(typeId: String): List<HubContextView> = dao.contextsByType(typeId).let { views(it) }
    suspend fun titledViews(): List<HubContextView> = dao.titledContexts().let { views(it) }

    fun changes() = dao.observeContextIds()

    suspend fun contextsContainingAll(scope: Collection<String>): List<HubContext> {
        val ids = normalizedScope(scope)
        return dao.contextsContainingAll(ids, ids.size)
    }

    suspend fun relatedToAll(scope: Collection<String>): List<HubEntityBinding> {
        val ids = normalizedScope(scope)
        return dao.relatedToAll(ids, ids.size)
    }

    suspend fun facetsRelatedToAll(scope: Collection<String>): List<HubEntityFacet> {
        val ids = normalizedScope(scope)
        return dao.facetsRelatedToAll(ids, ids.size)
    }

    suspend fun explore(scope: List<HubEntityRef>, limit: Int = 100, offset: Int = 0): HubExplorerResult {
        require(scope.isNotEmpty())
        require(limit in 1..200 && offset >= 0)
        val scopedBindings = scope.distinct().map { bind(it) }
        val counts = dao.candidatesRelatedToAll(scopedBindings.map { it.id }, scopedBindings.size, limit, offset)
        val candidates = bindings(counts.map { it.entityId })
        val summariesByRef = summaries(candidates).associateBy { it.ref }
        val countByBinding = counts.associate { it.entityId to it.contextCount }
        val facets = candidates.groupBy { it.moduleId to it.entityKind }.map { (kind, bindings) ->
            HubExplorerFacet(kind.first, kind.second, bindings.mapNotNull { binding ->
                summariesByRef[HubEntityRef(binding.moduleId, binding.entityKind, binding.canonicalId)]?.let { HubExplorerCandidate(it, countByBinding.getValue(binding.id)) }
            }.sortedWith(compareByDescending<HubExplorerCandidate> { it.compatibleContextCount }.thenBy { it.summary.label.lowercase() }))
        }.sortedWith(compareBy({ it.moduleId }, { it.entityKind }))
        val scopeByRef = summaries(scopedBindings).associateBy { it.ref }
        return HubExplorerResult(scope.distinct().mapNotNull(scopeByRef::get), facets)
    }

    private fun normalizedScope(scope: Collection<String>) = scope.toSet().toList().also { require(it.isNotEmpty()) }
    private suspend fun validateMembers(members: List<HubContextMemberDraft>, typeId: String?) {
        require(members.size >= 2) { "A Context requires at least two members" }
        require(members.map { it.bindingId to it.role.trim() }.distinct().size == members.size) { "Duplicate member role" }
        val found = bindings(members.map { it.bindingId }).associateBy { it.id }
        val bindings = members.associateWith { requireNotNull(found[it.bindingId]) { "Unknown Hub entity binding" } }
        if (typeId == null) return
        val fields = requireNotNull(type(typeId)) { "Unknown Context Type" }.second
        fields.forEach { field ->
            val count = bindings.count { (draft, binding) -> field.matches(draft, binding) }
            require(count >= field.minCardinality) { "${field.label}: minimum ${field.minCardinality}" }
            field.maxCardinality?.let { require(count <= it) { "${field.label}: maximum $it" } }
        }
        bindings.forEach { (draft, binding) -> require(fields.any { it.matches(draft, binding) }) { "Member is not accepted by this Context Type" } }
    }

    private fun HubContextTypeField.matches(draft: HubContextMemberDraft, binding: HubEntityBinding): Boolean {
        if (role.isNotBlank() && draft.role.trim() != role) return false
        if (acceptedModuleId != null && (binding.moduleId != acceptedModuleId || binding.entityKind != acceptedEntityKind)) return false
        return acceptedCapability == null || acceptedCapability in adapters.adapter(HubEntityRef(binding.moduleId, binding.entityKind, binding.canonicalId)).capabilities
    }

    private suspend fun views(contexts: List<HubContext>): List<HubContextView> {
        if (contexts.isEmpty()) return emptyList()
        val storedMembers = dao.membersForContexts(contexts.map { it.id })
        val bindings = bindings(storedMembers.map { it.entityId })
        val bindingIdByRef = bindings.associate {
            HubEntityRef(it.moduleId, it.entityKind, it.canonicalId) to it.id
        }
        val byBinding = summaries(bindings).mapNotNull { summary ->
            bindingIdByRef[summary.ref]?.let { bindingId -> bindingId to summary }
        }.toMap()
        val resolvedByContext = storedMembers.mapNotNull { member ->
            byBinding[member.entityId]?.let { member.contextId to HubResolvedContextMember(member, it) }
        }.groupBy({ it.first }, { it.second })
        return contexts.map { context ->
            val resolved = resolvedByContext[context.id].orEmpty()
            HubContextView(context, resolved.map { it.summary }, resolved)
        }
    }

    private suspend fun recordMemberOnlyContextUpdate(contextId: String, title: String?) {
        database.activityDao().insert(
            HubActivityEntity(
                id = UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                moduleId = "hub",
                action = "episode_updated",
                entityKind = "episode",
                entityId = contextId,
                entityLabel = title,
                origin = "user",
                sourceTable = "hub_contexts",
                sourceRowKey = contextId,
                appVersion = appVersion,
                reversible = false,
            ),
        )
    }

    private suspend fun summaries(bindings: List<HubEntityBinding>): List<HubEntitySummary> =
        bindings.groupBy { it.moduleId to it.entityKind }.flatMap { (_, group) ->
            val first = group.first()
            val adapter = adapters.adapter(HubEntityRef(first.moduleId, first.entityKind, first.canonicalId))
            val resolved = adapter.summaries(group.map { it.canonicalId }.toSet())
            group.map { binding ->
                resolved[binding.canonicalId]
                    ?: HubEntitySummary(
                        HubEntityRef(binding.moduleId, binding.entityKind, binding.canonicalId),
                        binding.canonicalId,
                        lifecycle = binding.lifecycle,
                    )
            }
        }
    private suspend fun bindings(ids: List<String>): List<HubEntityBinding> {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) return emptyList()
        onBindingBatch?.invoke(distinctIds.size)
        return dao.bindings(distinctIds)
    }
    private fun now() = System.currentTimeMillis()
}
