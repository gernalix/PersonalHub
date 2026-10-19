package com.gernalix.sostanze.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.widget.RemoteViews
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.sostanze.R
import kotlinx.coroutines.runBlocking

class SubstancesWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(
                AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                AppWidgetManager.ACTION_APPWIDGET_RESTORED,
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
            )) {
            val pendingResult = goAsync()
            Thread {
                try {
                    if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
                        intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                        updateAll(context.applicationContext)
                    } else {
                        super.onReceive(context.applicationContext, intent)
                    }
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

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        SubstancesWidgetPrefs.restore(context.applicationContext, oldWidgetIds, newWidgetIds)
        // AppWidgetProvider dispatches onUpdate for the new IDs after this callback.
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SubstancesWidgetProvider::class.java))
            updateWidgets(context.applicationContext, manager, ids)
        }

        @Synchronized
        fun updateWidgets(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
            appWidgetIds.forEach { appWidgetId ->
                manager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
            }
        }

        fun updateOne(context: Context, appWidgetId: Int) {
            val manager = AppWidgetManager.getInstance(context)
            updateWidgets(context.applicationContext, manager, intArrayOf(appWidgetId))
        }

        private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            return try {
                configuredViews(context, appWidgetId)
            } catch (failure: Exception) {
                android.util.Log.w("SubstancesWidget", "Widget refresh needs database recovery: ${failure.javaClass.simpleName}")
                RemoteViews(context.packageName, R.layout.widget_substances_action).apply {
                    val label = context.getString(R.string.widget_substances_refresh)
                    setTextViewText(R.id.widgetSubstancesLabel, label)
                    setContentDescription(R.id.widgetSubstancesLabel, label)
                    setOnClickPendingIntent(R.id.widgetSubstancesRoot, PendingIntent.getActivity(
                        context, appWidgetId,
                        Intent(context, com.gernalix.sostanze.MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ))
                }
            }
        }

        private fun configuredViews(context: Context, appWidgetId: Int): RemoteViews {
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
