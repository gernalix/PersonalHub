// v470
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
 * System capsule host: single place where feature capsules are constructed and wired.
 *
 * This is the "systemic" part of FCS: ownership + boundaries are explicit, and capsules
 * receive ONLY contracts (interfaces), never MainViewModel state directly.
 */
class SystemCapsuleHost(
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
    val alerts: AlertsCapsuleViewModel by lazy {
        AlertsCapsuleViewModel(
        hostStateFlow = alertsAccess.hostStateFlow(),
        runtimeScope = runtimeScope,
        getContext = { alertsAccess.appContextOrNull() },
        resolveSessionStartAtMs = { sessionId, nowMs -> alertsAccess.resolveSessionStartAtMs(sessionId, nowMs) },
        getTags = { alertsAccess.tags() },
        getRunningSessions = { alertsAccess.runningSessions() },
        persist = { alertsAccess.persist() },
        persistAsync = { alertsAccess.persistAsync() },
        scheduleAutoBackup = { alertsAccess.scheduleAutoBackup() },
        logUserEvent = { a, et, eid, s, p, u -> alertsAccess.logUserEvent(a, et, eid, s, p, u) },
        logSystemEvent = { a, et, eid, s, p -> alertsAccess.logSystemEvent(a, et, eid, s, p) }
        )
    }

    val sessionOwner: SessionOwnerCapsuleViewModel by lazy {
        SessionOwnerCapsuleViewModel(
            access = sessionOwnerAccess
        )
    }

    val now: NowCapsuleViewModel by lazy {
        NowCapsuleViewModel(
            access = nowAccess,
            sessionOwner = sessionOwner
        )
    }

    val quickEvents: QuickEventsCapsuleViewModel by lazy {
        QuickEventsCapsuleViewModel(
            access = quickEventsAccess
        )
    }

    val sinceWhen: SinceWhenCapsuleViewModel by lazy {
        SinceWhenCapsuleViewModel(
            access = sinceWhenAccess
        )
    }

    val tags: TagsCapsuleViewModel by lazy {
        TagsCapsuleViewModel(
            access = tagsAccess,
            sessionsRuntime = sessionOwner,
        )
    }

    val timeline: TimelineCapsuleViewModel by lazy {
        TimelineCapsuleViewModel(
            access = timelineAccess,
            sessionOwner = sessionOwner
        )
    }

    val chains: ChainsCapsuleViewModel by lazy {
        ChainsCapsuleViewModel(
            access = chainsAccess
        )
    }

}
