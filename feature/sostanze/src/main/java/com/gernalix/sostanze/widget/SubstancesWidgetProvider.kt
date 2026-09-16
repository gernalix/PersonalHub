package com.gernalix.sostanze.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.sostanze.R
import kotlinx.coroutines.runBlocking

class SubstancesWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val pendingResult = goAsync()
            Thread {
                try {
                    super.onReceive(context.applicationContext, intent)
                } finally {
                    pendingResult.finish()
                }
            }.start()
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateWidgets(context.applicationContext, manager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        SubstancesWidgetPrefs.delete(context.applicationContext, appWidgetIds)
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
            val targetId = SubstancesWidgetPrefs.read(context, appWidgetId)
            val resolvedLabel = targetId?.let { labelFor(context, it) }
            val unavailable = targetId != null && resolvedLabel == null
            val label = resolvedLabel ?: context.getString(
                if (unavailable) R.string.widget_substances_unavailable else R.string.widget_substances_configure
            )
            return RemoteViews(context.packageName, R.layout.widget_substances_action).apply {
                setTextViewText(R.id.widgetSubstancesLabel, label)
                setContentDescription(R.id.widgetSubstancesLabel, label)
                setOnClickPendingIntent(
                    R.id.widgetSubstancesRoot,
                    if (targetId == null || unavailable) configureIntent(context, appWidgetId) else clickIntent(context, appWidgetId)
                )
            }
        }

        private fun labelFor(context: Context, substanceId: Long): String? =
            runBlocking {
                PersonalHubDatabase.get(context).dao().substanceById(substanceId)
                    ?.takeIf { !it.archived }
                    ?.name
                    ?.ifBlank { context.getString(R.string.widget_substances_untitled) }
            }

        private fun configureIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, SubstancesWidgetConfigureActivity::class.java).apply {
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
            val intent = Intent(context, SubstancesWidgetClickActivity::class.java).apply {
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
