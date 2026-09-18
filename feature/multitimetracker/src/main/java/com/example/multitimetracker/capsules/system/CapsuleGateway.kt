// v462
package com.example.multitimetracker.capsules.system

import com.example.multitimetracker.capsules.alerts.controller.AlertsCapsuleViewModel
import com.example.multitimetracker.capsules.chains.controller.ChainsCapsuleViewModel
import com.example.multitimetracker.capsules.now.controller.NowCapsuleViewModel
import com.example.multitimetracker.capsules.quickevents.controller.QuickEventsCapsuleViewModel
import com.example.multitimetracker.capsules.sessions.controller.SessionOwnerCapsuleViewModel
import com.example.multitimetracker.capsules.sincewhen.controller.SinceWhenCapsuleViewModel
import com.example.multitimetracker.capsules.tags.controller.TagsCapsuleViewModel
import com.example.multitimetracker.capsules.timeline.controller.TimelineCapsuleViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * Capsule Gateway (architectural lock).
 *
 * This is the single, explicit entry point to obtain capsule ViewModels.
 *
 * Rationale:
 * - MainViewModel/Root UI should not instantiate capsules ad-hoc.
 * - All capsule construction and wiring stays in one place.
 * - This file intentionally stays tiny: it enforces *structure* without forcing
 *   a risky rewrite of feature logic.
 */
class CapsuleGateway(
    runtimeScope: CoroutineScope,
    alertsAccess: AlertsCapsuleAccess,
    chainsAccess: ChainsCapsuleAccess,
    nowAccess: NowCapsuleAccess,
    quickEventsAccess: QuickEventsCapsuleAccess,
    sessionOwnerAccess: SessionOwnerCapsuleAccess,
    sinceWhenAccess: SinceWhenCapsuleAccess,
    tagsAccess: TagsCapsuleAccess,
    timelineAccess: TimelineCapsuleAccess
) {
    private val host: SystemCapsuleHost = SystemCapsuleHost(
        runtimeScope = runtimeScope,
        alertsAccess = alertsAccess,
        chainsAccess = chainsAccess,
        nowAccess = nowAccess,
        quickEventsAccess = quickEventsAccess,
        sessionOwnerAccess = sessionOwnerAccess,
        sinceWhenAccess = sinceWhenAccess,
        tagsAccess = tagsAccess,
        timelineAccess = timelineAccess
    )

    val alerts: AlertsCapsuleViewModel get() = host.alerts
    val now: NowCapsuleViewModel get() = host.now
    val quickEvents: QuickEventsCapsuleViewModel get() = host.quickEvents
    val sessionOwner: SessionOwnerCapsuleViewModel get() = host.sessionOwner
    val sinceWhen: SinceWhenCapsuleViewModel get() = host.sinceWhen
    val tags: TagsCapsuleViewModel get() = host.tags
    val timeline: TimelineCapsuleViewModel get() = host.timeline
    val chains: ChainsCapsuleViewModel get() = host.chains
}
