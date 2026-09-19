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
import com.example.multitimetracker.core.quickevent.QuickEventTarget
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

        val result = QuickEventWidgetTapRunner(
            core = DefaultQuickEventCore(appCtx),
            audit = { _, _, _, _, _ -> Unit },
            afterSuccessfulWrite = {
                PersistentMutationTracker.requestExport(appCtx)
                appCtx.sendBroadcast(
                    Intent(QuickSessionWidgetProvider.ACTION_SNAPSHOT_CHANGED)
                        .setPackage(appCtx.packageName)
                )
            }
        ).run(target)

        when (result) {
            is QuickEventWidgetTapResult.Recorded -> {
                Toast.makeText(appCtx, getString(R.string.quick_event_recorded, result.title), Toast.LENGTH_SHORT).show()
                QuickEventWidgetProvider.updateOne(appCtx, appWidgetId)
            }
            is QuickEventWidgetTapResult.NeedsInput -> openTimerForCompletion(result.target)
            QuickEventWidgetTapResult.Unavailable -> {
                Toast.makeText(appCtx, getString(R.string.widget_quick_event_unavailable), Toast.LENGTH_SHORT).show()
                QuickEventWidgetProvider.updateOne(appCtx, appWidgetId)
            }
            QuickEventWidgetTapResult.Failed -> {
                Toast.makeText(appCtx, getString(R.string.quick_event_write_failed), Toast.LENGTH_SHORT).show()
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
