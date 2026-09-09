package com.example.multitimetracker.core.quickevent

import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate
import com.example.multitimetracker.persistence.QuickEventRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickEventExecutorTest {
    @Test
    fun templateExecutionCreatesOneCanonicalEntry() {
        val core = FakeQuickEventCore(templates = mutableMapOf(1L to QuickEventTemplate(1L, "Coffee", setOf(7L))))

        val result = executor(core).execute(QuickEventTarget.Template(1L), timestampMs = 1000L)

        assertTrue(result is QuickEventExecutionResult.Executed)
        assertEquals("Coffee", (result as QuickEventExecutionResult.Executed).title)
        assertEquals(listOf("Coffee"), core.inserted.map { it.title })
        assertEquals(listOf(1L), core.inserted.map { it.templateId })
        assertEquals(1, core.afterWriteCount)
    }

    @Test
    fun macroExecutionCreatesOrderedEntries() {
        val core = FakeQuickEventCore(
            templates = mutableMapOf(
                1L to QuickEventTemplate(1L, "First", setOf(1L)),
                2L to QuickEventTemplate(2L, "Second", setOf(2L))
            ),
            macros = mutableMapOf(5L to QuickEventMacro(5L, "Morning", setOf(9L))),
            macroActions = mutableMapOf(
                5L to listOf(
                    QuickEventMacroAction(5L, 2L, 2),
                    QuickEventMacroAction(5L, 1L, 1)
                )
            )
        )

        val result = executor(core).execute(QuickEventTarget.Macro(5L), timestampMs = 1000L)

        assertTrue(result is QuickEventExecutionResult.Executed)
        assertEquals("Morning", (result as QuickEventExecutionResult.Executed).title)
        assertEquals(listOf("First", "Second"), core.inserted.map { it.title })
        assertEquals(listOf(5L, 5L), core.inserted.map { it.macroId })
        assertEquals(setOf(1L, 9L), core.inserted.first().tagIds)
    }

    @Test
    fun requiredInputReturnsNeedsInputWithoutWrite() {
        val core = FakeQuickEventCore(
            templates = mutableMapOf(1L to QuickEventTemplate(1L, "Medication", emptySet())),
            fields = mutableMapOf(1L to listOf(QuickEventFieldDefinition(1L, 1L, "Dose", required = true)))
        )

        val result = executor(core).execute(QuickEventTarget.Template(1L), timestampMs = 1000L)

        assertTrue(result is QuickEventExecutionResult.NeedsInput)
        assertEquals(emptyList<FakeEntry>(), core.inserted)
        assertEquals(0, core.afterWriteCount)
    }

    @Test
    fun archivedTargetCannotWrite() {
        val core = FakeQuickEventCore(templates = mutableMapOf(1L to QuickEventTemplate(1L, "Old", emptySet(), isArchived = true)))

        val result = executor(core).execute(QuickEventTarget.Template(1L), timestampMs = 1000L)

        assertTrue(result is QuickEventExecutionResult.Unavailable)
        assertEquals(emptyList<FakeEntry>(), core.inserted)
    }

    @Test
    fun persistenceFailureProducesNoSuccessCallback() {
        val core = FakeQuickEventCore(
            templates = mutableMapOf(1L to QuickEventTemplate(1L, "Broken", emptySet())),
            failInsert = true
        )

        runCatching { executor(core).execute(QuickEventTarget.Template(1L), timestampMs = 1000L) }

        assertEquals(0, core.afterWriteCount)
    }

    private fun executor(core: FakeQuickEventCore): QuickEventExecutor =
        QuickEventExecutor(
            core = core,
            audit = { action, _, _, _, _ -> core.auditActions += action },
            afterSuccessfulWrite = { core.afterWriteCount += 1 },
            nowMs = { 1000L }
        )
}

data class FakeEntry(val templateId: Long?, val macroId: Long?, val title: String, val tagIds: Set<Long>)

private class FakeQuickEventCore(
    val templates: MutableMap<Long, QuickEventTemplate> = mutableMapOf(),
    val macros: MutableMap<Long, QuickEventMacro> = mutableMapOf(),
    val fields: MutableMap<Long, List<QuickEventFieldDefinition>> = mutableMapOf(),
    val macroActions: MutableMap<Long, List<QuickEventMacroAction>> = mutableMapOf(),
    val failInsert: Boolean = false
) : QuickEventCore {
    val inserted = mutableListOf<FakeEntry>()
    val auditActions = mutableListOf<String>()
    var afterWriteCount = 0

    override fun readSnapshot(): QuickEventRepository.Snapshot = QuickEventRepository.Snapshot(emptyList(), emptyList())
    override fun readScreenSnapshot(entryLimit: Int): QuickEventRepository.Snapshot = QuickEventRepository.Snapshot(emptyList(), emptyList())
    override fun readTemplateById(templateId: Long): QuickEventTemplate? = templates[templateId]
    override fun readEntryById(entryId: Long): QuickEventEntry? = null
    override fun readMacroById(macroId: Long): QuickEventMacro? = macros[macroId]
    override fun readTemplatesByIds(templateIds: Set<Long>): List<QuickEventTemplate> = templateIds.mapNotNull { templates[it] }
    override fun readFieldDefinitionsForTemplate(templateId: Long): List<QuickEventFieldDefinition> = fields[templateId].orEmpty()
    override fun readFieldDefinitionsForTemplates(templateIds: Set<Long>): List<QuickEventFieldDefinition> = templateIds.flatMap { fields[it].orEmpty() }
    override fun readMacroActionsForMacro(macroId: Long): List<QuickEventMacroAction> = macroActions[macroId].orEmpty()
    override fun insertTemplate(title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, fields: List<QuickEventFieldDefinition>): Long = error("unused")
    override fun updateTemplate(templateId: Long, title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, fields: List<QuickEventFieldDefinition>) = error("unused")
    override fun softDeleteTemplate(templateId: Long) = error("unused")
    override fun insertEntry(templateId: Long?, macroId: Long?, title: String, timestampMs: Long, tagIds: Set<Long>, fieldValues: List<QuickEventFieldValue>): Long {
        if (failInsert) error("forced failure")
        inserted += FakeEntry(templateId, macroId, title, tagIds)
        return inserted.size.toLong()
    }
    override fun createStandaloneEntry(title: String, timestampMs: Long, tagIds: Set<Long>, fieldValues: List<QuickEventFieldValue>): Long = error("unused")
    override fun createStandaloneEntryWithReusableTemplate(title: String, timestampMs: Long, tagIds: Set<Long>, fieldValues: List<QuickEventFieldValue>): QuickEventRepository.StandaloneEntryCreateResult = error("unused")
    override fun updateEntry(entryId: Long, title: String, timestampMs: Long, tagIds: Set<Long>, fieldValues: List<QuickEventFieldValue>) = error("unused")
    override fun softDeleteEntry(entryId: Long) = error("unused")
    override fun restoreEntry(entryId: Long) = error("unused")
    override fun insertMacro(title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, actions: List<QuickEventMacroAction>): Long = error("unused")
    override fun updateMacro(macroId: Long, title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, actions: List<QuickEventMacroAction>) = error("unused")
    override fun softDeleteMacro(macroId: Long) = error("unused")
    override fun replaceAll(
        templates: List<QuickEventTemplate>,
        entries: List<QuickEventEntry>,
        fieldDefinitions: List<QuickEventFieldDefinition>,
        fieldValues: List<QuickEventFieldValue>,
        macros: List<QuickEventMacro>,
        macroActions: List<QuickEventMacroAction>
    ) = error("unused")
}
