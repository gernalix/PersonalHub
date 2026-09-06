package com.supercontacts.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_fields",
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
        Index(value = ["field_type", "value"]),
        Index(value = ["field_type", "normalized_value"]),
        Index(value = ["field_type", "sort_value"]),
        Index(value = ["contact_id", "field_type", "position"]),
    ],
)
data class ContactFieldEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "contact_id")
    val contactId: Long,
    @ColumnInfo(name = "field_type")
    val fieldType: String,
    val value: String,
    @ColumnInfo(name = "normalized_value")
    val normalizedValue: String? = null,
    @ColumnInfo(name = "sort_value")
    val sortValue: String? = null,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
    @ColumnInfo(name = "edited_at")
    val editedAt: Long? = null,
    val position: Int = 0,
    @ColumnInfo(name = "is_primary")
    val isPrimary: Boolean = false,
    val source: String? = "manual",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @ColumnInfo(name = "country_code")
    val countryCode: String? = null,
    val description: String? = null,
)
