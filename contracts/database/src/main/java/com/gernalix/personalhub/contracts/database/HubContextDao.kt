package com.gernalix.personalhub.contracts.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HubContextDao {
    @Query("SELECT * FROM hub_entity_bindings WHERE module_id=:moduleId AND entity_kind=:entityKind AND canonical_id=:canonicalId LIMIT 1")
    suspend fun binding(moduleId: String, entityKind: String, canonicalId: String): HubEntityBinding?

    @Query("SELECT * FROM hub_entity_bindings WHERE id=:id LIMIT 1")
    suspend fun binding(id: String): HubEntityBinding?

    @Query("SELECT * FROM hub_entity_bindings WHERE id IN (:ids)")
    suspend fun bindings(ids: List<String>): List<HubEntityBinding>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBinding(value: HubEntityBinding)

    @Query("UPDATE hub_entity_bindings SET lifecycle=:lifecycle, updated_at=:updatedAt WHERE id=:id")
    suspend fun setLifecycle(id: String, lifecycle: String, updatedAt: String): Int

    @Query("SELECT count(*) FROM hub_context_members WHERE entity_id=:entityId")
    suspend fun membershipCount(entityId: String): Int

    @Query("DELETE FROM hub_entity_bindings WHERE id=:id")
    suspend fun deleteBinding(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertContext(value: HubContext)

    @Query("SELECT * FROM hub_contexts WHERE id=:contextId LIMIT 1")
    suspend fun context(contextId: String): HubContext?

    @Query("UPDATE hub_contexts SET context_type_id=:typeId,title=:title,updated_at=:updatedAt WHERE id=:contextId")
    suspend fun updateContext(contextId: String, typeId: String?, title: String?, updatedAt: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMembers(values: List<HubContextMember>)

    @Query("DELETE FROM hub_context_members WHERE context_id=:contextId")
    suspend fun deleteMembers(contextId: String)

    @Query("SELECT count(*) FROM hub_context_members WHERE context_id=:contextId")
    suspend fun memberCount(contextId: String): Int

    @Query("DELETE FROM hub_context_members WHERE context_id=:contextId AND entity_id=:entityId AND role=:role")
    suspend fun removeMember(contextId: String, entityId: String, role: String): Int

    @Query("DELETE FROM hub_contexts WHERE id=:contextId")
    suspend fun deleteContext(contextId: String): Int

    @Upsert
    suspend fun upsertType(value: HubContextType)

    @Query("SELECT * FROM hub_context_types ORDER BY name COLLATE NOCASE,id")
    suspend fun types(): List<HubContextType>

    @Query("SELECT * FROM hub_context_types WHERE id=:typeId LIMIT 1")
    suspend fun type(typeId: String): HubContextType?

    @Query("SELECT * FROM hub_context_type_fields WHERE context_type_id=:typeId ORDER BY position")
    suspend fun typeFields(typeId: String): List<HubContextTypeField>

    @Query("DELETE FROM hub_context_types WHERE id=:typeId")
    suspend fun deleteType(typeId: String): Int

    @Query("DELETE FROM hub_context_type_fields WHERE context_type_id=:typeId")
    suspend fun deleteTypeFields(typeId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTypeFields(values: List<HubContextTypeField>)

    @Query("SELECT * FROM hub_contexts WHERE id IN (SELECT context_id FROM hub_context_members WHERE entity_id=:entityId) ORDER BY updated_at DESC")
    suspend fun contextsForEntity(entityId: String): List<HubContext>

    @Query("SELECT * FROM hub_contexts WHERE context_type_id=:typeId AND id IN (SELECT context_id FROM hub_context_members WHERE entity_id=:entityId) ORDER BY updated_at DESC")
    suspend fun contextsForEntityAndType(entityId: String, typeId: String): List<HubContext>

    @Query("SELECT * FROM hub_context_members WHERE context_id=:contextId ORDER BY position,role,entity_id")
    suspend fun members(contextId: String): List<HubContextMember>

    @Query("SELECT * FROM hub_context_members WHERE context_id IN (:contextIds) ORDER BY context_id,position,role,entity_id")
    suspend fun membersForContexts(contextIds: List<String>): List<HubContextMember>

    @Query("SELECT id FROM hub_contexts ORDER BY id")
    fun observeContextIds(): Flow<List<String>>

    @Query("SELECT * FROM hub_contexts WHERE context_type_id=:typeId ORDER BY updated_at DESC")
    suspend fun contextsByType(typeId: String): List<HubContext>

    @Query("SELECT * FROM hub_contexts WHERE trim(coalesce(title,'')) != '' ORDER BY updated_at DESC,id")
    suspend fun titledContexts(): List<HubContext>

    @Query("SELECT c.* FROM hub_contexts c JOIN hub_context_members m ON m.context_id=c.id WHERE m.entity_id IN (:entityIds) GROUP BY c.id HAVING count(DISTINCT m.entity_id)=:scopeSize ORDER BY c.updated_at DESC")
    suspend fun contextsContainingAll(entityIds: List<String>, scopeSize: Int): List<HubContext>

    @Query("SELECT DISTINCT b.* FROM hub_entity_bindings b JOIN hub_context_members candidate ON candidate.entity_id=b.id WHERE candidate.entity_id NOT IN (:entityIds) AND candidate.context_id IN (SELECT scoped.context_id FROM hub_context_members scoped WHERE scoped.entity_id IN (:entityIds) GROUP BY scoped.context_id HAVING count(DISTINCT scoped.entity_id)=:scopeSize) ORDER BY b.module_id,b.entity_kind,b.canonical_id")
    suspend fun relatedToAll(entityIds: List<String>, scopeSize: Int): List<HubEntityBinding>

    @Query("SELECT b.module_id,b.entity_kind,count(DISTINCT b.id) AS count FROM hub_entity_bindings b JOIN hub_context_members candidate ON candidate.entity_id=b.id WHERE candidate.entity_id NOT IN (:entityIds) AND candidate.context_id IN (SELECT scoped.context_id FROM hub_context_members scoped WHERE scoped.entity_id IN (:entityIds) GROUP BY scoped.context_id HAVING count(DISTINCT scoped.entity_id)=:scopeSize) GROUP BY b.module_id,b.entity_kind ORDER BY b.module_id,b.entity_kind")
    suspend fun facetsRelatedToAll(entityIds: List<String>, scopeSize: Int): List<HubEntityFacet>

    @Query("SELECT candidate.entity_id AS entity_id,count(DISTINCT candidate.context_id) AS context_count FROM hub_context_members candidate WHERE candidate.entity_id NOT IN (:entityIds) AND candidate.context_id IN (SELECT scoped.context_id FROM hub_context_members scoped WHERE scoped.entity_id IN (:entityIds) GROUP BY scoped.context_id HAVING count(DISTINCT scoped.entity_id)=:scopeSize) GROUP BY candidate.entity_id ORDER BY context_count DESC,candidate.entity_id LIMIT :limit OFFSET :offset")
    suspend fun candidatesRelatedToAll(entityIds: List<String>, scopeSize: Int, limit: Int, offset: Int): List<HubCandidateCount>
}
