package com.gernalix.personalhub.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Global generation marker for the canonical database export pipeline. */
@Entity(tableName = "hub_generation")
data class HubGeneration(@PrimaryKey val id: Int = 1, val generation: Long = 0)
