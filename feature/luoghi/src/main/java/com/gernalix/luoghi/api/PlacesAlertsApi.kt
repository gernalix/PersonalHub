package com.gernalix.luoghi.api

import android.content.Context
import com.gernalix.luoghi.LuoghiAppContainer
import com.gernalix.personalhub.core.alerts.AlertDomain
import com.gernalix.personalhub.core.alerts.AlertMatchMode
import com.gernalix.personalhub.core.alerts.AlertScope
import com.gernalix.personalhub.core.alerts.AlertTargetKind
import com.gernalix.personalhub.core.alerts.AlertTrigger
import com.gernalix.personalhub.core.alerts.ManagedAlertCatalog
import com.gernalix.personalhub.core.alerts.ManagedAlertDraft
import com.gernalix.personalhub.core.alerts.ManagedAlertOption
import com.gernalix.personalhub.core.alerts.ManagedAlertProvider
import com.gernalix.personalhub.core.alerts.ManagedAlertRule
import com.gernalix.personalhub.core.alerts.PlaceAlertDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Public host boundary for Places alert management. */
class PlacesAlertsApi(context: Context) : ManagedAlertProvider {
    private val container = LuoghiAppContainer(context.applicationContext)

    override val domain: AlertDomain = AlertDomain.PLACE

    override suspend fun load(): ManagedAlertCatalog = withContext(Dispatchers.IO) {
        val rules = container.alerts.listRules()
        val targets = container.alerts.placeTagTargets(rules.map { it.id })
        val tags = container.places.listTags()
            .filterNot { it.archived }
            .sortedBy { it.name.lowercase() }
        val places = container.places.places.first()
            .filterNot { it.archived }
            .sortedBy { it.displayName().lowercase() }
        val tagLabels = tags.associate { it.id to it.name }
        val placeLabels = places.associate { it.uuid to it.displayName() }
        ManagedAlertCatalog(
            domain = domain,
            rules = rules.filter { it.deletedAt == null }.mapNotNull { rule ->
                val trigger = runCatching { AlertTrigger.valueOf(rule.trigger) }.getOrNull() ?: return@mapNotNull null
                val targetKind = runCatching { AlertTargetKind.valueOf(rule.targetKind) }.getOrNull() ?: return@mapNotNull null
                val matchMode = runCatching { AlertMatchMode.valueOf(rule.matchMode) }.getOrDefault(AlertMatchMode.ALL)
                val scope = runCatching { AlertScope.valueOf(rule.scope) }.getOrDefault(AlertScope.ALWAYS)
                val tagIds = targets[rule.id].orEmpty()
                ManagedAlertRule(
                    id = rule.id,
                    domain = domain,
                    message = rule.message,
                    trigger = trigger,
                    targetKind = targetKind,
                    entityId = rule.entityId,
                    entityLabel = rule.entityId?.let(placeLabels::get),
                    tagIds = tagIds,
                    tagLabels = tagIds.mapNotNull(tagLabels::get),
                    matchMode = matchMode,
                    scope = scope,
                    enabled = rule.enabled,
                    cooldownMs = rule.cooldownMs,
                )
            },
            tags = tags.map { ManagedAlertOption(it.id, it.name) },
            entities = places.map { ManagedAlertOption(it.uuid, it.displayName()) },
        )
    }

    override suspend fun create(draft: ManagedAlertDraft): Boolean = withContext(Dispatchers.IO) {
        val placeDraft = draft.toPlaceDraft() ?: return@withContext false
        runCatching { container.alerts.create(placeDraft) }.isSuccess
    }

    override suspend fun update(ruleId: String, draft: ManagedAlertDraft): Boolean = withContext(Dispatchers.IO) {
        val placeDraft = draft.toPlaceDraft() ?: return@withContext false
        runCatching { container.alerts.update(ruleId, placeDraft) }.getOrDefault(false)
    }

    override suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        container.alerts.setEnabled(ruleId, enabled)
    }

    override suspend fun delete(ruleId: String): Boolean = withContext(Dispatchers.IO) {
        container.alerts.delete(ruleId)
    }

    private fun ManagedAlertDraft.toPlaceDraft(): PlaceAlertDraft? {
        if (domain != AlertDomain.PLACE || message.trim().isEmpty()) return null
        if (trigger !in setOf(AlertTrigger.PLACE_CHECK_IN, AlertTrigger.PLACE_CHECK_OUT, AlertTrigger.PLACE_BOTH)) {
            return null
        }
        when (targetKind) {
            AlertTargetKind.ENTITY -> if (entityId.isNullOrBlank()) return null
            AlertTargetKind.TAGS -> if (tagIds.isEmpty()) return null
        }
        return PlaceAlertDraft(
            message = message,
            trigger = trigger,
            targetKind = targetKind,
            placeId = entityId,
            placeTagIds = tagIds,
            matchMode = matchMode,
            scope = scope,
            cooldownMs = cooldownMs,
        )
    }

    private fun com.gernalix.luoghi.data.PlaceEntity.displayName(): String =
        nickname.ifBlank { address.orEmpty().ifBlank { "Unnamed place" } }
}
