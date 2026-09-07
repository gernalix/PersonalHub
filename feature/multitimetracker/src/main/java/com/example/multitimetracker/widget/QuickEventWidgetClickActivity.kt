package com.example.multitimetracker.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.example.multitimetracker.MainActivity
import com.example.multitimetracker.R
import com.example.multitimetracker.core.quickevent.DefaultQuickEventCore
import com.example.multitimetracker.core.quickevent.QuickEventExecutionResult
import com.example.multitimetracker.core.quickevent.QuickEventExecutor
import com.example.multitimetracker.core.quickevent.QuickEventTarget
import com.example.multitimetracker.persistence.AuditLogSqlite
import com.example.multitimetracker.persistence.PersistentMutationTracker
import com.example.multitimetracker.util.CapsuleWriteApi

@OptIn(CapsuleWriteApi::class)
class QuickEventWidgetClickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appCtx = applicationContext
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val target = QuickEventWidgetPrefs.read(appCtx, appWidgetId)
        if (target == null) {
            openConfiguration(appWidgetId)
            finishWithoutAnimation()
            return
        }

        val result = runCatching {
            QuickEventExecutor(
                core = DefaultQuickEventCore(appCtx),
                audit = { action, entityType, entityId, summary, payload ->
                    AuditLogSqlite.insert(appCtx, isSystem = false, action = action, entityType = entityType, entityId = entityId, summary = summary, payload = payload)
                },
                afterSuccessfulWrite = {
                    PersistentMutationTracker.requestExport(appCtx)
                }
            ).execute(target)
        }.getOrElse {
            Toast.makeText(appCtx, getString(R.string.quick_event_write_failed), Toast.LENGTH_SHORT).show()
            QuickEventWidgetProvider.updateOne(appCtx, appWidgetId)
            finishWithoutAnimation()
            return
        }

        when (result) {
            is QuickEventExecutionResult.Executed -> {
                val message = if (result.entryIds.size == 1) getString(R.string.quick_event_recorded)
                else getString(R.string.quick_event_macro_recorded, result.entryIds.size)
                Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show()
                QuickEventWidgetProvider.updateOne(appCtx, appWidgetId)
            }
            is QuickEventExecutionResult.NeedsInput -> openTimerForCompletion(result.target)
            is QuickEventExecutionResult.Unavailable -> {
                Toast.makeText(appCtx, getString(R.string.widget_quick_event_unavailable), Toast.LENGTH_SHORT).show()
                QuickEventWidgetProvider.updateOne(appCtx, appWidgetId)
            }
        }
        finishWithoutAnimation()
    }

    private fun openConfiguration(appWidgetId: Int) {
        startActivity(Intent(this, QuickEventWidgetConfigureActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun openTimerForCompletion(target: QuickEventTarget) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = QuickEventWidgetDeepLink.uriFor(target)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }

    private fun finishWithoutAnimation() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
