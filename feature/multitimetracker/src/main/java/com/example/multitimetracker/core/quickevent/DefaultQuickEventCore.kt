package com.example.multitimetracker.core.quickevent

import android.content.Context
import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate
import com.example.multitimetracker.persistence.QuickEventRepository

@OptIn(com.example.multitimetracker.util.CapsuleWriteApi::class)
class DefaultQuickEventCore(private val context: Context) : QuickEventCore {
    private val repo: QuickEventRepository by lazy(LazyThreadSafetyMode.NONE) {
        QuickEventRepository(context)
    }

    override fun readSnapshot(): QuickEventRepository.Snapshot = repo.readSnapshot()

    override fun readScreenSnapshot(entryLimit: Int): QuickEventRepository.Snapshot =
        repo.readScreenSnapshot(entryLimit = entryLimit)

    override fun readTemplateById(templateId: Long): QuickEventTemplate? = repo.readTemplateById(templateId)

    override fun readEntryById(entryId: Long): QuickEventEntry? = repo.readEntryById(entryId)

    override fun readMacroById(macroId: Long): QuickEventMacro? = repo.readMacroById(macroId)

    override fun readTemplatesByIds(templateIds: Set<Long>): List<QuickEventTemplate> =
        repo.readTemplatesByIds(templateIds)

    override fun readFieldDefinitionsForTemplate(templateId: Long): List<QuickEventFieldDefinition> =
        repo.readFieldDefinitionsForTemplate(templateId)

    override fun readFieldDefinitionsForTemplates(templateIds: Set<Long>): List<QuickEventFieldDefinition> =
        repo.readFieldDefinitionsForTemplates(templateIds)

    override fun readMacroActionsForMacro(macroId: Long): List<QuickEventMacroAction> =
        repo.readMacroActionsForMacro(macroId)

    override fun insertTemplate(
        title: String,
        tagIds: Set<Long>,
        sortOrder: Int,
        isArchived: Boolean,
        fields: List<QuickEventFieldDefinition>
    ): Long =
        repo.insertTemplate(title = title, tagIds = tagIds, sortOrder = sortOrder, isArchived = isArchived, fields = fields)

    override fun updateTemplate(
        templateId: Long,
        title: String,
        tagIds: Set<Long>,
        sortOrder: Int,
        isArchived: Boolean,
        fields: List<QuickEventFieldDefinition>
    ) {
        repo.updateTemplate(templateId = templateId, title = title, tagIds = tagIds, sortOrder = sortOrder, isArchived = isArchived, fields = fields)
    }

    override fun softDeleteTemplate(templateId: Long) = repo.softDeleteTemplate(templateId)

    override fun insertEntry(
        templateId: Long?,
        macroId: Long?,
        title: String,
        timestampMs: Long,
        tagIds: Set<Long>,
        fieldValues: List<QuickEventFieldValue>
    ): Long =
        repo.insertEntry(templateId = templateId, macroId = macroId, title = title, timestampMs = timestampMs, tagIds = tagIds, fieldValues = fieldValues)

    override fun createStandaloneEntry(
        title: String,
        timestampMs: Long,
        tagIds: Set<Long>,
        fieldValues: List<QuickEventFieldValue>
    ): Long =
        repo.createStandaloneEntry(title = title, timestampMs = timestampMs, tagIds = tagIds, fieldValues = fieldValues)

    override fun createStandaloneEntryWithReusableTemplate(
        title: String,
        timestampMs: Long,
        tagIds: Set<Long>,
        fieldValues: List<QuickEventFieldValue>
    ): QuickEventRepository.StandaloneEntryCreateResult =
        repo.createStandaloneEntryWithReusableTemplate(title = title, timestampMs = timestampMs, tagIds = tagIds, fieldValues = fieldValues)

    override fun updateEntry(entryId: Long, title: String, timestampMs: Long, tagIds: Set<Long>, fieldValues: List<QuickEventFieldValue>) {
        repo.updateEntry(entryId = entryId, title = title, timestampMs = timestampMs, tagIds = tagIds, fieldValues = fieldValues)
    }

    override fun softDeleteEntry(entryId: Long) = repo.softDeleteEntry(entryId)

    override fun restoreEntry(entryId: Long) = repo.restoreEntry(entryId)

    override fun insertMacro(title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, actions: List<QuickEventMacroAction>): Long =
        repo.insertMacro(title = title, tagIds = tagIds, sortOrder = sortOrder, isArchived = isArchived, actions = actions)

    override fun updateMacro(macroId: Long, title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, actions: List<QuickEventMacroAction>) {
        repo.updateMacro(macroId = macroId, title = title, tagIds = tagIds, sortOrder = sortOrder, isArchived = isArchived, actions = actions)
    }

    override fun softDeleteMacro(macroId: Long) = repo.softDeleteMacro(macroId)

    override fun replaceAll(
        templates: List<QuickEventTemplate>,
        entries: List<QuickEventEntry>,
        fieldDefinitions: List<QuickEventFieldDefinition>,
        fieldValues: List<QuickEventFieldValue>,
        macros: List<QuickEventMacro>,
        macroActions: List<QuickEventMacroAction>
    ) {
        repo.replaceAll(
            templates = templates,
            entries = entries,
            fieldDefinitions = fieldDefinitions,
            fieldValues = fieldValues,
            macros = macros,
            macroActions = macroActions
        )
    }
}
