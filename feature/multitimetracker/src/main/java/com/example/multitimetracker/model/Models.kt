// v471
// v470
package com.example.multitimetracker.model

// CAPSULE-AUDIT [CAP-001]: MODEL uses core contract DTOs (ClosedSessionRecord/TaggedSessionRecord).
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.contracts.TaggedSessionRecord

enum class TimeFenceTrigger { ON_START, ON_STOP }

enum class TimeFenceDelivery { NOTIFICATION, PREFENCE }

enum class TimedTagNotificationType { NONE, NORMAL, ALARM }

enum class TimeFenceScope { ALWAYS, ONE_TIME }

enum class TimeFenceMatchMode { AND, OR }

/**
 * "Time-fence" (geofence temporale): regole che scattano su start/stop di session con certi tag.
 */
data class TimeFenceRule(
    val id: Long,
    val message: String,
    val trigger: TimeFenceTrigger,
    val delivery: TimeFenceDelivery = TimeFenceDelivery.NOTIFICATION,
    val scope: TimeFenceScope,
    val matchMode: TimeFenceMatchMode = TimeFenceMatchMode.AND,
    val tagIds: Set<Long>,
    /** Legacy persisted delay. Timer Alerts ignore it; new and edited rules store zero. */
    val timerMinutes: Int = 0,
    val isEnabled: Boolean = true,
    /** Cooldown anti-spam (ms). */
    val cooldownMs: Long = 0L,
    /** Ultima volta che ha sparato (ms). */
    val lastFiredAtMs: Long? = null,
    val isDeleted: Boolean = false,
    val deletedAtMs: Long? = null
)

data class PreFencePrompt(
    val ruleId: Long,
    val sessionId: Long,
    val sessionTitle: String,
    val message: String,
    val firedAtMs: Long = 0L,
)

data class Task(
    val id: Long,
    val name: String,
    val link: String = "",
    val tagIds: Set<Long>,
    val isDeleted: Boolean = false,
    val deletedAtMs: Long? = null,
    val isRunning: Boolean,
    val totalMs: Long,
    val lastStartedAtMs: Long? // null se in pausa
)

data class Tag(
    val id: Long,
    val name: String,
    val timedDurationMinutes: Int? = null,
    val notificationType: TimedTagNotificationType = TimedTagNotificationType.NONE,
    /**
     * Non-destructive hide: archived tags do not show in pickers or the Tags tab by default,
     * but historical sessions keep their tagIds unchanged.
     */
    val isArchived: Boolean = false,

    /**
     * Timeline-only display flag.
     *
     * If false, the tag is still attached to sessions and fully usable for filtering/analytics,
     * but it will NOT be shown next to sessions in the Timeline list.
     *
     * Default is true for backward compatibility.
     */
    val showInTimeline: Boolean = true,

    val isDeleted: Boolean = false,
    val deletedAtMs: Long? = null,
    /**
     * When a tag is deleted with "Solo tag", we remove the tag from its tasks.
     * This field keeps the list of task IDs that had this tag right before deletion,
     * so that restoring the tag can re-associate it automatically.
     */
    val restoreSessionIds: Set<Long> = emptySet(),
    val activeChildrenCount: Int,
    val totalMs: Long,
    val lastStartedAtMs: Long? // null se non sta correndo
)


data class TaskChainStep(
    val name: String = "",
    val link: String = "",
    val tagIds: Set<Long> = emptySet()
)

data class TaskChain(
    val id: Long,
    val name: String,
    val steps: List<TaskChainStep>,
    val isDeleted: Boolean = false,
    val deletedAtMs: Long? = null
)

data class ActiveChainRun(
    val chainId: Long,
    val stepIndex: Int,
    val currentSessionId: Long
)


const val DEFAULT_LIFE_PERIOD_COLOR_ARGB: Long = 0xFF0B6B6BL

enum class LifePeriodDisplayUnit {
    YEARS,
    MONTHS,
    WEEKS,
    DAYS,
    HOURS,
    MINUTES,
    SECONDS;

    companion object {
        val displayOrder: List<LifePeriodDisplayUnit> = listOf(YEARS, MONTHS, WEEKS, DAYS, HOURS, MINUTES, SECONDS)
    }
}

enum class LifePeriodDurationMode {
    EXACT_DURATION,
    CALENDAR_DAYS
}

data class LifePeriod(
    val id: Long,
    val title: String,
    val description: String = "",
    val startMs: Long,
    val endMs: Long? = null,
    val colorArgb: Long = DEFAULT_LIFE_PERIOD_COLOR_ARGB,
    val tagIds: Set<Long> = emptySet(),
    val displayUnits: Set<LifePeriodDisplayUnit> = setOf(LifePeriodDisplayUnit.DAYS)
)
data class SessionUi(
    val id: Long,
    val title: String,
    val startMs: Long,
    /** Null means running. */
    val endMs: Long?,
    val expectedEndMs: Long? = null,
    val tagIds: Set<Long>,
    val deletedAtMs: Long? = null
)

enum class HomeLoadState {
    Loading,
    ReadyWithData,
    ReadyEmpty,
    Error
}

fun homeLoadStateFor(runningSessions: List<SessionUi>): HomeLoadState =
    if (runningSessions.any { it.endMs == null && it.deletedAtMs == null }) {
        HomeLoadState.ReadyWithData
    } else {
        HomeLoadState.ReadyEmpty
    }

data class QuickEventTemplate(
    val id: Long,
    val title: String,
    val tagIds: Set<Long>,
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
    val deletedAtMs: Long? = null
)

enum class QuickEventFieldType {
    TEXT,
    NUMBER,
    BOOLEAN,
    CHOICE
}

data class QuickEventFieldDefinition(
    val id: Long,
    val templateId: Long,
    val label: String,
    val type: QuickEventFieldType = QuickEventFieldType.TEXT,
    val required: Boolean = false,
    val defaultValue: String = "",
    val choiceOptions: List<String> = emptyList(),
    val displayOrder: Int = 0,
    val deletedAtMs: Long? = null
)

data class QuickEventEntry(
    val id: Long,
    val templateId: Long?,
    val macroId: Long? = null,
    val title: String,
    val timestampMs: Long,
    val tagIds: Set<Long>,
    val deletedAtMs: Long? = null
)

data class QuickEventFieldValue(
    val id: Long,
    val entryId: Long,
    val fieldId: Long,
    val label: String,
    val type: QuickEventFieldType = QuickEventFieldType.TEXT,
    val value: String = "",
    val displayOrder: Int = 0
)

data class QuickEventMacro(
    val id: Long,
    val title: String,
    val tagIds: Set<Long> = emptySet(),
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
    val deletedAtMs: Long? = null
)

data class QuickEventMacroAction(
    val macroId: Long,
    val templateId: Long,
    val displayOrder: Int = 0
)

data class UiState(
    /** Total time spent inside the app UI (foreground only). */
    val appUsageMs: Long = 0L,
    /** When non-null, the app is currently in foreground and this is the start timestamp. */
    val appUsageRunningSinceMs: Long? = null,
    /** Timestamp of app install (or fallback: first tracked data). */
    val installAtMs: Long = 0L,
    val nowMs: Long = 0L,
    val timeMachineTargetMs: Long? = null,
    val isReadOnly: Boolean = false,
    val homeLoadState: HomeLoadState = HomeLoadState.Loading
)
