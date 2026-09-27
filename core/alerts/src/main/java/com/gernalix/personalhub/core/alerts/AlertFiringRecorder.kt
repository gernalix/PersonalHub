package com.gernalix.personalhub.core.alerts

import android.content.Context
import com.gernalix.personalhub.alerts.AlertFiringEntity
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.util.UUID

enum class AlertDeliveryKind(val code: String) {
    NOTIFICATION("notification"),
    IN_APP_PROMPT("in_app_prompt"),
}

object AlertFiringRecorder {
    suspend fun recordSuccessfulDelivery(
        context: Context,
        fire: AlertFire,
        entityLabel: String?,
        delivery: AlertDeliveryKind,
        keepEnabled: Boolean,
        database: PersonalHubDatabase = PersonalHubDatabase.get(context.applicationContext),
    ) {
        database.alertDao().recordSuccessfulDelivery(
            firing = AlertFiringEntity(
                id = UUID.randomUUID().toString(),
                ruleId = fire.ruleId,
                domain = fire.domain.moduleId,
                trigger = fire.trigger.name,
                entityId = fire.entityId,
                entityLabel = entityLabel?.trim()?.takeIf(String::isNotEmpty),
                tagNames = fire.tagNames.sorted().joinToString(", "),
                delivery = delivery.code,
                message = fire.message,
                firedAt = fire.firedAtMs,
            ),
            keepEnabled = keepEnabled,
        )
    }
}
