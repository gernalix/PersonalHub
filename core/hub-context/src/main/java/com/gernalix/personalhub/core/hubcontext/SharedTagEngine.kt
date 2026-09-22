package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import androidx.room.withTransaction
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class HubTagMatchMode { AND, OR }

data class HubTagFilter(
    val include: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
    val mode: HubTagMatchMode = HubTagMatchMode.AND,
    val noTags: Boolean = false,
)

data class HubTagCreateResult(
    val tag: HubTagEntity?,
    val exactDuplicate: HubTagEntity? = null,
    val nearDuplicates: List<HubTagEntity> = emptyList(),
)

data class HubFacetSuggestion(
    val summary: HubEntitySummary,
    val typeLabel: String,
    val icon: String,
    val isTag: Boolean,
)

class SharedTagEngine(
    private val db: PersonalHubDatabase,
) {
    private val dao get() = db.hubTagDao()

    suspend fun search(namespace: String, query: String, limit: Int = 50): List<HubTagEntity> {
        requireNamespace(namespace)
        val normalized = normalize(query.removePrefix("#"))
        return dao.search(namespace, "%$normalized%", limit)
    }

    fun observe(namespace: String): Flow<List<HubTagEntity>> {
        requireNamespace(namespace)
        return dao.observe(namespace)
    }

    fun observeAssignments(namespace: String): Flow<List<HubTagAssignmentView>> {
        requireNamespace(namespace)
        return dao.observeAssignments(namespace)
    }

    fun observeFilters(namespace: String): Flow<List<HubSavedTagFilter>> {
        requireNamespace(namespace)
        return dao.observeFilters(namespace)
    }

    suspend fun all(): List<HubTagEntity> = dao.allTags()
    suspend fun aliases(tagId: String): List<HubTagAlias> = dao.aliases(tagId)
    suspend fun removeAlias(tagId: String, alias: String): Boolean =
        dao.removeAlias(tagId, normalize(alias)) == 1

    suspend fun updateDetails(
        tagId: String,
        description: String?,
        icon: String?,
        color: String?,
    ) {
        val current = requireNotNull(dao.tag(tagId))
        dao.upsert(
            current.copy(
                description = description?.trim()?.takeIf(String::isNotEmpty),
                icon = icon?.trim()?.takeIf(String::isNotEmpty),
                color = color?.trim()?.takeIf(String::isNotEmpty),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun saveFilter(value: HubSavedTagFilter) = dao.saveFilter(value)
    suspend fun deleteFilter(id: String): Boolean = dao.deleteFilter(id) == 1

    suspend fun create(
        namespace: String,
        name: String,
        description: String? = null,
        icon: String? = null,
        color: String? = null,
        kind: String = HubTagKinds.FREE,
        global: Boolean = namespace == HubTagNamespaces.GLOBAL,
        acceptNearDuplicate: Boolean = false,
    ): HubTagCreateResult {
        requireNamespace(namespace)
        require(kind in HubTagKinds.values)
        require((kind == HubTagKinds.CATEGORY) == (namespace == HubTagNamespaces.SOLDI_CATEGORY))
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Tag name is blank" }
        val normalized = normalize(trimmed)
        dao.tag(namespace, normalized)?.let { return HubTagCreateResult(null, exactDuplicate = it) }
        val near = dao.search(namespace, "%", 500).filter { candidate ->
            candidate.normalizedName != normalized && levenshtein(candidate.normalizedName, normalized) <= nearDuplicateDistance(normalized)
        }
        if (near.isNotEmpty() && !acceptNearDuplicate) return HubTagCreateResult(null, nearDuplicates = near)
        val now = System.currentTimeMillis()
        val tag = HubTagEntity(UUID.randomUUID().toString(), namespace, kind, trimmed, normalized, description, icon, color, now, now, isGlobal = global)
        dao.upsert(tag)
        return HubTagCreateResult(tag)
    }

    suspend fun getOrCreate(namespace: String, name: String): HubTagEntity {
        val normalized = normalize(name)
        dao.tag(namespace, normalized)?.let { return it }
        return requireNotNull(create(namespace, name, acceptNearDuplicate = true).tag)
    }

    suspend fun createStable(
        id: String,
        namespace: String,
        name: String,
        metadataJson: String? = null,
        kind: String = HubTagKinds.FREE,
    ): HubTagEntity {
        requireNamespace(namespace)
        require(kind in HubTagKinds.values)
        require(id.isNotBlank())
        dao.tag(id)?.let {
            require(it.namespace == namespace) { "Stable tag id belongs to ${it.namespace}" }
            return it
        }
        val trimmed = name.trim()
        val normalized = normalize(trimmed)
        require(trimmed.isNotEmpty())
        dao.tag(namespace, normalized)?.let { return it }
        val now = System.currentTimeMillis()
        return HubTagEntity(
            id = id,
            namespace = namespace,
            kind = kind,
            name = trimmed,
            normalizedName = normalized,
            createdAt = now,
            updatedAt = now,
            isGlobal = namespace == HubTagNamespaces.GLOBAL,
            metadataJson = metadataJson,
        ).also { dao.upsert(it) }
    }

    suspend fun syncProjection(
        id: String,
        namespace: String,
        name: String,
        archived: Boolean,
        metadataJson: String? = null,
    ): HubTagEntity = db.withTransaction {
        val current = createStable(id, namespace, name, metadataJson)
        require(current.id == id) { "Projection id collision in $namespace" }
        val normalized = normalize(name)
        val updated = current.copy(
            name = name.trim(),
            normalizedName = normalized,
            archived = archived,
            metadataJson = metadataJson,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(updated)
        updated
    }

    suspend fun replaceByNames(target: HubEntityRef, namespace: String, names: Collection<String>) {
        requireNamespace(namespace)
        val desiredNames = names.asSequence().map(String::trim).filter(String::isNotEmpty).distinctBy(::normalize).toList()
        val desired = buildList { for (name in desiredNames) add(getOrCreate(namespace, name)) }
        val current = tags(target).filter { it.namespace == namespace }
        remove(target, current.map(HubTagEntity::id) - desired.map(HubTagEntity::id).toSet())
        assign(target, desired.map(HubTagEntity::id))
    }

    suspend fun replace(target: HubEntityRef, namespace: String, tagIds: Collection<String>) {
        requireNamespace(namespace)
        val desired = tagIds.distinct().map { id -> requireNotNull(dao.tag(id)) { "Unknown tag: $id" } }
        desired.forEach { require(it.namespace == namespace || it.isGlobal) }
        val current = tags(target).filter { it.namespace == namespace || it.isGlobal }
        remove(target, current.map(HubTagEntity::id) - desired.map(HubTagEntity::id).toSet())
        assign(target, desired.map(HubTagEntity::id))
    }

    suspend fun assign(
        target: HubEntityRef,
        tagIds: Collection<String>,
        provenance: String = HubTagProvenance.MANUAL,
        assignedAt: Long = System.currentTimeMillis(),
    ) {
        require(provenance in HubTagProvenance.values)
        if (tagIds.isEmpty()) return
        db.withTransaction {
            val binding = ensureBinding(target, assignedAt)
            val tags = tagIds.distinct().map { id -> requireNotNull(dao.tag(id)) { "Unknown tag: $id" } }
            tags.forEach { requireCompatible(target, it) }
            val categoryIds = (dao.tagsForBinding(binding.id) + tags)
                .filter { it.namespace == HubTagNamespaces.SOLDI_CATEGORY }
                .map { it.id }
                .distinct()
            require(categoryIds.size <= 1) { "Only one primary Soldi category is allowed" }
            dao.assign(tags.map { HubTagAssignment(binding.id, it.id, assignedAt, provenance) })
            dao.refreshUsage()
        }
    }

    suspend fun remove(target: HubEntityRef, tagIds: Collection<String>) {
        val binding = binding(target) ?: return
        if (tagIds.isEmpty()) return
        db.withTransaction {
            dao.bulkUnassign(listOf(binding.id), tagIds.distinct())
            dao.refreshUsage()
        }
    }

    suspend fun clear(target: HubEntityRef) {
        val assigned = tags(target)
        remove(target, assigned.map(HubTagEntity::id))
    }

    suspend fun bulkAdd(targets: Collection<HubEntityRef>, tagIds: Collection<String>, provenance: String = HubTagProvenance.MANUAL) {
        targets.distinct().forEach { assign(it, tagIds, provenance) }
    }

    suspend fun bulkRemove(targets: Collection<HubEntityRef>, tagIds: Collection<String>) {
        val bindingIds = targets.distinct().mapNotNull { binding(it)?.id }
        if (bindingIds.isEmpty() || tagIds.isEmpty()) return
        db.withTransaction {
            dao.bulkUnassign(bindingIds, tagIds.distinct())
            dao.refreshUsage()
        }
    }

    suspend fun copy(from: HubEntityRef, to: HubEntityRef) {
        val source = binding(from) ?: return
        val compatible = dao.tagsForBinding(source.id).filter { runCatching { requireCompatible(to, it) }.isSuccess }
        assign(to, compatible.map(HubTagEntity::id))
    }

    suspend fun tags(target: HubEntityRef): List<HubTagEntity> =
        binding(target)?.let { dao.tagsForBinding(it.id) }.orEmpty()

    suspend fun backlinks(tagId: String): List<HubEntityRef> = dao.assignmentsForTag(tagId).map {
        HubEntityRef(it.moduleId, it.entityKind, it.canonicalId)
    }

    suspend fun rename(tagId: String, name: String) {
        val tag = requireNotNull(dao.tag(tagId))
        val normalized = normalize(name)
        require(normalized.isNotEmpty())
        val collision = dao.tag(tag.namespace, normalized)
        require(collision == null || collision.id == tagId) { "Duplicate tag in ${tag.namespace}" }
        check(dao.rename(tagId, name.trim(), normalized, System.currentTimeMillis()) == 1)
    }

    suspend fun archive(tagId: String, archived: Boolean) {
        check(dao.setArchived(tagId, archived, System.currentTimeMillis()) == 1)
    }

    suspend fun pin(tagId: String, pinned: Boolean) {
        check(dao.setPinned(tagId, pinned, System.currentTimeMillis()) == 1)
    }

    suspend fun deleteUnused(tagId: String): Boolean = dao.deleteUnused(tagId) == 1

    suspend fun addAlias(tagId: String, alias: String) {
        val tag = requireNotNull(dao.tag(tagId))
        val normalized = normalize(alias)
        require(normalized.isNotEmpty())
        dao.addAlias(HubTagAlias(tagId, tag.namespace, alias.trim(), normalized))
    }

    suspend fun merge(sourceId: String, targetId: String) {
        require(sourceId != targetId)
        db.withTransaction {
            val source = requireNotNull(dao.tag(sourceId))
            val target = requireNotNull(dao.tag(targetId))
            require(source.namespace == target.namespace) { "Cross-namespace merge is forbidden" }
            dao.moveAssignments(sourceId, targetId)
            dao.deleteAssignments(sourceId)
            runCatching { dao.addAlias(HubTagAlias(targetId, target.namespace, source.name, source.normalizedName)) }
            dao.moveAliases(sourceId, targetId, target.namespace)
            check(dao.deleteUnused(sourceId) == 1)
            dao.refreshUsage()
        }
    }

    suspend fun addParent(childId: String, parentId: String) {
        require(childId != parentId)
        val child = requireNotNull(dao.tag(childId))
        val parent = requireNotNull(dao.tag(parentId))
        require(child.namespace == parent.namespace)
        require(dao.wouldCreateCycle(childId, parentId) == 0) { "Tag hierarchy cycle" }
        dao.addParent(HubTagParent(childId, parentId))
    }

    suspend fun matches(target: HubEntityRef, filter: HubTagFilter): Boolean {
        val assigned = tags(target).mapTo(mutableSetOf(), HubTagEntity::id)
        if (filter.noTags && assigned.isNotEmpty()) return false
        if (filter.exclude.any(assigned::contains)) return false
        return when {
            filter.include.isEmpty() -> true
            filter.mode == HubTagMatchMode.AND -> assigned.containsAll(filter.include)
            else -> filter.include.any(assigned::contains)
        }
    }

    private suspend fun binding(ref: HubEntityRef) = db.hubContextDao().binding(ref.moduleId, ref.entityKind, ref.canonicalId)

    private suspend fun ensureBinding(ref: HubEntityRef, updatedAt: Long): HubEntityBinding {
        binding(ref)?.let { return it }
        val value = HubEntityBinding(UUID.randomUUID().toString(), ref.moduleId, ref.entityKind, ref.canonicalId, updatedAt = updatedAt)
        runCatching { db.hubContextDao().insertBinding(value) }
        return requireNotNull(binding(ref))
    }

    private fun requireCompatible(target: HubEntityRef, tag: HubTagEntity) {
        require(tag.isGlobal || tag.namespace == namespaceForTarget(target) || (target.moduleId == "soldi" && tag.namespace == HubTagNamespaces.SOLDI_CATEGORY)) {
            "Tag ${tag.id} is not compatible with ${target.moduleId}/${target.entityKind}"
        }
    }

    companion object {
        fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            .lowercase().replace(Regex("\\s+"), " ")

        fun namespaceForModule(moduleId: String): String = when (moduleId) {
            "people" -> HubTagNamespaces.PEOPLE
            "places" -> HubTagNamespaces.PLACES
            "soldi" -> HubTagNamespaces.SOLDI
            "substances" -> HubTagNamespaces.SUBSTANCES
            else -> require(moduleId.startsWith("timer")) { "Unsupported tag module: $moduleId" }.let { HubTagNamespaces.TIMER_NOW }
        }

        fun namespaceForTarget(ref: HubEntityRef): String = if (ref.moduleId != "timer") {
            namespaceForModule(ref.moduleId)
        } else when (ref.entityKind) {
            "quick_event_template", "quick_event_entry", "quick_event_macro" -> HubTagNamespaces.TIMER_EVENTS
            "life_period" -> HubTagNamespaces.TIMER_SINCE_WHEN
            else -> HubTagNamespaces.TIMER_NOW
        }

        private fun requireNamespace(namespace: String) = require(namespace in HubTagNamespaces.all) { "Unknown tag namespace: $namespace" }
        private fun nearDuplicateDistance(value: String) = if (value.length < 6) 1 else 2

        private fun levenshtein(left: String, right: String): Int {
            var previous = IntArray(right.length + 1) { it }
            left.forEachIndexed { leftIndex, leftChar ->
                val current = IntArray(right.length + 1)
                current[0] = leftIndex + 1
                right.forEachIndexed { rightIndex, rightChar ->
                    current[rightIndex + 1] = minOf(
                        current[rightIndex] + 1,
                        previous[rightIndex + 1] + 1,
                        previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
                    )
                }
                previous = current
            }
            return previous.last()
        }
    }
}

internal class HubTagAdapter(context: Context) : HubEntityAdapter {
    private val db = PersonalHubDatabase.get(context)
    override val moduleId = "tags"
    override val entityKind = "tag"
    override val capabilities = setOf("facet", "tag", "search", "create")

    override suspend fun exists(canonicalId: String) = db.hubTagDao().tag(canonicalId) != null
    override suspend fun lifecycle(canonicalId: String) = if (db.hubTagDao().tag(canonicalId)?.archived == true) HubEntityLifecycle.ARCHIVED else HubEntityLifecycle.ACTIVE
    override suspend fun summaries(canonicalIds: Set<String>): Map<String, HubEntitySummary> = canonicalIds.mapNotNull { id ->
        db.hubTagDao().tag(id)?.let { id to it.toHubSummary() }
    }.toMap()

    override suspend fun search(query: String, limit: Int): List<HubEntitySummary> = db.hubTagDao().allTags()
        .filter { !it.archived && (query.isBlank() || it.normalizedName.contains(SharedTagEngine.normalize(query.removePrefix("#")))) }
        .sortedWith(compareByDescending<HubTagEntity> { it.pinned }.thenByDescending { it.usageCount }.thenBy { it.name.lowercase() })
        .take(limit).map { it.toHubSummary() }

    override suspend fun openTarget(canonicalId: String): HubOpenTarget? = HubOpenTarget(
        "personalhub://tags/$canonicalId",
        "com.gernalix.personalhub.HubTagsActivity",
    )

    private fun HubTagEntity.toHubSummary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, id),
        name,
        namespace,
        if (archived) HubEntityLifecycle.ARCHIVED else HubEntityLifecycle.ACTIVE,
        mapOf("facet_type" to "tag", "icon" to (icon ?: "🏷"), "namespace" to namespace, "usage_count" to usageCount.toString()),
    )
}
