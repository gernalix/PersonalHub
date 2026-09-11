package com.gernalix.sostanze.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.gernalix.personalhub.core.database.HubAutoExport
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.sostanze.R
import com.gernalix.sostanze.data.SostanzeRepository
import kotlinx.coroutines.runBlocking

class SubstancesWidgetClickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appCtx = applicationContext
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val substanceId = SubstancesWidgetPrefs.read(appCtx, appWidgetId)
        if (substanceId == null) {
            openConfiguration(appWidgetId)
            finishWithoutAnimation()
            return
        }

        val result = runBlocking {
            SubstancesWidgetTapRunner(
                repository = SostanzeRepository(PersonalHubDatabase.get(appCtx)),
                afterSuccessfulWrite = { HubAutoExport.request(appCtx) }
            ).run(substanceId)
        }

        when (result) {
            is SubstancesWidgetTapResult.Recorded -> {
                Toast.makeText(appCtx, getString(R.string.recorded, result.title), Toast.LENGTH_SHORT).show()
                SubstancesWidgetProvider.updateOne(appCtx, appWidgetId)
            }
            SubstancesWidgetTapResult.Unavailable -> {
                Toast.makeText(appCtx, getString(R.string.widget_substances_unavailable), Toast.LENGTH_SHORT).show()
                SubstancesWidgetProvider.updateOne(appCtx, appWidgetId)
            }
            SubstancesWidgetTapResult.Failed -> {
                Toast.makeText(appCtx, getString(R.string.widget_substances_write_failed), Toast.LENGTH_SHORT).show()
                SubstancesWidgetProvider.updateOne(appCtx, appWidgetId)
            }
        }
        finishWithoutAnimation()
    }

    private fun openConfiguration(appWidgetId: Int) {
        startActivity(Intent(this, SubstancesWidgetConfigureActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
