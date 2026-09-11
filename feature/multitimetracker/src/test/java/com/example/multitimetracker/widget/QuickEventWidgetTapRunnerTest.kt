package com.example.multitimetracker.widget

import com.example.multitimetracker.core.quickevent.QuickEventCore
import com.example.multitimetracker.core.quickevent.QuickEventTarget
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickEventWidgetTapRunnerTest {
    @Test
    fun widgetTemplateTapCreatesExactlyOneEntryThenReturnsRecordedToast() {
        val core = FakeQuickEventCore(
            templates = mutableMapOf(11L to QuickEventTemplate(11L, "Coffee", setOf(3L)))
        )

        val result = runner(core).run(QuickEventTarget.Template(11L))

        assertTrue(result is QuickEventWidgetTapResult.Recorded)
        assertEquals("Coffee", (result as QuickEventWidgetTapResult.Recorded).title)
        assertEquals(listOf("Coffee"), core.inserted.map { it.title })
        assertEquals(listOf(11L), core.inserted.map { it.templateId })
        assertEquals(1, core.afterWriteCount)
    }

    @Test
    fun widgetTapFailureDoesNotReturnSuccessToast() {
        val core = FakeQuickEventCore(
            templates = mutableMapOf(12L to QuickEventTemplate(12L, "Broken", emptySet())),
            failInsert = true
        )

        val result = runner(core).run(QuickEventTarget.Template(12L))

        assertEquals(QuickEventWidgetTapResult.Failed, result)
        assertEquals(emptyList<FakeEntry>(), core.inserted)
        assertEquals(0, core.afterWriteCount)
    }

    @Test
    fun twoDistinctWidgetTargetsStayDistinct() {
        val core = FakeQuickEventCore(
            templates = mutableMapOf(
                21L to QuickEventTemplate(21L, "Coffee", setOf(3L)),
                22L to QuickEventTemplate(22L, "Water", setOf(4L))
            )
        )

        val first = runner(core).run(QuickEventTarget.Template(21L))
        val second = runner(core).run(QuickEventTarget.Template(22L))

        assertTrue(first is QuickEventWidgetTapResult.Recorded)
        assertTrue(second is QuickEventWidgetTapResult.Recorded)
        assertEquals(listOf(21L, 22L), core.inserted.map { it.templateId })
        assertEquals(listOf("Coffee", "Water"), core.inserted.map { it.title })
        assertEquals(2, core.afterWriteCount)
    }

    private fun runner(core: FakeQuickEventCore): QuickEventWidgetTapRunner =
        QuickEventWidgetTapRunner(
            core = core,
            audit = { _, _, _, _, _ -> },
            afterSuccessfulWrite = { core.afterWriteCount += 1 }
        )
}

data class FakeEntry(val templateId: Long?, val macroId: Long?, val title: String, val tagIds: Set<Long>)

private class FakeQuickEventCore(
    val templates: MutableMap<Long, QuickEventTemplate> = mutableMapOf(),
    val failInsert: Boolean = false
) : QuickEventCore {
    val inserted = mutableListOf<FakeEntry>()
    var afterWriteCount = 0

    override fun readSnapshot(): QuickEventRepository.Snapshot = QuickEventRepository.Snapshot(emptyList(), emptyList())
    override fun readScreenSnapshot(entryLimit: Int): QuickEventRepository.Snapshot = QuickEventRepository.Snapshot(emptyList(), emptyList())
    override fun readTemplateById(templateId: Long): QuickEventTemplate? = templates[templateId]
    override fun readEntryById(entryId: Long): QuickEventEntry? = null
    override fun readMacroById(macroId: Long): QuickEventMacro? = null
    override fun readTemplatesByIds(templateIds: Set<Long>): List<QuickEventTemplate> = templateIds.mapNotNull { templates[it] }
    override fun readFieldDefinitionsForTemplate(templateId: Long): List<QuickEventFieldDefinition> = emptyList()
    override fun readFieldDefinitionsForTemplates(templateIds: Set<Long>): List<QuickEventFieldDefinition> = emptyList()
    override fun readMacroActionsForMacro(macroId: Long): List<QuickEventMacroAction> = emptyList()
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
