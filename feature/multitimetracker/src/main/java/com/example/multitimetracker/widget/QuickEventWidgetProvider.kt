package com.example.multitimetracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.multitimetracker.R
import com.example.multitimetracker.core.quickevent.DefaultQuickEventCore
import com.example.multitimetracker.core.quickevent.QuickEventTarget

class QuickEventWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateWidgets(context.applicationContext, manager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        QuickEventWidgetPrefs.delete(context.applicationContext, appWidgetIds)
    }

    companion object {
        fun updateWidgets(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
            appWidgetIds.forEach { appWidgetId ->
                manager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
            }
        }

        fun updateOne(context: Context, appWidgetId: Int) {
            val manager = AppWidgetManager.getInstance(context)
            manager.updateAppWidget(appWidgetId, buildViews(context.applicationContext, appWidgetId))
        }

        private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            val target = QuickEventWidgetPrefs.read(context, appWidgetId)
            val resolvedLabel = target?.let { labelFor(context, it) }
            val unavailable = target != null && resolvedLabel == null
            val label = resolvedLabel ?: context.getString(
                if (unavailable) R.string.widget_quick_event_unavailable else R.string.widget_quick_event_configure
            )
            return RemoteViews(context.packageName, R.layout.widget_quick_event).apply {
                setTextViewText(R.id.widgetQuickEventLabel, label)
                setContentDescription(R.id.widgetQuickEventLabel, label)
                setOnClickPendingIntent(
                    R.id.widgetQuickEventRoot,
                    if (target == null || unavailable) configureIntent(context, appWidgetId) else clickIntent(context, appWidgetId)
                )
            }
        }

        private fun labelFor(context: Context, target: QuickEventTarget): String? {
            val core = DefaultQuickEventCore(context)
            return when (target) {
                is QuickEventTarget.Template -> core.readTemplateById(target.templateId)
                    ?.takeIf { it.deletedAtMs == null && !it.isArchived }
                    ?.title
                is QuickEventTarget.Macro -> core.readMacroById(target.macroId)
                    ?.takeIf { it.deletedAtMs == null && !it.isArchived }
                    ?.title
            }?.ifBlank { context.getString(R.string.widget_quick_event_untitled) }
        }

        private fun configureIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, QuickEventWidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun clickIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, QuickEventWidgetClickActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
