package com.gernalix.luoghi.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Places tags are a dedicated namespace. They intentionally do not reuse Timer tags.
 */
@Entity(
    tableName = "place_tags",
    indices = [
        Index(value = ["normalized_name"], unique = true),
        Index("name"),
        Index("updated_at"),
    ],
)
data class PlaceTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "place_tag_cross_ref",
    primaryKeys = ["place_uuid", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["place_uuid"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlaceTagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("place_uuid"), Index("tag_id")],
)
data class PlaceTagCrossRef(
    @ColumnInfo(name = "place_uuid") val placeUuid: String,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)
