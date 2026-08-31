package com.example.multitimetracker.core.quickevent

import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate
import com.example.multitimetracker.persistence.QuickEventRepository

interface QuickEventCore {
    fun readSnapshot(): QuickEventRepository.Snapshot
    fun readScreenSnapshot(entryLimit: Int = 500): QuickEventRepository.Snapshot
    fun readTemplateById(templateId: Long): QuickEventTemplate?
    fun readEntryById(entryId: Long): QuickEventEntry?
    fun readMacroById(macroId: Long): QuickEventMacro?
    fun readTemplatesByIds(templateIds: Set<Long>): List<QuickEventTemplate>
    fun readFieldDefinitionsForTemplate(templateId: Long): List<QuickEventFieldDefinition>
    fun readFieldDefinitionsForTemplates(templateIds: Set<Long>): List<QuickEventFieldDefinition>
    fun readMacroActionsForMacro(macroId: Long): List<QuickEventMacroAction>
    fun insertTemplate(
        title: String,
        tagIds: Set<Long>,
        sortOrder: Int,
        isArchived: Boolean,
        fields: List<QuickEventFieldDefinition> = emptyList()
    ): Long
    fun updateTemplate(
        templateId: Long,
        title: String,
        tagIds: Set<Long>,
        sortOrder: Int,
        isArchived: Boolean,
        fields: List<QuickEventFieldDefinition> = emptyList()
    )
    fun softDeleteTemplate(templateId: Long)
    fun insertEntry(
        templateId: Long?,
        macroId: Long?,
        title: String,
        timestampMs: Long,
        tagIds: Set<Long>,
        fieldValues: List<QuickEventFieldValue> = emptyList()
    ): Long
    fun createStandaloneEntry(
        title: String,
        timestampMs: Long,
        tagIds: Set<Long> = emptySet(),
        fieldValues: List<QuickEventFieldValue> = emptyList()
    ): Long
    fun createStandaloneEntryWithReusableTemplate(
        title: String,
        timestampMs: Long,
        tagIds: Set<Long> = emptySet(),
        fieldValues: List<QuickEventFieldValue> = emptyList()
    ): QuickEventRepository.StandaloneEntryCreateResult
    fun updateEntry(
        entryId: Long,
        title: String,
        timestampMs: Long,
        tagIds: Set<Long>,
        fieldValues: List<QuickEventFieldValue> = emptyList()
    )
    fun softDeleteEntry(entryId: Long)
    fun restoreEntry(entryId: Long)
    fun insertMacro(title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, actions: List<QuickEventMacroAction>): Long
    fun updateMacro(macroId: Long, title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, actions: List<QuickEventMacroAction>)
    fun softDeleteMacro(macroId: Long)
    fun replaceAll(
        templates: List<QuickEventTemplate>,
        entries: List<QuickEventEntry>,
        fieldDefinitions: List<QuickEventFieldDefinition> = emptyList(),
        fieldValues: List<QuickEventFieldValue> = emptyList(),
        macros: List<QuickEventMacro> = emptyList(),
        macroActions: List<QuickEventMacroAction> = emptyList()
    )
}
