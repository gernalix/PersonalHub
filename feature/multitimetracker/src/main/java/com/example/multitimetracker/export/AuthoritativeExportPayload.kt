// v471
package com.example.multitimetracker.export

import com.example.multitimetracker.failLegacyTaskPath
import com.example.multitimetracker.capsules.chains.public.ChainsSnapshot
import com.example.multitimetracker.capsules.quickevents.public.QuickEventsSnapshot
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.session.SessionCore
import com.example.multitimetracker.model.ActiveChainRun
import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TaskChain
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.persistence.SnapshotStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class AuthoritativeExportPayload(
    val tasks: List<Task>,
    val tags: List<Tag>,
    val closedSessions: List<ClosedSessionRecord>,
    val tagSessions: List<TaggedSessionRecord>,
    val tagParentsByChild: Map<Long, Set<Long>>,
    val lifePeriods: List<LifePeriod>,
    val timeFenceRules: List<TimeFenceRule>,
    val chains: List<TaskChain>,
    val activeChainRun: ActiveChainRun?,
    val quickEventTemplates: List<QuickEventTemplate>,
    val quickEventEntries: List<QuickEventEntry>,
    val quickEventFieldDefinitions: List<QuickEventFieldDefinition>,
    val quickEventFieldValues: List<QuickEventFieldValue>,
    val quickEventMacros: List<QuickEventMacro>,
    val quickEventMacroActions: List<QuickEventMacroAction>,
    val appUsageMs: Long,
)

internal fun buildSessionOnlyRuntimeTasks(
    runningSessions: List<SessionUi>,
): List<Task> {
    return runningSessions.asSequence()
        .filter { it.deletedAtMs == null && it.endMs == null }
        .map { session ->
            Task(
                id = session.id,
                name = session.title,
                tagIds = session.tagIds,
                link = "",
                isRunning = true,
                totalMs = 0L,
                lastStartedAtMs = session.startMs,
            )
        }
        .toList()
}

internal object AuthoritativeExportPayloadBuilder {

    private enum class SignatureMode {
        FULL,
        ACTIVATION,
    }

    fun fromLegacyState(
        state: UiState,
        closedSessions: List<ClosedSessionRecord>,
        tagSessions: List<TaggedSessionRecord>,
    ): AuthoritativeExportPayload {
        failLegacyTaskPath()
    }

    fun fromSessionTables(
        tags: List<Tag>,
        appUsageMs: Long,
        sessionCore: SessionCore,
        lifePeriods: List<LifePeriod> = emptyList(),
        timeFenceRules: List<TimeFenceRule> = emptyList(),
        quickEvents: QuickEventsSnapshot = QuickEventsSnapshot.EMPTY,
        chains: ChainsSnapshot = ChainsSnapshot.EMPTY,
        tagParentsByChild: Map<Long, Set<Long>> = emptyMap(),
    ): AuthoritativeExportPayload {
        val tagNameById = tags.associateBy({ it.id }, { it.name })
        val allSessions = sessionCore.readAllSessions()
        val runningSessions = sessionCore.readRunningSessions()

        val closedSessionRows = allSessions.asSequence()
            .filter { it.deletedAtMs == null && it.endMs != null }
            .toList()

        val closedSessions = closedSessionRows.map { session ->
            ClosedSessionRecord(
                sessionId = session.id,
                sessionTitle = session.title,
                startTs = session.startMs,
                endTs = session.endMs ?: session.startMs,
            )
        }

        val tagSessions = ArrayList<TaggedSessionRecord>(closedSessionRows.size * 2)
        for (session in closedSessionRows) {
            val endMs = session.endMs ?: continue
            for (tagId in session.tagIds) {
                tagSessions.add(
                    TaggedSessionRecord(
                        tagId = tagId,
                        tagName = tagNameById[tagId] ?: "tag_$tagId",
                        sessionId = session.id,
                        sessionTitle = session.title,
                        startTs = session.startMs,
                        endTs = endMs,
                    )
                )
            }
        }

        return AuthoritativeExportPayload(
            tasks = buildSessionOnlyRuntimeTasks(runningSessions),
            tags = tags,
            closedSessions = closedSessions,
            tagSessions = tagSessions,
            tagParentsByChild = tagParentsByChild,
            lifePeriods = lifePeriods,
            timeFenceRules = timeFenceRules,
            chains = chains.chains,
            activeChainRun = chains.activeChainRun,
            quickEventTemplates = quickEvents.templates,
            quickEventEntries = quickEvents.entries,
            quickEventFieldDefinitions = quickEvents.fieldDefinitions,
            quickEventFieldValues = quickEvents.fieldValues,
            quickEventMacros = quickEvents.macros,
            quickEventMacroActions = quickEvents.macroActions,
            appUsageMs = appUsageMs,
        )
    }

    fun fromSnapshot(snapshot: SnapshotStore.Snapshot): AuthoritativeExportPayload = AuthoritativeExportPayload(
        tasks = snapshot.tasks,
        tags = snapshot.tags,
        closedSessions = snapshot.closedSessions,
        tagSessions = snapshot.tagSessions,
        tagParentsByChild = snapshot.tagParents
            .groupBy { it.childId }
            .mapValues { (_, edges) -> edges.map { it.parentId }.toSet() },
        lifePeriods = snapshot.lifePeriods,
        timeFenceRules = snapshot.timeFenceRules,
        chains = snapshot.chains,
        activeChainRun = snapshot.activeChainRun,
        quickEventTemplates = snapshot.quickEventTemplates,
        quickEventEntries = snapshot.quickEventEntries,
        quickEventFieldDefinitions = snapshot.quickEventFieldDefinitions,
        quickEventFieldValues = snapshot.quickEventFieldValues,
        quickEventMacros = snapshot.quickEventMacros,
        quickEventMacroActions = snapshot.quickEventMacroActions,
        appUsageMs = snapshot.appUsageMs,
    )

    fun signature(payload: AuthoritativeExportPayload): String =
        signature(payload, SignatureMode.FULL)

    fun activationSignature(payload: AuthoritativeExportPayload): String =
        signature(payload, SignatureMode.ACTIVATION)

    private fun signature(payload: AuthoritativeExportPayload, mode: SignatureMode): String {
        val digest = MessageDigest.getInstance("SHA-256")

        payload.tasks
            .sortedBy { it.id }
            .forEach { task ->
                digest.updateToken("task")
                digest.updateLong(task.id)
                digest.updateToken(task.name)
                digest.updateToken(task.link)
                digest.updateToken(task.tagIds.toList().sorted().joinToString(","))
                digest.updateBoolean(task.isDeleted)
                digest.updateLong(task.deletedAtMs ?: 0L)
                digest.updateBoolean(task.isRunning)
                if (mode == SignatureMode.FULL) {
                    digest.updateLong(task.totalMs)
                    digest.updateLong(task.lastStartedAtMs ?: 0L)
                }
            }

        payload.tags
            .sortedBy { it.id }
            .forEach { tag ->
                digest.updateToken("tag")
                digest.updateLong(tag.id)
                digest.updateToken(tag.name)
                digest.updateBoolean(tag.isArchived)
                digest.updateBoolean(tag.showInTimeline)
                digest.updateBoolean(tag.isDeleted)
                digest.updateLong(tag.deletedAtMs ?: 0L)
                digest.updateToken(tag.restoreSessionIds.toList().sorted().joinToString(","))
                if (mode == SignatureMode.FULL) {
                    digest.updateLong(tag.totalMs)
                    digest.updateLong(tag.lastStartedAtMs ?: 0L)
                }
            }

        payload.closedSessions
            .sortedWith(compareBy<ClosedSessionRecord>({ it.sessionId }, { it.startTs }, { it.endTs }, { it.sessionTitle }))
            .forEach { session ->
                digest.updateToken("task_session")
                digest.updateLong(session.sessionId)
                digest.updateToken(session.sessionTitle)
                digest.updateLong(session.startTs)
                digest.updateLong(session.endTs)
            }

        payload.tagSessions
            .sortedWith(
                compareBy<TaggedSessionRecord>(
                    { it.tagId },
                    { it.sessionId },
                    { it.startTs },
                    { it.endTs },
                    { it.tagName },
                    { it.sessionTitle },
                )
            )
            .forEach { session ->
                digest.updateToken("tag_session")
                digest.updateLong(session.tagId)
                digest.updateToken(session.tagName)
                digest.updateLong(session.sessionId)
                digest.updateToken(session.sessionTitle)
                digest.updateLong(session.startTs)
                digest.updateLong(session.endTs)
            }

        payload.tagParentsByChild
            .toSortedMap()
            .forEach { (childId, parents) ->
                digest.updateToken("tag_parent")
                digest.updateLong(childId)
                digest.updateToken(parents.toList().sorted().joinToString(","))
            }

        payload.lifePeriods
            .sortedBy { it.id }
            .forEach { period ->
                digest.updateToken("life_period")
                digest.updateLong(period.id)
                digest.updateToken(period.title)
                digest.updateToken(period.description)
                digest.updateLong(period.startMs)
                digest.updateLong(period.endMs ?: 0L)
                digest.updateLong(period.colorArgb)
                digest.updateToken(period.tagIds.toList().sorted().joinToString(","))
                digest.updateToken(period.displayUnits.map { it.name }.sorted().joinToString(","))
            }

        payload.quickEventTemplates
            .sortedBy { it.id }
            .forEach { template ->
                digest.updateToken("quick_event_template")
                digest.updateLong(template.id)
                digest.updateToken(template.title)
                digest.updateToken(template.tagIds.toList().sorted().joinToString(","))
                digest.updateLong(template.sortOrder.toLong())
                digest.updateBoolean(template.isArchived)
                digest.updateLong(template.deletedAtMs ?: 0L)
            }

        payload.quickEventFieldDefinitions
            .sortedBy { it.id }
            .forEach { field ->
                digest.updateToken("quick_event_field")
                digest.updateLong(field.id)
                digest.updateLong(field.templateId)
                digest.updateToken(field.label)
                digest.updateToken(field.type.name)
                digest.updateBoolean(field.required)
                digest.updateToken(field.defaultValue)
                digest.updateToken(field.choiceOptions.joinToString("|"))
                digest.updateInt(field.displayOrder)
                digest.updateLong(field.deletedAtMs ?: 0L)
            }

        payload.quickEventEntries
            .sortedBy { it.id }
            .forEach { entry ->
                digest.updateToken("quick_event_entry")
                digest.updateLong(entry.id)
                digest.updateLong(entry.templateId ?: 0L)
                digest.updateLong(entry.macroId ?: 0L)
                digest.updateToken(entry.title)
                digest.updateLong(entry.timestampMs)
                digest.updateToken(entry.tagIds.toList().sorted().joinToString(","))
                digest.updateLong(entry.deletedAtMs ?: 0L)
            }

        payload.quickEventFieldValues
            .sortedBy { it.id }
            .forEach { value ->
                digest.updateToken("quick_event_field_value")
                digest.updateLong(value.id)
                digest.updateLong(value.entryId)
                digest.updateLong(value.fieldId)
                digest.updateToken(value.label)
                digest.updateToken(value.type.name)
                digest.updateToken(value.value)
                digest.updateInt(value.displayOrder)
            }

        payload.quickEventMacros
            .sortedBy { it.id }
            .forEach { macro ->
                digest.updateToken("quick_event_macro")
                digest.updateLong(macro.id)
                digest.updateToken(macro.title)
                digest.updateToken(macro.tagIds.toList().sorted().joinToString(","))
                digest.updateInt(macro.sortOrder)
                digest.updateBoolean(macro.isArchived)
                digest.updateLong(macro.deletedAtMs ?: 0L)
            }

        payload.quickEventMacroActions
            .sortedWith(compareBy({ it.macroId }, { it.displayOrder }, { it.templateId }))
            .forEach { action ->
                digest.updateToken("quick_event_macro_action")
                digest.updateLong(action.macroId)
                digest.updateLong(action.templateId)
                digest.updateInt(action.displayOrder)
            }

        payload.timeFenceRules
            .sortedBy { it.id }
            .forEach { rule ->
                digest.updateToken("time_fence")
                digest.updateLong(rule.id)
                digest.updateToken(rule.message)
                digest.updateToken(rule.trigger.name)
                digest.updateToken(rule.delivery.name)
                digest.updateToken(rule.scope.name)
                digest.updateToken(rule.matchMode.name)
                digest.updateToken(rule.tagIds.toList().sorted().joinToString(","))
                digest.updateLong(rule.timerMinutes.toLong())
                digest.updateBoolean(rule.isEnabled)
                digest.updateLong(rule.cooldownMs)
                digest.updateLong(rule.lastFiredAtMs ?: 0L)
                digest.updateBoolean(rule.isDeleted)
                digest.updateLong(rule.deletedAtMs ?: 0L)
            }

        payload.chains
            .sortedBy { it.id }
            .forEach { chain ->
                digest.updateToken("chain")
                digest.updateLong(chain.id)
                digest.updateToken(chain.name)
                digest.updateBoolean(chain.isDeleted)
                digest.updateLong(chain.deletedAtMs ?: 0L)
                chain.steps.forEach { step ->
                    digest.updateToken("chain_step")
                    digest.updateToken(step.name)
                    digest.updateToken(step.link)
                    digest.updateToken(step.tagIds.toList().sorted().joinToString(","))
                }
            }

        payload.activeChainRun?.let { run ->
            digest.updateToken("active_chain")
            digest.updateLong(run.chainId)
            digest.updateInt(run.stepIndex)
            digest.updateLong(run.currentSessionId)
        }

        digest.updateToken("app_usage")
        digest.updateLong(payload.appUsageMs)

        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun MessageDigest.updateToken(value: String) {
        update(value.toByteArray(StandardCharsets.UTF_8))
        update(0)
    }

    private fun MessageDigest.updateLong(value: Long) {
        updateToken(value.toString())
    }

    private fun MessageDigest.updateInt(value: Int) {
        updateToken(value.toString())
    }

    private fun MessageDigest.updateBoolean(value: Boolean) {
        updateToken(if (value) "1" else "0")
    }
}
