package com.supercontacts.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_searches",
    indices = [
        Index(value = ["public_id"], unique = true),
        Index(value = ["created_at"]),
        Index(value = ["updated_at"]),
    ],
)
data class SavedSearchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "public_id")
    val publicId: String,
    val title: String,
    val query: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "saved_search_tags",
    primaryKeys = ["saved_search_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = SavedSearchEntity::class,
            parentColumns = ["id"],
            childColumns = ["saved_search_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["saved_search_id"]),
        Index(value = ["tag_id"]),
    ],
)
data class SavedSearchTagCrossRef(
    @ColumnInfo(name = "saved_search_id")
    val savedSearchId: Long,
    @ColumnInfo(name = "tag_id")
    val tagId: Long,
)
