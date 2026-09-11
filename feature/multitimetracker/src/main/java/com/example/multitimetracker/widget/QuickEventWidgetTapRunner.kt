package com.example.multitimetracker.widget

import com.example.multitimetracker.core.quickevent.QuickEventCore
import com.example.multitimetracker.core.quickevent.QuickEventExecutionResult
import com.example.multitimetracker.core.quickevent.QuickEventExecutor
import com.example.multitimetracker.core.quickevent.QuickEventTarget
import org.json.JSONObject

sealed class QuickEventWidgetTapResult {
    data class Recorded(val title: String, val entryIds: List<Long>) : QuickEventWidgetTapResult()
    data class NeedsInput(val target: QuickEventTarget) : QuickEventWidgetTapResult()
    data object Unavailable : QuickEventWidgetTapResult()
    data object Failed : QuickEventWidgetTapResult()
}

class QuickEventWidgetTapRunner(
    private val core: QuickEventCore,
    private val audit: (action: String, entityType: String, entityId: Long, summary: String, payload: JSONObject) -> Unit,
    private val afterSuccessfulWrite: () -> Unit
) {
    fun run(target: QuickEventTarget): QuickEventWidgetTapResult =
        runCatching {
            QuickEventExecutor(
                core = core,
                audit = audit,
                afterSuccessfulWrite = afterSuccessfulWrite
            ).execute(target)
        }.fold(
            onSuccess = { result ->
                when (result) {
                    is QuickEventExecutionResult.Executed -> QuickEventWidgetTapResult.Recorded(result.title, result.entryIds)
                    is QuickEventExecutionResult.NeedsInput -> QuickEventWidgetTapResult.NeedsInput(result.target)
                    is QuickEventExecutionResult.Unavailable -> QuickEventWidgetTapResult.Unavailable
                }
            },
            onFailure = { QuickEventWidgetTapResult.Failed }
        )
}
