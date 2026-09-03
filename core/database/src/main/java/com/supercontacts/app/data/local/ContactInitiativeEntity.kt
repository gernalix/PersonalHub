package com.supercontacts.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_initiatives",
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
        Index(value = ["timestamp_utc"]),
        Index(value = ["contact_id", "timestamp_utc"]),
    ],
)
data class ContactInitiativeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "contact_id")
    val contactId: Long,
    @ColumnInfo(name = "timestamp_utc")
    val timestampUtc: Long,
    @ColumnInfo(name = "initiative_type")
    val initiativeType: String,
)
