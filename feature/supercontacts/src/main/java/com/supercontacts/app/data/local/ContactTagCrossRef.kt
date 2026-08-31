package com.supercontacts.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "contact_tags",
    primaryKeys = ["contact_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contact_id"],
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
        Index(value = ["tag_id"]),
        Index(value = ["contact_id"]),
    ],
)
data class ContactTagCrossRef(
    @ColumnInfo(name = "contact_id")
    val contactId: Long,
    @ColumnInfo(name = "tag_id")
    val tagId: Long,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
)
