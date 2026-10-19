package com.gernalix.personalhub.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Keep IME edits synchronous while a caller's filter state catches up. */
internal class SearchEditBuffer(initialModel: String) {
    var value by mutableStateOf(TextFieldValue(initialModel))
        private set
    private var lastModel = initialModel
    private val pending = mutableListOf<String>()
    private var restored = false

    fun edit(next: TextFieldValue): Boolean {
        val changed = next.text != value.text
        value = next // Selection and composing range belong to the IME, too.
        if (changed) pending += next.text
        return changed
    }

    fun acceptModel(model: String) {
        if (model == lastModel) return
        lastModel = model
        val acknowledgement = pending.indexOf(model)
        if (acknowledgement >= 0) {
            pending.subList(0, acknowledgement + 1).clear()
        } else if (model != value.text) {
            // A genuinely external replacement (clear, saved search, suggestion).
            pending.clear()
            value = TextFieldValue(model, TextRange(model.length))
        }
    }

    fun takeRestoredQuery(model: String): String? {
        if (!restored) return null
        restored = false
        return value.text.takeIf { it != model }?.also { pending += it }
    }

    companion object {
        val Saver = listSaver<SearchEditBuffer, Any>(
            save = {
                listOf(it.value.text, it.value.selection.start, it.value.selection.end,
                    it.lastModel, ArrayList(it.pending))
            },
            restore = {
                SearchEditBuffer(it[3] as String).apply {
                    value = TextFieldValue(it[0] as String, TextRange(it[1] as Int, it[2] as Int))
                    pending.addAll((it[4] as List<*>).map { item -> item as String })
                    restored = true
                }
            },
        )
    }
}
