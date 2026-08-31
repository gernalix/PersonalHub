package com.supercontacts.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_messaging_links",
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
        Index(value = ["contact_id", "platform", "normalized_phone"], unique = true),
        Index(value = ["platform"]),
        Index(value = ["normalized_phone"]),
    ],
)
data class ContactMessagingLinkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "contact_id")
    val contactId: Long,
    val platform: String,
    @ColumnInfo(name = "normalized_phone")
    val normalizedPhone: String,
    @ColumnInfo(name = "deep_link")
    val deepLink: String,
    @ColumnInfo(name = "generation_status")
    val generationStatus: String,
    @ColumnInfo(name = "verification_status")
    val verificationStatus: String,
    @ColumnInfo(name = "last_scan_at")
    val lastScanAt: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
