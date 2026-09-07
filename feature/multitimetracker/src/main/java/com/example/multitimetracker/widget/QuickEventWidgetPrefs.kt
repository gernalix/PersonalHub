package com.example.multitimetracker.widget

import android.content.Context
import com.example.multitimetracker.core.quickevent.QuickEventTarget

object QuickEventWidgetPrefs {
    private const val PREFS = "quick_event_widget_prefs"
    private const val TEMPLATE_PREFIX = "template:"
    private const val MACRO_PREFIX = "macro:"

    fun save(context: Context, appWidgetId: Int, target: QuickEventTarget) {
        val encoded = when (target) {
            is QuickEventTarget.Template -> "$TEMPLATE_PREFIX${target.templateId}"
            is QuickEventTarget.Macro -> "$MACRO_PREFIX${target.macroId}"
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(appWidgetId), encoded)
            .apply()
    }

    fun read(context: Context, appWidgetId: Int): QuickEventTarget? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(appWidgetId), null) ?: return null
        return when {
            raw.startsWith(TEMPLATE_PREFIX) -> raw.removePrefix(TEMPLATE_PREFIX).toLongOrNull()?.let(QuickEventTarget::Template)
            raw.startsWith(MACRO_PREFIX) -> raw.removePrefix(MACRO_PREFIX).toLongOrNull()?.let(QuickEventTarget::Macro)
            else -> null
        }
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { edit.remove(key(it)) }
        edit.apply()
    }

    private fun key(appWidgetId: Int): String = "target_$appWidgetId"
}
