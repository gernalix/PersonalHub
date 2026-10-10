package com.gernalix.personalhub.contracts.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Globally scoped opaque identity. Local keys remain implementation details. */
data class CanonicalEntityRef(val entityKind: String, val canonicalId: String) {
    init { require(entityKind.isNotBlank() && canonicalId.isNotBlank()) }
}

@Entity(tableName = "hub_entities", indices = [Index(value = ["owning_module", "local_table", "local_key"], unique = true)])
data class HubCanonicalEntity(
    @PrimaryKey @ColumnInfo(name = "canonical_id") val canonicalId: String,
    @ColumnInfo(name = "entity_kind") val entityKind: String,
    @ColumnInfo(name = "owning_module") val owningModule: String,
    @ColumnInfo(name = "local_table") val localTable: String,
    @ColumnInfo(name = "local_key") val localKey: String,
    val lifecycle: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "hub_external_identities", foreignKeys = [ForeignKey(entity = HubCanonicalEntity::class, parentColumns = ["canonical_id"], childColumns = ["canonical_id"], onDelete = ForeignKey.RESTRICT)], indices = [Index("canonical_id"), Index(value = ["system", "source_scope", "external_id"], unique = true)])
data class HubExternalIdentity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    @ColumnInfo(name = "canonical_id") val canonicalId: String,
    @ColumnInfo(name = "entity_kind") val entityKind: String,
    val system: String,
    @ColumnInfo(name = "source_scope") val sourceScope: String,
    @ColumnInfo(name = "external_id") val externalId: String,
    @ColumnInfo(name = "external_id_normalized") val externalIdNormalized: String? = null,
    @ColumnInfo(name = "link_method") val linkMethod: String,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long? = null,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long? = null,
    @ColumnInfo(name = "verified_at") val verifiedAt: Long? = null,
    val lifecycle: String = "ACTIVE",
    @ColumnInfo(name = "metadata_json") val metadataJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "hub_entity_aliases", foreignKeys = [ForeignKey(entity = HubCanonicalEntity::class, parentColumns = ["canonical_id"], childColumns = ["canonical_id"], onDelete = ForeignKey.RESTRICT)], indices = [Index("canonical_id")])
data class HubEntityAlias(
    @PrimaryKey @ColumnInfo(name = "alias_canonical_id") val aliasCanonicalId: String,
    @ColumnInfo(name = "canonical_id") val canonicalId: String,
    val reason: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
