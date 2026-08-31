package com.example.multitimetracker.persistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldType
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate
import com.example.multitimetracker.util.CapsuleAudit
import com.example.multitimetracker.util.CapsuleWriteApi
import org.json.JSONArray

class QuickEventRepository(private val context: Context) {

    data class Snapshot(
        val templates: List<QuickEventTemplate>,
        val entries: List<QuickEventEntry>,
        val fieldDefinitions: List<QuickEventFieldDefinition> = emptyList(),
        val fieldValues: List<QuickEventFieldValue> = emptyList(),
        val macros: List<QuickEventMacro> = emptyList(),
        val macroActions: List<QuickEventMacroAction> = emptyList(),
    )

    data class StandaloneEntryCreateResult(
        val entryId: Long,
        val templateId: Long?
    )

    fun readSnapshot(): Snapshot = Snapshot(
        templates = readTemplates(includeArchived = true),
        entries = readEntries(limit = Int.MAX_VALUE),
        fieldDefinitions = readFieldDefinitions(),
        fieldValues = readFieldValues(),
        macros = readMacros(includeArchived = true),
        macroActions = readMacroActions(),
    )

    fun readScreenSnapshot(entryLimit: Int = 500): Snapshot {
        val entries = readEntries(limit = entryLimit.coerceAtLeast(50))
        val entryIds = entries.map { it.id }.toSet()
        return Snapshot(
            templates = readTemplates(includeArchived = true),
            entries = entries,
            fieldDefinitions = readFieldDefinitions(),
            fieldValues = readFieldValuesForEntries(entryIds),
            macros = readMacros(includeArchived = true),
            macroActions = readMacroActions(),
        )
    }

    fun readTemplateById(templateId: Long): QuickEventTemplate? =
        readTemplatesByIds(setOf(templateId)).firstOrNull()

    fun readEntryById(entryId: Long): QuickEventEntry? {
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val sql = """
                SELECT e.id, e.template_id, e.macro_id, e.title, e.timestamp_ms, e.deleted_at_ms, et.tag_id
                FROM ${SnapshotSqlite.QUICK_EVENT_ENTRIES_TABLE} e
                LEFT JOIN ${SnapshotSqlite.QUICK_EVENT_ENTRY_TAGS_TABLE} et ON et.entry_id = e.id
                WHERE e.id = ? AND e.deleted_at_ms IS NULL
            """.trimIndent()
            val byId = LinkedHashMap<Long, EntryAccumulator>()
            db.rawQuery(sql, arrayOf(entryId.toString())).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val acc = byId.getOrPut(id) {
                        EntryAccumulator(
                            id = id,
                            templateId = if (c.isNull(1)) null else c.getLong(1),
                            macroId = if (c.isNull(2)) null else c.getLong(2),
                            title = c.getString(3),
                            timestampMs = c.getLong(4),
                            deletedAtMs = if (c.isNull(5)) null else c.getLong(5),
                        )
                    }
                    if (!c.isNull(6)) acc.tagIds.add(c.getLong(6))
                }
            }
            return byId.values.firstOrNull()?.toUi()
        } finally {
            db.close()
        }
    }

    fun readMacroById(macroId: Long): QuickEventMacro? =
        readMacrosByIds(setOf(macroId)).firstOrNull()

    fun readTemplatesByIds(templateIds: Set<Long>): List<QuickEventTemplate> {
        val ids = templateIds.filter { it > 0L }.distinct().sorted()
        if (ids.isEmpty()) return emptyList()
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val placeholders = ids.joinToString(",") { "?" }
            val sql = """
                SELECT t.id, t.title, t.sort_order, t.is_archived, t.deleted_at_ms, tt.tag_id
                FROM ${SnapshotSqlite.QUICK_EVENT_TEMPLATES_TABLE} t
                LEFT JOIN ${SnapshotSqlite.QUICK_EVENT_TEMPLATE_TAGS_TABLE} tt ON tt.template_id = t.id
                WHERE t.id IN ($placeholders) AND t.deleted_at_ms IS NULL
                ORDER BY t.sort_order ASC, t.title COLLATE NOCASE ASC, t.id ASC
            """.trimIndent()
            val byId = LinkedHashMap<Long, TemplateAccumulator>()
            db.rawQuery(sql, ids.map { it.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val acc = byId.getOrPut(id) {
                        TemplateAccumulator(
                            id = id,
                            title = c.getString(1),
                            sortOrder = c.getInt(2),
                            isArchived = c.getInt(3) != 0,
                            deletedAtMs = if (c.isNull(4)) null else c.getLong(4),
                        )
                    }
                    if (!c.isNull(5)) acc.tagIds.add(c.getLong(5))
                }
            }
            return byId.values.map { it.toUi() }
        } finally {
            db.close()
        }
    }

    private fun readMacrosByIds(macroIds: Set<Long>): List<QuickEventMacro> {
        val ids = macroIds.filter { it > 0L }.distinct().sorted()
        if (ids.isEmpty()) return emptyList()
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val placeholders = ids.joinToString(",") { "?" }
            val sql = """
                SELECT m.id, m.title, m.sort_order, m.is_archived, m.deleted_at_ms, mt.tag_id
                FROM ${SnapshotSqlite.QUICK_EVENT_MACROS_TABLE} m
                LEFT JOIN ${SnapshotSqlite.QUICK_EVENT_MACRO_TAGS_TABLE} mt ON mt.macro_id = m.id
                WHERE m.id IN ($placeholders) AND m.deleted_at_ms IS NULL
                ORDER BY m.sort_order ASC, m.title COLLATE NOCASE ASC, m.id ASC
            """.trimIndent()
            val byId = LinkedHashMap<Long, MacroAccumulator>()
            db.rawQuery(sql, ids.map { it.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val acc = byId.getOrPut(id) {
                        MacroAccumulator(
                            id = id,
                            title = c.getString(1),
                            sortOrder = c.getInt(2),
                            isArchived = c.getInt(3) != 0,
                            deletedAtMs = if (c.isNull(4)) null else c.getLong(4),
                        )
                    }
                    if (!c.isNull(5)) acc.tagIds.add(c.getLong(5))
                }
            }
            return byId.values.map { it.toUi() }
        } finally {
            db.close()
        }
    }

    fun readTemplates(includeArchived: Boolean = false): List<QuickEventTemplate> {
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val whereArchived = if (includeArchived) "" else "AND t.is_archived = 0"
            val sql = """
                SELECT t.id, t.title, t.sort_order, t.is_archived, t.deleted_at_ms, tt.tag_id
                FROM ${SnapshotSqlite.QUICK_EVENT_TEMPLATES_TABLE} t
                LEFT JOIN ${SnapshotSqlite.QUICK_EVENT_TEMPLATE_TAGS_TABLE} tt ON tt.template_id = t.id
                WHERE t.deleted_at_ms IS NULL $whereArchived
                ORDER BY t.sort_order ASC, t.title COLLATE NOCASE ASC, t.id ASC
            """.trimIndent()
            val byId = LinkedHashMap<Long, TemplateAccumulator>()
            db.rawQuery(sql, emptyArray()).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val acc = byId.getOrPut(id) {
                        TemplateAccumulator(
                            id = id,
                            title = c.getString(1),
                            sortOrder = c.getInt(2),
                            isArchived = c.getInt(3) != 0,
                            deletedAtMs = if (c.isNull(4)) null else c.getLong(4),
                        )
                    }
                    if (!c.isNull(5)) acc.tagIds.add(c.getLong(5))
                }
            }
            return byId.values.map { it.toUi() }
        } finally {
            db.close()
        }
    }

    fun readEntries(limit: Int = 50): List<QuickEventEntry> {
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val safeLimit = limit.coerceAtLeast(1)
            val sql = """
                SELECT e.id, e.template_id, e.macro_id, e.title, e.timestamp_ms, e.deleted_at_ms, et.tag_id
                FROM ${SnapshotSqlite.QUICK_EVENT_ENTRIES_TABLE} e
                LEFT JOIN ${SnapshotSqlite.QUICK_EVENT_ENTRY_TAGS_TABLE} et ON et.entry_id = e.id
                WHERE e.deleted_at_ms IS NULL
                ORDER BY e.timestamp_ms DESC, e.id DESC
                LIMIT $safeLimit
            """.trimIndent()
            val byId = LinkedHashMap<Long, EntryAccumulator>()
            db.rawQuery(sql, emptyArray()).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val acc = byId.getOrPut(id) {
                        EntryAccumulator(
                            id = id,
                            templateId = if (c.isNull(1)) null else c.getLong(1),
                            macroId = if (c.isNull(2)) null else c.getLong(2),
                            title = c.getString(3),
                            timestampMs = c.getLong(4),
                            deletedAtMs = if (c.isNull(5)) null else c.getLong(5),
                        )
                    }
                    if (!c.isNull(6)) acc.tagIds.add(c.getLong(6))
                }
            }
            return byId.values.map { it.toUi() }
        } finally {
            db.close()
        }
    }

    fun readAllEntriesOrderedByTimestamp(includeDeleted: Boolean = false): List<QuickEventEntry> {
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val whereDeleted = if (includeDeleted) "" else "WHERE e.deleted_at_ms IS NULL"
            val sql = """
                SELECT e.id, e.template_id, e.macro_id, e.title, e.timestamp_ms, e.deleted_at_ms, et.tag_id
                FROM ${SnapshotSqlite.QUICK_EVENT_ENTRIES_TABLE} e
                LEFT JOIN ${SnapshotSqlite.QUICK_EVENT_ENTRY_TAGS_TABLE} et ON et.entry_id = e.id
                $whereDeleted
                ORDER BY e.timestamp_ms DESC, e.id DESC
            """.trimIndent()
            val byId = LinkedHashMap<Long, EntryAccumulator>()
            db.rawQuery(sql, emptyArray()).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val acc = byId.getOrPut(id) {
                        EntryAccumulator(
                            id = id,
                            templateId = if (c.isNull(1)) null else c.getLong(1),
                            macroId = if (c.isNull(2)) null else c.getLong(2),
                            title = c.getString(3),
                            timestampMs = c.getLong(4),
                            deletedAtMs = if (c.isNull(5)) null else c.getLong(5),
                        )
                    }
                    if (!c.isNull(6)) acc.tagIds.add(c.getLong(6))
                }
            }
            return byId.values.map { it.toUi() }
        } finally {
            db.close()
        }
    }

    fun readFieldDefinitions(): List<QuickEventFieldDefinition> {
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val sql = """
                SELECT id, template_id, label, type, required, default_value, choice_options_json, display_order, deleted_at_ms
                FROM ${SnapshotSqlite.QUICK_EVENT_TEMPLATE_FIELDS_TABLE}
                WHERE deleted_at_ms IS NULL
                ORDER BY template_id ASC, display_order ASC, label COLLATE NOCASE ASC, id ASC
            """.trimIndent()
            return db.rawQuery(sql, emptyArray()).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            QuickEventFieldDefinition(
                                id = c.getLong(0),
                                templateId = c.getLong(1),
                                label = c.getString(2),
                                type = parseFieldType(c.getString(3)),
                                required = c.getInt(4) != 0,
                                defaultValue = c.getString(5),
                                choiceOptions = parseStringArray(c.getString(6)),
                                displayOrder = c.getInt(7),
                                deletedAtMs = if (c.isNull(8)) null else c.getLong(8),
                            )
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    fun readFieldDefinitionsForTemplate(templateId: Long): List<QuickEventFieldDefinition> =
        readFieldDefinitionsForTemplates(setOf(templateId))

    fun readFieldDefinitionsForTemplates(templateIds: Set<Long>): List<QuickEventFieldDefinition> {
        val ids = templateIds.filter { it > 0L }.distinct().sorted()
        if (ids.isEmpty()) return emptyList()
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val placeholders = ids.joinToString(",") { "?" }
            val sql = """
                SELECT id, template_id, label, type, required, default_value, choice_options_json, display_order, deleted_at_ms
                FROM ${SnapshotSqlite.QUICK_EVENT_TEMPLATE_FIELDS_TABLE}
                WHERE deleted_at_ms IS NULL AND template_id IN ($placeholders)
                ORDER BY template_id ASC, display_order ASC, label COLLATE NOCASE ASC, id ASC
            """.trimIndent()
            return db.rawQuery(sql, ids.map { it.toString() }.toTypedArray()).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            QuickEventFieldDefinition(
                                id = c.getLong(0),
                                templateId = c.getLong(1),
                                label = c.getString(2),
                                type = parseFieldType(c.getString(3)),
                                required = c.getInt(4) != 0,
                                defaultValue = c.getString(5),
                                choiceOptions = parseStringArray(c.getString(6)),
                                displayOrder = c.getInt(7),
                                deletedAtMs = if (c.isNull(8)) null else c.getLong(8),
                            )
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    fun readFieldValues(): List<QuickEventFieldValue> {
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val sql = """
                SELECT id, entry_id, field_id, label, type, value, display_order
                FROM ${SnapshotSqlite.QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE}
                ORDER BY entry_id ASC, display_order ASC, id ASC
            """.trimIndent()
            return db.rawQuery(sql, emptyArray()).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            QuickEventFieldValue(
                                id = c.getLong(0),
                                entryId = c.getLong(1),
                                fieldId = c.getLong(2),
                                label = c.getString(3),
                                type = parseFieldType(c.getString(4)),
                                value = c.getString(5),
                                displayOrder = c.getInt(6),
                            )
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    fun readFieldValuesForEntries(entryIds: Set<Long>): List<QuickEventFieldValue> {
        val ids = entryIds.filter { it > 0L }.distinct().sorted()
        if (ids.isEmpty()) return emptyList()
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val placeholders = ids.joinToString(",") { "?" }
            val sql = """
                SELECT id, entry_id, field_id, label, type, value, display_order
                FROM ${SnapshotSqlite.QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE}
                WHERE entry_id IN ($placeholders)
                ORDER BY entry_id ASC, display_order ASC, id ASC
            """.trimIndent()
            return db.rawQuery(sql, ids.map { it.toString() }.toTypedArray()).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            QuickEventFieldValue(
                                id = c.getLong(0),
                                entryId = c.getLong(1),
                                fieldId = c.getLong(2),
                                label = c.getString(3),
                                type = parseFieldType(c.getString(4)),
                                value = c.getString(5),
                                displayOrder = c.getInt(6),
                            )
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    fun readMacros(includeArchived: Boolean = false): List<QuickEventMacro> {
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            val whereArchived = if (includeArchived) "" else "AND m.is_archived = 0"
            val sql = """
                SELECT m.id, m.title, m.sort_order, m.is_archived, m.deleted_at_ms, mt.tag_id
                FROM ${SnapshotSqlite.QUICK_EVENT_MACROS_TABLE} m
                LEFT JOIN ${SnapshotSqlite.QUICK_EVENT_MACRO_TAGS_TABLE} mt ON mt.macro_id = m.id
                WHERE m.deleted_at_ms IS NULL $whereArchived
                ORDER BY m.sort_order ASC, m.title COLLATE NOCASE ASC, m.id ASC
            """.trimIndent()
            val byId = LinkedHashMap<Long, MacroAccumulator>()
            db.rawQuery(sql, emptyArray()).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val acc = byId.getOrPut(id) {
                        MacroAccumulator(
                            id = id,
                            title = c.getString(1),
                            sortOrder = c.getInt(2),
                            isArchived = c.getInt(3) != 0,
                            deletedAtMs = if (c.isNull(4)) null else c.getLong(4),
                        )
                    }
                    if (!c.isNull(5)) acc.tagIds.add(c.getLong(5))
                }
            }
            return byId.values.map { it.toUi() }
        } finally {
            db.close()
        }
    }

    fun readMacroActions(): List<QuickEventMacroAction> {
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            return db.rawQuery(
                """
                SELECT macro_id, template_id, display_order
                FROM ${SnapshotSqlite.QUICK_EVENT_MACRO_ACTIONS_TABLE}
                ORDER BY macro_id ASC, display_order ASC, template_id ASC
                """.trimIndent(),
                emptyArray()
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(QuickEventMacroAction(macroId = c.getLong(0), templateId = c.getLong(1), displayOrder = c.getInt(2)))
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    fun readMacroActionsForMacro(macroId: Long): List<QuickEventMacroAction> {
        if (macroId <= 0L) return emptyList()
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openReadableDb(context)
        try {
            return db.rawQuery(
                """
                SELECT macro_id, template_id, display_order
                FROM ${SnapshotSqlite.QUICK_EVENT_MACRO_ACTIONS_TABLE}
                WHERE macro_id = ?
                ORDER BY display_order ASC, template_id ASC
                """.trimIndent(),
                arrayOf(macroId.toString())
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(QuickEventMacroAction(macroId = c.getLong(0), templateId = c.getLong(1), displayOrder = c.getInt(2)))
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    @CapsuleWriteApi
    fun insertTemplate(
        title: String,
        tagIds: Set<Long>,
        sortOrder: Int,
        isArchived: Boolean,
        fields: List<QuickEventFieldDefinition> = emptyList()
    ): Long {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.insertTemplate")
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openWritableDb(context)
        val now = System.currentTimeMillis()
        var insertedId = -1L
        db.beginTransaction()
        try {
            val templateId = db.insertOrThrow(
                SnapshotSqlite.QUICK_EVENT_TEMPLATES_TABLE,
                null,
                templateValues(title, sortOrder, isArchived, now)
            )
            replaceTemplateTags(db, templateId, tagIds)
            replaceTemplateFields(db, templateId, fields, now)
            db.setTransactionSuccessful()
            insertedId = templateId
        } finally {
            db.endTransaction()
            db.close()
        }
        PersistentMutationTracker.record(context, "QuickEventRepository.insertTemplate")
        return insertedId
    }

    @CapsuleWriteApi
    fun updateTemplate(
        templateId: Long,
        title: String,
        tagIds: Set<Long>,
        sortOrder: Int,
        isArchived: Boolean,
        fields: List<QuickEventFieldDefinition> = emptyList()
    ) {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.updateTemplate")
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openWritableDb(context)
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.update(
                SnapshotSqlite.QUICK_EVENT_TEMPLATES_TABLE,
                ContentValues().apply {
                    put("title", title)
                    put("sort_order", sortOrder)
                    put("is_archived", if (isArchived) 1 else 0)
                    put("updated_at_ms", now)
                },
                "id = ? AND deleted_at_ms IS NULL",
                arrayOf(templateId.toString())
            )
            replaceTemplateTags(db, templateId, tagIds)
            replaceTemplateFields(db, templateId, fields, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        PersistentMutationTracker.record(context, "QuickEventRepository.updateTemplate")
    }

    @CapsuleWriteApi
    fun softDeleteTemplate(templateId: Long) {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.softDeleteTemplate")
        softDeleteRow(SnapshotSqlite.QUICK_EVENT_TEMPLATES_TABLE, templateId)
        PersistentMutationTracker.record(context, "QuickEventRepository.softDeleteTemplate")
    }

    @CapsuleWriteApi
    fun insertEntry(
        templateId: Long?,
        macroId: Long?,
        title: String,
        timestampMs: Long,
        tagIds: Set<Long>,
        fieldValues: List<QuickEventFieldValue> = emptyList()
    ): Long {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.insertEntry")
        val cleanTitle = normalizeEntryTitle(title)
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openWritableDb(context)
        val now = System.currentTimeMillis()
        var insertedId = -1L
        db.beginTransaction()
        try {
            val entryId = db.insertOrThrow(
                SnapshotSqlite.QUICK_EVENT_ENTRIES_TABLE,
                null,
                entryValues(null, templateId, macroId, cleanTitle, timestampMs, now)
            )
            replaceEntryTags(db, entryId, tagIds)
            replaceEntryFieldValues(db, entryId, fieldValues)
            db.setTransactionSuccessful()
            insertedId = entryId
        } finally {
            db.endTransaction()
            db.close()
        }
        PersistentMutationTracker.record(context, "QuickEventRepository.insertEntry")
        return insertedId
    }

    @CapsuleWriteApi
    fun createStandaloneEntry(
        title: String,
        timestampMs: Long,
        tagIds: Set<Long> = emptySet(),
        fieldValues: List<QuickEventFieldValue> = emptyList()
    ): Long {
        return insertEntry(
            templateId = null,
            macroId = null,
            title = title,
            timestampMs = timestampMs,
            tagIds = tagIds,
            fieldValues = fieldValues
        )
    }

    @CapsuleWriteApi
    fun createStandaloneEntryWithReusableTemplate(
        title: String,
        timestampMs: Long,
        tagIds: Set<Long> = emptySet(),
        fieldValues: List<QuickEventFieldValue> = emptyList()
    ): StandaloneEntryCreateResult {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.createStandaloneEntryWithReusableTemplate")
        val cleanTitle = normalizeEntryTitle(title)
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openWritableDb(context)
        val now = System.currentTimeMillis()
        var entryId = -1L
        var templateId = -1L
        db.beginTransaction()
        try {
            entryId = db.insertOrThrow(
                SnapshotSqlite.QUICK_EVENT_ENTRIES_TABLE,
                null,
                entryValues(null, null, null, cleanTitle, timestampMs, now)
            )
            replaceEntryTags(db, entryId, tagIds)
            replaceEntryFieldValues(db, entryId, fieldValues)
            templateId = db.insertOrThrow(
                SnapshotSqlite.QUICK_EVENT_TEMPLATES_TABLE,
                null,
                templateValues(cleanTitle, sortOrder = 0, isArchived = false, now = now)
            )
            replaceTemplateTags(db, templateId, tagIds)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        PersistentMutationTracker.record(context, "QuickEventRepository.createStandaloneEntryWithReusableTemplate")
        return StandaloneEntryCreateResult(entryId = entryId, templateId = templateId)
    }

    @CapsuleWriteApi
    fun updateEntry(entryId: Long, title: String, timestampMs: Long, tagIds: Set<Long>, fieldValues: List<QuickEventFieldValue> = emptyList()) {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.updateEntry")
        val cleanTitle = normalizeEntryTitle(title)
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openWritableDb(context)
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.update(
                SnapshotSqlite.QUICK_EVENT_ENTRIES_TABLE,
                ContentValues().apply {
                    put("title", cleanTitle)
                    put("timestamp_ms", timestampMs)
                    put("updated_at_ms", now)
                },
                "id = ? AND deleted_at_ms IS NULL",
                arrayOf(entryId.toString())
            )
            replaceEntryTags(db, entryId, tagIds)
            replaceEntryFieldValues(db, entryId, fieldValues)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        PersistentMutationTracker.record(context, "QuickEventRepository.updateEntry")
    }

    @CapsuleWriteApi
    fun softDeleteEntry(entryId: Long) {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.softDeleteEntry")
        softDeleteRow(SnapshotSqlite.QUICK_EVENT_ENTRIES_TABLE, entryId)
        PersistentMutationTracker.record(context, "QuickEventRepository.softDeleteEntry")
    }

    @CapsuleWriteApi
    fun restoreEntry(entryId: Long) {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.restoreEntry")
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openWritableDb(context)
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.update(
                SnapshotSqlite.QUICK_EVENT_ENTRIES_TABLE,
                ContentValues().apply {
                    putNull("deleted_at_ms")
                    put("updated_at_ms", now)
                },
                "id = ? AND deleted_at_ms IS NOT NULL",
                arrayOf(entryId.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        PersistentMutationTracker.record(context, "QuickEventRepository.restoreEntry")
    }

    @CapsuleWriteApi
    fun insertMacro(title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, actions: List<QuickEventMacroAction>): Long {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.insertMacro")
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openWritableDb(context)
        val now = System.currentTimeMillis()
        var insertedId = -1L
        db.beginTransaction()
        try {
            val macroId = db.insertOrThrow(
                SnapshotSqlite.QUICK_EVENT_MACROS_TABLE,
                null,
                macroValues(title, sortOrder, isArchived, now)
            )
            replaceMacroTags(db, macroId, tagIds)
            replaceMacroActions(db, macroId, actions)
            db.setTransactionSuccessful()
            insertedId = macroId
        } finally {
            db.endTransaction()
            db.close()
        }
        PersistentMutationTracker.record(context, "QuickEventRepository.insertMacro")
        return insertedId
    }

    @CapsuleWriteApi
    fun updateMacro(macroId: Long, title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, actions: List<QuickEventMacroAction>) {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.updateMacro")
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openWritableDb(context)
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.update(
                SnapshotSqlite.QUICK_EVENT_MACROS_TABLE,
                ContentValues().apply {
                    put("title", title)
                    put("sort_order", sortOrder)
                    put("is_archived", if (isArchived) 1 else 0)
                    put("updated_at_ms", now)
                },
                "id = ? AND deleted_at_ms IS NULL",
                arrayOf(macroId.toString())
            )
            replaceMacroTags(db, macroId, tagIds)
            replaceMacroActions(db, macroId, actions)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        PersistentMutationTracker.record(context, "QuickEventRepository.updateMacro")
    }

    @CapsuleWriteApi
    fun softDeleteMacro(macroId: Long) {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.softDeleteMacro")
        softDeleteRow(SnapshotSqlite.QUICK_EVENT_MACROS_TABLE, macroId)
        PersistentMutationTracker.record(context, "QuickEventRepository.softDeleteMacro")
    }

    @CapsuleWriteApi
    fun replaceAll(
        templates: List<QuickEventTemplate>,
        entries: List<QuickEventEntry>,
        fieldDefinitions: List<QuickEventFieldDefinition> = emptyList(),
        fieldValues: List<QuickEventFieldValue> = emptyList(),
        macros: List<QuickEventMacro> = emptyList(),
        macroActions: List<QuickEventMacroAction> = emptyList()
    ) {
        CapsuleAudit.auditPersistenceWrite("QuickEventRepository.replaceAll")
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openWritableDb(context)
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete(SnapshotSqlite.QUICK_EVENT_MACRO_ACTIONS_TABLE, null, null)
            db.delete(SnapshotSqlite.QUICK_EVENT_MACRO_TAGS_TABLE, null, null)
            db.delete(SnapshotSqlite.QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE, null, null)
            db.delete(SnapshotSqlite.QUICK_EVENT_TEMPLATE_FIELDS_TABLE, null, null)
            db.delete(SnapshotSqlite.QUICK_EVENT_TEMPLATE_TAGS_TABLE, null, null)
            db.delete(SnapshotSqlite.QUICK_EVENT_ENTRY_TAGS_TABLE, null, null)
            db.delete(SnapshotSqlite.QUICK_EVENT_MACROS_TABLE, null, null)
            db.delete(SnapshotSqlite.QUICK_EVENT_TEMPLATES_TABLE, null, null)
            db.delete(SnapshotSqlite.QUICK_EVENT_ENTRIES_TABLE, null, null)

            templates.forEach { template ->
                db.insertWithOnConflict(
                    SnapshotSqlite.QUICK_EVENT_TEMPLATES_TABLE,
                    null,
                    templateValues(template.title, template.sortOrder, template.isArchived, now).apply {
                        put("id", template.id)
                        if (template.deletedAtMs == null) putNull("deleted_at_ms") else put("deleted_at_ms", template.deletedAtMs)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                replaceTemplateTags(db, template.id, template.tagIds)
            }
            entries.forEach { entry ->
                db.insertWithOnConflict(
                    SnapshotSqlite.QUICK_EVENT_ENTRIES_TABLE,
                    null,
                    entryValues(entry.id, entry.templateId, entry.macroId, entry.title, entry.timestampMs, now).apply {
                        if (entry.deletedAtMs == null) putNull("deleted_at_ms") else put("deleted_at_ms", entry.deletedAtMs)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                replaceEntryTags(db, entry.id, entry.tagIds)
            }
            fieldDefinitions.forEach { field -> insertFieldDefinition(db, field.templateId, field, now, forceId = field.id) }
            fieldValues.forEach { value -> insertFieldValue(db, value.entryId, value, forceId = value.id) }
            macros.forEach { macro ->
                db.insertWithOnConflict(
                    SnapshotSqlite.QUICK_EVENT_MACROS_TABLE,
                    null,
                    macroValues(macro.title, macro.sortOrder, macro.isArchived, now).apply {
                        put("id", macro.id)
                        if (macro.deletedAtMs == null) putNull("deleted_at_ms") else put("deleted_at_ms", macro.deletedAtMs)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                replaceMacroTags(db, macro.id, macro.tagIds)
            }
            macroActions.forEach { action -> insertMacroAction(db, action.macroId, action.templateId, action.displayOrder) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        PersistentMutationTracker.record(context, "QuickEventRepository.replaceAll")
    }

    private fun softDeleteRow(table: String, id: Long) {
        SnapshotSqlite.ensureQuickEventTables(context)
        val db = SnapshotSqlite.openWritableDb(context)
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.update(
                table,
                ContentValues().apply {
                    put("deleted_at_ms", now)
                    put("updated_at_ms", now)
                },
                "id = ? AND deleted_at_ms IS NULL",
                arrayOf(id.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun templateValues(title: String, sortOrder: Int, isArchived: Boolean, now: Long) = ContentValues().apply {
        put("title", title)
        put("sort_order", sortOrder)
        put("is_archived", if (isArchived) 1 else 0)
        put("created_at_ms", now)
        put("updated_at_ms", now)
        putNull("deleted_at_ms")
    }

    private fun normalizeEntryTitle(title: String): String {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotBlank()) { "Quick event title is required" }
        return cleanTitle
    }

    private fun entryValues(id: Long?, templateId: Long?, macroId: Long?, title: String, timestampMs: Long, now: Long) = ContentValues().apply {
        if (id != null) put("id", id)
        if (templateId == null) putNull("template_id") else put("template_id", templateId)
        if (macroId == null) putNull("macro_id") else put("macro_id", macroId)
        put("title", title)
        put("timestamp_ms", timestampMs)
        put("created_at_ms", now)
        put("updated_at_ms", now)
        putNull("deleted_at_ms")
    }

    private fun macroValues(title: String, sortOrder: Int, isArchived: Boolean, now: Long) = ContentValues().apply {
        put("title", title)
        put("sort_order", sortOrder)
        put("is_archived", if (isArchived) 1 else 0)
        put("created_at_ms", now)
        put("updated_at_ms", now)
        putNull("deleted_at_ms")
    }

    private fun replaceTemplateFields(db: SQLiteDatabase, templateId: Long, fields: List<QuickEventFieldDefinition>, now: Long) {
        db.delete(SnapshotSqlite.QUICK_EVENT_TEMPLATE_FIELDS_TABLE, "template_id = ?", arrayOf(templateId.toString()))
        fields.sortedBy { it.displayOrder }.forEachIndexed { index, field ->
            insertFieldDefinition(db, templateId, field.copy(displayOrder = field.displayOrder.takeIf { it != 0 } ?: index), now, forceId = field.id.takeIf { it > 0L })
        }
    }

    private fun insertFieldDefinition(db: SQLiteDatabase, templateId: Long, field: QuickEventFieldDefinition, now: Long, forceId: Long? = null) {
        val values = ContentValues().apply {
            if (forceId != null) put("id", forceId)
            put("template_id", templateId)
            put("label", field.label)
            put("type", field.type.name)
            put("required", if (field.required) 1 else 0)
            put("default_value", field.defaultValue)
            put("choice_options_json", JSONArray(field.choiceOptions).toString())
            put("display_order", field.displayOrder)
            put("created_at_ms", now)
            put("updated_at_ms", now)
            if (field.deletedAtMs == null) putNull("deleted_at_ms") else put("deleted_at_ms", field.deletedAtMs)
        }
        db.insertWithOnConflict(SnapshotSqlite.QUICK_EVENT_TEMPLATE_FIELDS_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun replaceEntryFieldValues(db: SQLiteDatabase, entryId: Long, values: List<QuickEventFieldValue>) {
        db.delete(SnapshotSqlite.QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE, "entry_id = ?", arrayOf(entryId.toString()))
        values.sortedBy { it.displayOrder }.forEach { value -> insertFieldValue(db, entryId, value) }
    }

    private fun insertFieldValue(db: SQLiteDatabase, entryId: Long, value: QuickEventFieldValue, forceId: Long? = null) {
        db.insertWithOnConflict(
            SnapshotSqlite.QUICK_EVENT_ENTRY_FIELD_VALUES_TABLE,
            null,
            ContentValues().apply {
                if (forceId != null) put("id", forceId)
                put("entry_id", entryId)
                put("field_id", value.fieldId)
                put("label", value.label)
                put("type", value.type.name)
                put("value", value.value)
                put("display_order", value.displayOrder)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun replaceTemplateTags(db: SQLiteDatabase, templateId: Long, tagIds: Set<Long>) {
        db.delete(SnapshotSqlite.QUICK_EVENT_TEMPLATE_TAGS_TABLE, "template_id = ?", arrayOf(templateId.toString()))
        tagIds.filter { it > 0L }.forEach { tagId ->
            db.insertWithOnConflict(
                SnapshotSqlite.QUICK_EVENT_TEMPLATE_TAGS_TABLE,
                null,
                ContentValues().apply {
                    put("template_id", templateId)
                    put("tag_id", tagId)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    private fun replaceEntryTags(db: SQLiteDatabase, entryId: Long, tagIds: Set<Long>) {
        db.delete(SnapshotSqlite.QUICK_EVENT_ENTRY_TAGS_TABLE, "entry_id = ?", arrayOf(entryId.toString()))
        tagIds.filter { it > 0L }.forEach { tagId ->
            db.insertWithOnConflict(
                SnapshotSqlite.QUICK_EVENT_ENTRY_TAGS_TABLE,
                null,
                ContentValues().apply {
                    put("entry_id", entryId)
                    put("tag_id", tagId)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    private fun replaceMacroTags(db: SQLiteDatabase, macroId: Long, tagIds: Set<Long>) {
        db.delete(SnapshotSqlite.QUICK_EVENT_MACRO_TAGS_TABLE, "macro_id = ?", arrayOf(macroId.toString()))
        tagIds.filter { it > 0L }.forEach { tagId ->
            db.insertWithOnConflict(
                SnapshotSqlite.QUICK_EVENT_MACRO_TAGS_TABLE,
                null,
                ContentValues().apply {
                    put("macro_id", macroId)
                    put("tag_id", tagId)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    private fun replaceMacroActions(db: SQLiteDatabase, macroId: Long, actions: List<QuickEventMacroAction>) {
        db.delete(SnapshotSqlite.QUICK_EVENT_MACRO_ACTIONS_TABLE, "macro_id = ?", arrayOf(macroId.toString()))
        actions.sortedBy { it.displayOrder }.forEachIndexed { index, action ->
            insertMacroAction(db, macroId, action.templateId, action.displayOrder.takeIf { it != 0 } ?: index)
        }
    }

    private fun insertMacroAction(db: SQLiteDatabase, macroId: Long, templateId: Long, displayOrder: Int) {
        if (macroId <= 0L || templateId <= 0L) return
        db.insertWithOnConflict(
            SnapshotSqlite.QUICK_EVENT_MACRO_ACTIONS_TABLE,
            null,
            ContentValues().apply {
                put("macro_id", macroId)
                put("template_id", templateId)
                put("display_order", displayOrder)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private data class TemplateAccumulator(
        val id: Long,
        val title: String,
        val sortOrder: Int,
        val isArchived: Boolean,
        val deletedAtMs: Long?,
        val tagIds: MutableSet<Long> = linkedSetOf(),
    ) {
        fun toUi(): QuickEventTemplate = QuickEventTemplate(
            id = id,
            title = title,
            tagIds = tagIds.toSet(),
            sortOrder = sortOrder,
            isArchived = isArchived,
            deletedAtMs = deletedAtMs,
        )
    }

    private data class EntryAccumulator(
        val id: Long,
        val templateId: Long?,
        val macroId: Long?,
        val title: String,
        val timestampMs: Long,
        val deletedAtMs: Long?,
        val tagIds: MutableSet<Long> = linkedSetOf(),
    ) {
        fun toUi(): QuickEventEntry = QuickEventEntry(
            id = id,
            templateId = templateId,
            macroId = macroId,
            title = title,
            timestampMs = timestampMs,
            tagIds = tagIds.toSet(),
            deletedAtMs = deletedAtMs,
        )
    }

    private data class MacroAccumulator(
        val id: Long,
        val title: String,
        val sortOrder: Int,
        val isArchived: Boolean,
        val deletedAtMs: Long?,
        val tagIds: MutableSet<Long> = linkedSetOf(),
    ) {
        fun toUi(): QuickEventMacro = QuickEventMacro(
            id = id,
            title = title,
            tagIds = tagIds.toSet(),
            sortOrder = sortOrder,
            isArchived = isArchived,
            deletedAtMs = deletedAtMs,
        )
    }

    companion object {
        fun parseFieldType(raw: String?): QuickEventFieldType =
            runCatching { QuickEventFieldType.valueOf(raw.orEmpty()) }.getOrDefault(QuickEventFieldType.TEXT)

        fun parseStringArray(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
