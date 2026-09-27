package com.gernalix.personalhub.core.alerts

import android.content.Context
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.personalhub.alerts.AlertRuleEntity
import com.gernalix.personalhub.contracts.database.HubTagEntity
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.core.database.PersonalHubDatabase

data class UnifiedPlaceAlertsSnapshot(
    val rules: List<AlertRuleEntity> = emptyList(),
    val targets: Map<String, Set<String>> = emptyMap(),
    val places: List<PlaceEntity> = emptyList(),
    val tags: List<HubTagEntity> = emptyList(),
)

/** Thin read/write facade over the existing Places rules and tag-target tables. */
class UnifiedPlaceAlertsProvider(
    context: Context,
    private val database: PersonalHubDatabase = PersonalHubDatabase.get(context.applicationContext),
) {
    private val repository = PlaceAlertRepository(context.applicationContext, database)

    suspend fun snapshot(): UnifiedPlaceAlertsSnapshot {
        val rules = repository.listRules().filter { it.deletedAt == null }
        return UnifiedPlaceAlertsSnapshot(
            rules = rules,
            targets = repository.targets(rules.map { it.id }),
            places = database.placeDao().listPlaces(),
            tags = database.hubTagDao().allTags().filter {
                !it.archived && (it.namespace == HubTagNamespaces.PLACES || it.isGlobal)
            },
        )
    }

    suspend fun save(id: String?, draft: PlaceAlertDraft, enabled: Boolean): Boolean {
        val next = draft.copy(enabled = enabled)
        if (id == null) repository.create(next) else if (!repository.update(id, next)) return false
        return true
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean = repository.setEnabled(id, enabled)
    suspend fun delete(id: String): Boolean = repository.delete(id)
}
