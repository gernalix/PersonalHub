package com.gernalix.personalhub.contracts.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "since_when_migration_state")
data class SinceWhenMigrationState(
    @PrimaryKey val key: String,
    val completedAt: Long,
)
