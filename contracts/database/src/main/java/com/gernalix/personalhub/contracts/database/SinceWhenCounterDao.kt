package com.gernalix.personalhub.contracts.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SinceWhenCounterDao {
    @Query("SELECT * FROM since_when_counters ORDER BY initial_timestamp DESC, id DESC")
    fun observeAll(): Flow<List<SinceWhenCounterEntity>>

    @Query("SELECT * FROM since_when_counters ORDER BY initial_timestamp DESC, id DESC")
    suspend fun all(): List<SinceWhenCounterEntity>

    @Query("SELECT * FROM since_when_counters WHERE id = :id")
    suspend fun get(id: Long): SinceWhenCounterEntity?

    @Query("SELECT * FROM since_when_counters WHERE source_entity_type = :type AND source_entity_id = :sourceId AND initial_timestamp = :timestamp LIMIT 1")
    suspend fun findSourceSnapshot(type: String, sourceId: String, timestamp: Long): SinceWhenCounterEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(counter: SinceWhenCounterEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLegacy(counter: SinceWhenCounterEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM since_when_migration_state WHERE `key` = :key)")
    suspend fun migrationComplete(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markMigrationComplete(state: SinceWhenMigrationState)

    @Update
    suspend fun update(counter: SinceWhenCounterEntity)

    @Delete
    suspend fun delete(counter: SinceWhenCounterEntity)
}
