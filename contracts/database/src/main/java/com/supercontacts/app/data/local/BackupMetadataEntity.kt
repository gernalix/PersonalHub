package com.supercontacts.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "backup_metadata")
data class BackupMetadataEntity(
    @PrimaryKey
    val id: Int = 1,
    @ColumnInfo(name = "app_id")
    val appId: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "backup_format_version")
    val backupFormatVersion: Int,
    @ColumnInfo(name = "exported_at")
    val exportedAt: Long,
)
