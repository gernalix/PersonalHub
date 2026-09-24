package com.gernalix.personalhub.contracts.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

object HubTagNamespaces {
    const val PEOPLE = "people"
    const val PLACES = "places"
    const val SOLDI = "soldi"
    const val SOLDI_CATEGORY = "soldi.category"
    const val SUBSTANCES = "substances"
    const val TIMER_NOW = "timer.now"
    const val TIMER_EVENTS = "timer.events"
    const val SINCE_WHEN = "since_when"
    @Deprecated("Since When is a PersonalHub domain")
    const val TIMER_SINCE_WHEN = SINCE_WHEN
    const val GLOBAL = "global"

    val local = setOf(PEOPLE, PLACES, SOLDI, SOLDI_CATEGORY, SUBSTANCES, TIMER_NOW, TIMER_EVENTS, SINCE_WHEN)
    val all = local + GLOBAL
}

object HubTagKinds {
    const val FREE = "FREE"
    const val CATEGORY = "CATEGORY"
    val values = setOf(FREE, CATEGORY)
}

object HubTagProvenance {
    const val MANUAL = "manual"
    const val SUGGESTED = "suggested"
    const val AUTOMATIC = "automatic"
    val values = setOf(MANUAL, SUGGESTED, AUTOMATIC)
}

@Entity(
    tableName = "hub_tags",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["namespace", "normalized_name"], unique = true),
        Index("namespace"),
        Index("archived"),
        Index("pinned"),
        Index("last_used_at"),
    ],
)
data class HubTagEntity(
    val id: String,
    val namespace: String,
    val kind: String = HubTagKinds.FREE,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    @ColumnInfo(name = "is_global") val isGlobal: Boolean = false,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    @ColumnInfo(name = "usage_count") val usageCount: Long = 0,
    @ColumnInfo(name = "metadata_json") val metadataJson: String? = null,
)

@Entity(
    tableName = "hub_tag_aliases",
    primaryKeys = ["tag_id", "normalized_alias"],
    foreignKeys = [ForeignKey(
        entity = HubTagEntity::class,
        parentColumns = ["id"],
        childColumns = ["tag_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tag_id"), Index(value = ["namespace", "normalized_alias"], unique = true)],
)
data class HubTagAlias(
    @ColumnInfo(name = "tag_id") val tagId: String,
    val namespace: String,
    val alias: String,
    @ColumnInfo(name = "normalized_alias") val normalizedAlias: String,
)

@Entity(
    tableName = "hub_tag_assignments",
    primaryKeys = ["target_binding_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = HubEntityBinding::class,
            parentColumns = ["id"],
            childColumns = ["target_binding_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = HubTagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("target_binding_id"), Index("tag_id"), Index("assigned_at"), Index("provenance")],
)
data class HubTagAssignment(
    @ColumnInfo(name = "target_binding_id") val targetBindingId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
    @ColumnInfo(name = "assigned_at") val assignedAt: Long,
    val provenance: String = HubTagProvenance.MANUAL,
)

@Entity(
    tableName = "hub_tag_parents",
    primaryKeys = ["child_tag_id", "parent_tag_id"],
    foreignKeys = [
        ForeignKey(entity = HubTagEntity::class, parentColumns = ["id"], childColumns = ["child_tag_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = HubTagEntity::class, parentColumns = ["id"], childColumns = ["parent_tag_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("child_tag_id"), Index("parent_tag_id")],
)
data class HubTagParent(
    @ColumnInfo(name = "child_tag_id") val childTagId: String,
    @ColumnInfo(name = "parent_tag_id") val parentTagId: String,
)

@Entity(
    tableName = "hub_saved_tag_filters",
    primaryKeys = ["id"],
    indices = [Index(value = ["namespace", "name"], unique = true), Index("updated_at")],
)
data class HubSavedTagFilter(
    val id: String,
    val namespace: String,
    val name: String,
    @ColumnInfo(name = "query_json") val queryJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

data class HubTagUsage(
    @ColumnInfo(name = "tag_id") val tagId: String,
    val count: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long?,
)

data class HubTagAssignmentView(
    @ColumnInfo(name = "target_binding_id") val targetBindingId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
    @ColumnInfo(name = "assigned_at") val assignedAt: Long,
    val provenance: String,
    @ColumnInfo(name = "module_id") val moduleId: String,
    @ColumnInfo(name = "entity_kind") val entityKind: String,
    @ColumnInfo(name = "canonical_id") val canonicalId: String,
)
