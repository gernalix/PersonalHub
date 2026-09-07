package com.example.multitimetracker.core.quickevent

import com.example.multitimetracker.model.QuickEventDefaults
import com.example.multitimetracker.model.QuickEventFieldDefinition
import org.json.JSONArray
import org.json.JSONObject

sealed class QuickEventTarget {
    data class Template(val templateId: Long) : QuickEventTarget()
    data class Macro(val macroId: Long) : QuickEventTarget()
}

sealed class QuickEventExecutionResult {
    data class Executed(val title: String, val entryIds: List<Long>) : QuickEventExecutionResult()
    data class NeedsInput(val target: QuickEventTarget) : QuickEventExecutionResult()
    data class Unavailable(val reason: Reason) : QuickEventExecutionResult() {
        enum class Reason { MISSING, ARCHIVED_OR_DELETED, EMPTY_MACRO, ACTION_MISSING }
    }
}

class QuickEventExecutor(
    private val core: QuickEventCore,
    private val audit: (action: String, entityType: String, entityId: Long, summary: String, payload: JSONObject) -> Unit,
    private val afterSuccessfulWrite: () -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    fun execute(target: QuickEventTarget, timestampMs: Long = nowMs()): QuickEventExecutionResult =
        when (target) {
            is QuickEventTarget.Template -> executeTemplate(target.templateId, macroId = null, timestampMs = timestampMs)
            is QuickEventTarget.Macro -> executeMacro(target.macroId, timestampMs)
        }

    private fun executeTemplate(templateId: Long, macroId: Long?, timestampMs: Long): QuickEventExecutionResult {
        val template = core.readTemplateById(templateId)
            ?: return QuickEventExecutionResult.Unavailable(QuickEventExecutionResult.Unavailable.Reason.MISSING)
        if (template.deletedAtMs != null || template.isArchived) {
            return QuickEventExecutionResult.Unavailable(QuickEventExecutionResult.Unavailable.Reason.ARCHIVED_OR_DELETED)
        }
        val fields = core.readFieldDefinitionsForTemplate(templateId)
        if (QuickEventDefaults.requiredFieldsMissingDefaults(fields)) {
            return QuickEventExecutionResult.NeedsInput(QuickEventTarget.Template(templateId))
        }
        val entryId = insertTemplateEntry(templateId, macroId, template.title, timestampMs, template.tagIds, fields)
        afterSuccessfulWrite()
        return QuickEventExecutionResult.Executed(template.title, listOf(entryId))
    }

    private fun executeMacro(macroId: Long, timestampMs: Long): QuickEventExecutionResult {
        val macro = core.readMacroById(macroId)
            ?: return QuickEventExecutionResult.Unavailable(QuickEventExecutionResult.Unavailable.Reason.MISSING)
        if (macro.deletedAtMs != null || macro.isArchived) {
            return QuickEventExecutionResult.Unavailable(QuickEventExecutionResult.Unavailable.Reason.ARCHIVED_OR_DELETED)
        }
        val actions = core.readMacroActionsForMacro(macroId).sortedBy { it.displayOrder }
        if (actions.isEmpty()) {
            return QuickEventExecutionResult.Unavailable(QuickEventExecutionResult.Unavailable.Reason.EMPTY_MACRO)
        }
        val templateIds = actions.map { it.templateId }.toSet()
        val templatesById = core.readTemplatesByIds(templateIds).associateBy { it.id }
        val fieldsByTemplate = core.readFieldDefinitionsForTemplates(templateIds).groupBy { it.templateId }
        if (actions.any { templatesById[it.templateId] == null }) {
            return QuickEventExecutionResult.Unavailable(QuickEventExecutionResult.Unavailable.Reason.ACTION_MISSING)
        }
        if (actions.any { action -> templatesById[action.templateId]?.let { it.deletedAtMs != null || it.isArchived } == true }) {
            return QuickEventExecutionResult.Unavailable(QuickEventExecutionResult.Unavailable.Reason.ARCHIVED_OR_DELETED)
        }
        if (actions.any { QuickEventDefaults.requiredFieldsMissingDefaults(fieldsByTemplate[it.templateId].orEmpty()) }) {
            return QuickEventExecutionResult.NeedsInput(QuickEventTarget.Macro(macroId))
        }
        val entryIds = actions.map { action ->
            val template = requireNotNull(templatesById[action.templateId])
            insertTemplateEntry(
                templateId = template.id,
                macroId = macroId,
                title = template.title,
                timestampMs = timestampMs,
                tagIds = template.tagIds + macro.tagIds,
                fields = fieldsByTemplate[template.id].orEmpty()
            )
        }
        audit(
            "QUICK_EVENT_MACRO_RUN",
            "QUICK_EVENT_MACRO",
            macroId,
            macro.title.ifBlank { macroId.toString() },
            JSONObject().put("macroId", macroId).put("entries", entryIds.size).put("timestampMs", timestampMs)
        )
        afterSuccessfulWrite()
        return QuickEventExecutionResult.Executed(macro.title, entryIds)
    }

    private fun insertTemplateEntry(
        templateId: Long,
        macroId: Long?,
        title: String,
        timestampMs: Long,
        tagIds: Set<Long>,
        fields: List<QuickEventFieldDefinition>
    ): Long {
        val entryId = core.insertEntry(
            templateId = templateId,
            macroId = macroId,
            title = title,
            timestampMs = timestampMs,
            tagIds = tagIds,
            fieldValues = QuickEventDefaults.defaultValuesFor(0L, fields)
        )
        audit(
            "QUICK_EVENT_ENTRY_CREATE",
            "QUICK_EVENT_ENTRY",
            entryId,
            title.ifBlank { entryId.toString() },
            JSONObject()
                .put("entryId", entryId)
                .put("templateId", templateId)
                .put("macroId", macroId ?: JSONObject.NULL)
                .put("timestampMs", timestampMs)
                .put("tagIds", JSONArray(tagIds.toList().sorted()))
        )
        return entryId
    }
}
