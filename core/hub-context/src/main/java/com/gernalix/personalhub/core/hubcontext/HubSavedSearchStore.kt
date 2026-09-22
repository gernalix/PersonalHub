package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import com.gernalix.personalhub.core.database.DatabasePreferences
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class HubSavedSearch(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val query: HubSearchQuery,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

/**
 * Canonical saved-search store backed by hub_preferences inside personalhub.db.
 * Feature modules can reuse this instead of introducing module-specific saved-query formats.
 */
class HubSavedSearchStore(context: Context) {
    private val preferences = DatabasePreferences(context.applicationContext, NAMESPACE)

    suspend fun list(): List<HubSavedSearch> = withContext(Dispatchers.IO) {
        decode(preferences.getString(KEY, null))
            .sortedWith(compareByDescending<HubSavedSearch> { it.updatedAt }.thenBy { it.name.lowercase() })
    }

    suspend fun save(value: HubSavedSearch): HubSavedSearch = withContext(Dispatchers.IO) {
        val normalized = value.copy(
            name = value.name.trim(),
            query = value.query.normalized(),
            updatedAt = System.currentTimeMillis(),
        )
        require(normalized.name.isNotBlank())
        val current = decode(preferences.getString(KEY, null)).associateBy(HubSavedSearch::id).toMutableMap()
        val previous = current[normalized.id]
        current[normalized.id] = if (previous == null) normalized else normalized.copy(createdAt = previous.createdAt)
        check(preferences.edit().putString(KEY, encode(current.values)).commit())
        current.getValue(normalized.id)
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val current = decode(preferences.getString(KEY, null)).associateBy(HubSavedSearch::id).toMutableMap()
        if (current.remove(id) == null) return@withContext false
        check(preferences.edit().putString(KEY, encode(current.values)).commit())
        true
    }

    private fun encode(values: Collection<HubSavedSearch>): String =
        JSONArray().apply {
            values.sortedBy(HubSavedSearch::id).forEach { saved ->
                put(
                    JSONObject()
                        .put("id", saved.id)
                        .put("name", saved.name)
                        .put("created_at", saved.createdAt)
                        .put("updated_at", saved.updatedAt)
                        .put("query", encodeQuery(saved.query)),
                )
            }
        }.toString()

    private fun decode(raw: String?): List<HubSavedSearch> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        HubSavedSearch(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            query = decodeQuery(item.getJSONObject("query")),
                            createdAt = item.getLong("created_at"),
                            updatedAt = item.getLong("updated_at"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeQuery(query: HubSearchQuery): JSONObject {
        val normalized = query.normalized()
        return JSONObject()
            .put("text", normalized.text)
            .put("modules", JSONArray(normalized.modules.toList()))
            .put("entity_kinds", JSONArray(normalized.entityKinds.toList()))
            .put("capabilities", JSONArray(normalized.capabilities.toList()))
            .put("include_archived", normalized.includeArchived)
            .put("per_adapter_limit", normalized.perAdapterLimit)
            .put("limit", normalized.limit)
    }

    private fun decodeQuery(json: JSONObject): HubSearchQuery =
        HubSearchQuery(
            text = json.optString("text"),
            modules = json.stringSet("modules"),
            entityKinds = json.stringSet("entity_kinds"),
            capabilities = json.stringSet("capabilities"),
            includeArchived = json.optBoolean("include_archived", false),
            perAdapterLimit = json.optInt("per_adapter_limit", 25).coerceIn(1, 100),
            limit = json.optInt("limit", 100).coerceIn(1, 500),
        ).normalized()

    private fun JSONObject.stringSet(key: String): Set<String> {
        val values = optJSONArray(key) ?: return emptySet()
        return buildSet {
            for (index in 0 until values.length()) {
                values.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    private companion object {
        const val NAMESPACE = "hub_search_v1"
        const val KEY = "saved_queries"
    }
}
