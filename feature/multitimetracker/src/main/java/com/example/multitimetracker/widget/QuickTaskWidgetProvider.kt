// v471
package com.example.multitimetracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.multitimetracker.R

class QuickSessionWidgetProvider : AppWidgetProvider() {

    companion object {
        // Dynamic receiver inside the app uses this to refresh UI when the widget writes a new snapshot.
        const val ACTION_SNAPSHOT_CHANGED = "com.example.multitimetracker.ACTION_SNAPSHOT_CHANGED"

        private fun buildClickIntent(context: Context): PendingIntent {
            // Use a tiny transparent Activity to reliably run the quick-session logic + haptics + toast,
            // then open the app focused on the created session.
            val intent = Intent(context, QuickSessionWidgetClickActivity::class.java)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun updateAllWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val rv = RemoteViews(context.packageName, R.layout.widget_quick_task)
            rv.setOnClickPendingIntent(R.id.widgetRoot, buildClickIntent(context))
            appWidgetManager.updateAppWidget(appWidgetIds, rv)
        }

    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAllWidgets(context, appWidgetManager, appWidgetIds)
    }

    // No onReceive logic needed: click is handled by QuickSessionWidgetClickActivity.
}


