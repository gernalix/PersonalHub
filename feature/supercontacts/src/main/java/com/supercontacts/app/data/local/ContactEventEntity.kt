package com.supercontacts.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_events",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["contact_id"]),
        Index(value = ["event_type"]),
        Index(value = ["occurred_at"]),
        Index(value = ["event_type", "occurred_at"]),
        Index(value = ["contact_id", "event_type", "occurred_at"]),
    ],
)
data class ContactEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "contact_id")
    val contactId: Long,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "action_type")
    val actionType: String,
    @ColumnInfo(name = "event_type")
    val eventType: String = "",
    @ColumnInfo(name = "field_type")
    val fieldType: String? = null,
    @ColumnInfo(name = "old_value")
    val oldValue: String? = null,
    @ColumnInfo(name = "new_value")
    val newValue: String? = null,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String? = null,
)
