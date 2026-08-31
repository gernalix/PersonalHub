// v471
package com.example.multitimetracker.export

import android.content.Context
import com.example.multitimetracker.model.ActiveChainRun
import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TaskChain
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import androidx.documentfile.provider.DocumentFile

/**
 * Adapter layer that converts the capsule-friendly "snapshot" representation
 * (lists stored as List<Any>) into strongly-typed structures required by exporters.
 *
 * Rationale:
 * - Import/Export capsule must not depend on the full ViewModel state types.
 * - Exporters *should* remain strongly typed.
 */
object SnapshotExportAdapter {

    private inline fun <reified T : Any> castList(name: String, list: List<Any>): List<T> {
        val out = list.filterIsInstance<T>()
        if (out.size != list.size) {
            val badCount = list.size - out.size
            throw IllegalStateException("Invalid snapshot list '$name' ($badCount unexpected item(s))")
        }
        return out
    }

    fun exportAllToDirectoryFromSnapshot(
        context: Context,
        dir: DocumentFile,
        tasks: List<Any>,
        tags: List<Any>,
        closedSessions: List<Any>,
        tagSessions: List<Any>,
        tagParentsByChild: Map<Long, Set<Long>>,
        lifePeriods: List<Any>,
        timeFenceRules: List<Any>,
        chains: List<Any>,
        activeChainRun: Any?,
        appUsageMs: Long,
        quickEventTemplates: List<Any> = emptyList(),
        quickEventEntries: List<Any> = emptyList(),
        quickEventFieldDefinitions: List<Any> = emptyList(),
        quickEventFieldValues: List<Any> = emptyList(),
        quickEventMacros: List<Any> = emptyList(),
        quickEventMacroActions: List<Any> = emptyList()
    ) {
        val typedTasks = castList<Task>("tasks", tasks)
        val typedTags = castList<Tag>("tags", tags)
        val typedClosedSessionRecords = castList<ClosedSessionRecord>("closedSessions", closedSessions)
        val typedTaggedSessionRecords = castList<TaggedSessionRecord>("tagSessions", tagSessions)
        val typedLifePeriods = castList<LifePeriod>("lifePeriods", lifePeriods)
        val typedRules = castList<TimeFenceRule>("timeFenceRules", timeFenceRules)
        val typedChains = castList<TaskChain>("chains", chains)
        val typedQuickEventTemplates = castList<QuickEventTemplate>("quickEventTemplates", quickEventTemplates)
        val typedQuickEventEntries = castList<QuickEventEntry>("quickEventEntries", quickEventEntries)
        val typedQuickEventFieldDefinitions = castList<QuickEventFieldDefinition>("quickEventFieldDefinitions", quickEventFieldDefinitions)
        val typedQuickEventFieldValues = castList<QuickEventFieldValue>("quickEventFieldValues", quickEventFieldValues)
        val typedQuickEventMacros = castList<QuickEventMacro>("quickEventMacros", quickEventMacros)
        val typedQuickEventMacroActions = castList<QuickEventMacroAction>("quickEventMacroActions", quickEventMacroActions)

        val typedActiveRun = when (activeChainRun) {
            null -> null
            is ActiveChainRun -> activeChainRun
            else -> throw IllegalStateException("Invalid snapshot field 'activeChainRun' (unexpected type)")
        }

        CsvExporter.exportAllToDirectory(
            context = context,
            dir = dir,
            tasks = typedTasks,
            tags = typedTags,
            closedSessions = typedClosedSessionRecords,
            tagSessions = typedTaggedSessionRecords,
            tagParentsByChild = tagParentsByChild,
            lifePeriods = typedLifePeriods,
            timeFenceRules = typedRules,
            chains = typedChains,
            activeChainRun = typedActiveRun,
            appUsageMs = appUsageMs,
            quickEventTemplates = typedQuickEventTemplates,
            quickEventEntries = typedQuickEventEntries,
            quickEventFieldDefinitions = typedQuickEventFieldDefinitions,
            quickEventFieldValues = typedQuickEventFieldValues,
            quickEventMacros = typedQuickEventMacros,
            quickEventMacroActions = typedQuickEventMacroActions
        )
    }
}

