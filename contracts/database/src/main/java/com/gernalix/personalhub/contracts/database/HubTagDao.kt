package com.gernalix.personalhub.contracts.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HubTagDao {
    @Query("SELECT * FROM hub_tags WHERE id=:id LIMIT 1")
    suspend fun tag(id: String): HubTagEntity?

    @Query("SELECT * FROM hub_tags WHERE namespace=:namespace AND normalized_name=:normalized LIMIT 1")
    suspend fun tag(namespace: String, normalized: String): HubTagEntity?

    @Query("SELECT t.* FROM hub_tags t LEFT JOIN hub_tag_aliases a ON a.tag_id=t.id WHERE (t.namespace=:namespace OR t.is_global=1) AND t.archived=0 AND (t.normalized_name LIKE :pattern OR a.normalized_alias LIKE :pattern) GROUP BY t.id ORDER BY t.pinned DESC,t.usage_count DESC,t.last_used_at DESC,t.name COLLATE NOCASE LIMIT :limit")
    suspend fun search(namespace: String, pattern: String, limit: Int): List<HubTagEntity>

    @Query("SELECT * FROM hub_tags WHERE namespace=:namespace OR is_global=1 ORDER BY archived,pinned DESC,usage_count DESC,last_used_at DESC,name COLLATE NOCASE")
    fun observe(namespace: String): Flow<List<HubTagEntity>>

    @Query("SELECT * FROM hub_tags ORDER BY namespace,name COLLATE NOCASE")
    suspend fun allTags(): List<HubTagEntity>

    @Upsert suspend fun upsert(value: HubTagEntity)
    @Upsert suspend fun upsert(values: List<HubTagEntity>)

    @Query("UPDATE hub_tags SET name=:name,normalized_name=:normalized,updated_at=:updatedAt WHERE id=:id")
    suspend fun rename(id: String, name: String, normalized: String, updatedAt: Long): Int

    @Query("UPDATE hub_tags SET archived=:archived,updated_at=:updatedAt WHERE id=:id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: Long): Int

    @Query("UPDATE hub_tags SET pinned=:pinned,updated_at=:updatedAt WHERE id=:id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM hub_tags WHERE id=:id AND NOT EXISTS (SELECT 1 FROM hub_tag_assignments WHERE tag_id=:id)")
    suspend fun deleteUnused(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun addAlias(value: HubTagAlias)
    @Query("SELECT * FROM hub_tag_aliases WHERE tag_id=:tagId ORDER BY alias COLLATE NOCASE")
    suspend fun aliases(tagId: String): List<HubTagAlias>
    @Query("DELETE FROM hub_tag_aliases WHERE tag_id=:tagId AND normalized_alias=:normalized")
    suspend fun removeAlias(tagId: String, normalized: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun assign(value: HubTagAssignment): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun assign(values: List<HubTagAssignment>): List<Long>
    @Query("DELETE FROM hub_tag_assignments WHERE target_binding_id=:targetBindingId AND tag_id=:tagId")
    suspend fun unassign(targetBindingId: String, tagId: String): Int
    @Query("DELETE FROM hub_tag_assignments WHERE target_binding_id IN (:targetBindingIds) AND tag_id IN (:tagIds)")
    suspend fun bulkUnassign(targetBindingIds: List<String>, tagIds: List<String>): Int

    @Query("SELECT t.* FROM hub_tags t JOIN hub_tag_assignments a ON a.tag_id=t.id WHERE a.target_binding_id=:bindingId ORDER BY t.namespace,t.name COLLATE NOCASE")
    suspend fun tagsForBinding(bindingId: String): List<HubTagEntity>

    @Query("SELECT a.target_binding_id,a.tag_id,a.assigned_at,a.provenance,b.module_id,b.entity_kind,b.canonical_id FROM hub_tag_assignments a JOIN hub_entity_bindings b ON b.id=a.target_binding_id WHERE a.tag_id=:tagId ORDER BY a.assigned_at DESC")
    suspend fun assignmentsForTag(tagId: String): List<HubTagAssignmentView>

    @Query("SELECT a.target_binding_id,a.tag_id,a.assigned_at,a.provenance,b.module_id,b.entity_kind,b.canonical_id FROM hub_tag_assignments a JOIN hub_entity_bindings b ON b.id=a.target_binding_id JOIN hub_tags t ON t.id=a.tag_id WHERE t.namespace=:namespace OR t.is_global=1 ORDER BY a.assigned_at DESC")
    fun observeAssignments(namespace: String): Flow<List<HubTagAssignmentView>>

    @Query("SELECT tag_id,count(*) AS count,max(assigned_at) AS last_used_at FROM hub_tag_assignments GROUP BY tag_id")
    suspend fun usage(): List<HubTagUsage>

    @Query("UPDATE hub_tags SET usage_count=(SELECT count(*) FROM hub_tag_assignments WHERE tag_id=hub_tags.id),last_used_at=(SELECT max(assigned_at) FROM hub_tag_assignments WHERE tag_id=hub_tags.id) WHERE usage_count IS NOT (SELECT count(*) FROM hub_tag_assignments WHERE tag_id=hub_tags.id) OR last_used_at IS NOT (SELECT max(assigned_at) FROM hub_tag_assignments WHERE tag_id=hub_tags.id)")
    suspend fun refreshUsage()

    @Query("UPDATE OR IGNORE hub_tag_assignments SET tag_id=:targetId WHERE tag_id=:sourceId")
    suspend fun moveAssignments(sourceId: String, targetId: String): Int
    @Query("DELETE FROM hub_tag_assignments WHERE tag_id=:tagId")
    suspend fun deleteAssignments(tagId: String): Int
    @Query("UPDATE OR IGNORE hub_tag_aliases SET tag_id=:targetId,namespace=:namespace WHERE tag_id=:sourceId")
    suspend fun moveAliases(sourceId: String, targetId: String, namespace: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun addParent(value: HubTagParent)
    @Query("WITH RECURSIVE ancestors(id) AS (SELECT parent_tag_id FROM hub_tag_parents WHERE child_tag_id=:parentId UNION ALL SELECT p.parent_tag_id FROM hub_tag_parents p JOIN ancestors a ON p.child_tag_id=a.id) SELECT count(*) FROM ancestors WHERE id=:childId")
    suspend fun wouldCreateCycle(childId: String, parentId: String): Int

    @Upsert suspend fun saveFilter(value: HubSavedTagFilter)
    @Query("SELECT * FROM hub_saved_tag_filters WHERE namespace=:namespace ORDER BY updated_at DESC,name COLLATE NOCASE")
    fun observeFilters(namespace: String): Flow<List<HubSavedTagFilter>>

    @Query("SELECT * FROM hub_saved_tag_filters WHERE id=:id LIMIT 1")
    suspend fun filter(id: String): HubSavedTagFilter?

    @Query("DELETE FROM hub_saved_tag_filters WHERE id=:id")
    suspend fun deleteFilter(id: String): Int
}
