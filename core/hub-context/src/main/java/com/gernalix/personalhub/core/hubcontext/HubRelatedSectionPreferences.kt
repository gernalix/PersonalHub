package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.database.DatabasePreferences
import org.json.JSONArray
import org.json.JSONObject

data class HubRelatedSection(val moduleId: String, val entityKind: String, val visible: Boolean = true) {
    val key get() = "$moduleId/$entityKind"
}

class HubRelatedSectionPreferences(context: Context, owner: HubEntityRef) {
    private val preferences = DatabasePreferences(context, "hub_related_${owner.moduleId}_${owner.entityKind}")

    fun load(defaults: List<HubRelatedSection>): List<HubRelatedSection> {
        val byKey = defaults.associateBy { it.key }
        val stored = preferences.getString("sections", null)?.let(::JSONArray) ?: return defaults
        val loaded = (0 until stored.length()).mapNotNull { index ->
            val value = stored.getJSONObject(index)
            byKey[value.getString("key")]?.copy(visible = value.optBoolean("visible", true))
        }
        return loaded + defaults.filter { fallback -> loaded.none { it.key == fallback.key } }
    }

    fun save(sections: List<HubRelatedSection>) {
        val json = JSONArray().apply { sections.forEach { put(JSONObject().put("key", it.key).put("visible", it.visible)) } }
        check(preferences.edit().putString("sections", json.toString()).commit())
    }
}
