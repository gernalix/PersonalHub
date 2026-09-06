package com.gernalix.personalhub.contracts.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

object HubEntityLifecycle {
    const val ACTIVE = "ACTIVE"
    const val ARCHIVED = "ARCHIVED"
    const val DELETED = "DELETED"
    val values = setOf(ACTIVE, ARCHIVED, DELETED)
}

@Entity(
    tableName = "hub_entity_bindings",
    primaryKeys = ["id"],
    indices = [Index(value = ["module_id", "entity_kind", "canonical_id"], unique = true), Index("lifecycle")],
)
data class HubEntityBinding(
    val id: String,
    @ColumnInfo(name = "module_id") val moduleId: String,
    @ColumnInfo(name = "entity_kind") val entityKind: String,
    @ColumnInfo(name = "canonical_id") val canonicalId: String,
    val lifecycle: String = HubEntityLifecycle.ACTIVE,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(
    tableName = "hub_context_types",
    primaryKeys = ["id"],
    indices = [Index(value = ["name"], unique = true)],
)
data class HubContextType(
    val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    val locked: Boolean = false,
)

@Entity(
    tableName = "hub_context_type_fields",
    primaryKeys = ["context_type_id", "field_id"],
    foreignKeys = [ForeignKey(
        entity = HubContextType::class,
        parentColumns = ["id"],
        childColumns = ["context_type_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["context_type_id", "position"], unique = true), Index("accepted_module_id", "accepted_entity_kind")],
)
data class HubContextTypeField(
    @ColumnInfo(name = "context_type_id") val contextTypeId: String,
    @ColumnInfo(name = "field_id") val fieldId: String,
    val position: Int,
    val label: String,
    val role: String = "",
    @ColumnInfo(name = "accepted_module_id") val acceptedModuleId: String? = null,
    @ColumnInfo(name = "accepted_entity_kind") val acceptedEntityKind: String? = null,
    @ColumnInfo(name = "accepted_capability") val acceptedCapability: String? = null,
    @ColumnInfo(name = "min_cardinality") val minCardinality: Int = 0,
    @ColumnInfo(name = "max_cardinality") val maxCardinality: Int? = null,
)

@Entity(
    tableName = "hub_contexts",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(
        entity = HubContextType::class,
        parentColumns = ["id"],
        childColumns = ["context_type_id"],
        onDelete = ForeignKey.SET_NULL,
    )],
    indices = [Index("context_type_id"), Index("updated_at")],
)
data class HubContext(
    val id: String,
    @ColumnInfo(name = "context_type_id") val contextTypeId: String? = null,
    val title: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(
    tableName = "hub_context_members",
    primaryKeys = ["context_id", "entity_id", "role"],
    foreignKeys = [
        ForeignKey(entity = HubContext::class, parentColumns = ["id"], childColumns = ["context_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = HubEntityBinding::class, parentColumns = ["id"], childColumns = ["entity_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("entity_id"), Index("context_id", "role"), Index("entity_id", "context_id")],
)
data class HubContextMember(
    @ColumnInfo(name = "context_id") val contextId: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val role: String = "",
    val position: Int = 0,
)

data class HubEntityFacet(
    @ColumnInfo(name = "module_id") val moduleId: String,
    @ColumnInfo(name = "entity_kind") val entityKind: String,
    val count: Int,
)

data class HubCandidateCount(
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "context_count") val contextCount: Int,
)
