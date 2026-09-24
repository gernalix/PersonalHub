package com.gernalix.personalhub.contracts.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A timestamp snapshot. Source fields are descriptive links and never cascade to the source row. */
@Entity(
    tableName = "since_when_counters",
    indices = [
        Index(value = ["source_entity_type", "source_entity_id", "initial_timestamp"], unique = true),
        Index("initial_timestamp"),
        Index("created_at"),
    ],
)
data class SinceWhenCounterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    @ColumnInfo(name = "initial_timestamp") val initialTimestamp: Long,
    @ColumnInfo(name = "end_timestamp") val endTimestamp: Long? = null,
    @ColumnInfo(name = "color_argb") val colorArgb: Long = 0xFF168A83L,
    @ColumnInfo(name = "display_units_json") val displayUnitsJson: String = "[\"DAYS\"]",
    @ColumnInfo(name = "legacy_tag_ids_json") val legacyTagIdsJson: String = "[]",
    @ColumnInfo(name = "source_entity_type") val sourceEntityType: String? = null,
    @ColumnInfo(name = "source_entity_id") val sourceEntityId: String? = null,
    @ColumnInfo(name = "source_timestamp_field") val sourceTimestampField: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
