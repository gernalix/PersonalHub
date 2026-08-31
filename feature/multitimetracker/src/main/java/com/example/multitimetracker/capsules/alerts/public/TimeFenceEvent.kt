package com.example.multitimetracker.capsules.alerts.public

import com.example.multitimetracker.model.TimeFenceTrigger

/**
 * Lightweight event emitted by the session runtime to be evaluated by the Alerts capsule.
 * This keeps alert logic isolated from the rest of the app.
 */
data class TimeFenceEvent(
    val trigger: TimeFenceTrigger,
    val sessionId: Long,
    val sessionTitle: String,
    val sessionTagIds: Set<Long>
)
