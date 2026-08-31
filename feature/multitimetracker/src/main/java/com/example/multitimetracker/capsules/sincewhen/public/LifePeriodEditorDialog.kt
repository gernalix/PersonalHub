package com.example.multitimetracker.capsules.sincewhen.public

import androidx.compose.runtime.Composable
import com.example.multitimetracker.capsules.sincewhen.ui.SinceWhenLifePeriodEditorDialog
import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.LifePeriodDisplayUnit
import com.example.multitimetracker.model.Tag

@Composable
fun LifePeriodEditorDialog(
    title: String,
    initial: LifePeriod?,
    initialDraft: LifePeriod? = null,
    initialNowMs: Long,
    availableTags: List<Tag>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Long, Long?, Long, Set<Long>, Set<LifePeriodDisplayUnit>) -> Unit,
) {
    SinceWhenLifePeriodEditorDialog(
        title = title,
        initial = initial,
        initialDraft = initialDraft,
        initialNowMs = initialNowMs,
        availableTags = availableTags,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
