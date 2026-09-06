package com.gernalix.personalhub.contracts.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

object HubResourceKinds {
    const val WEB_URL = "web_url"
    const val ANDROID_URI = "android_uri"
    const val NOTE = "note"
    const val SAF_DOCUMENT = "saf_document"
}

@Entity(tableName = "hub_resources")
data class HubResource(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String?,
    val value: String,
    val persistedPermission: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Dao
interface HubResourceDao {
    @Query("SELECT * FROM hub_resources WHERE id=:id") suspend fun resource(id: String): HubResource?
    @Query("SELECT * FROM hub_resources WHERE id IN (:ids)") suspend fun resources(ids: List<String>): List<HubResource>
    @Query("SELECT * FROM hub_resources WHERE title LIKE '%' || :query || '%' OR value LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<HubResource>
    @Insert suspend fun insert(resource: HubResource)
    @Update suspend fun update(resource: HubResource)
    @Query("DELETE FROM hub_resources WHERE id=:id") suspend fun delete(id: String)
}
