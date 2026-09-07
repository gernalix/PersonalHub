package com.example.multitimetracker.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.example.multitimetracker.R
import com.example.multitimetracker.core.quickevent.DefaultQuickEventCore
import com.example.multitimetracker.core.quickevent.QuickEventTarget

class QuickEventWidgetConfigureActivity : Activity() {
    private var selected: QuickEventTarget? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val snapshot = DefaultQuickEventCore(applicationContext).readSnapshot()
        val targets = buildList {
            snapshot.templates
                .filter { it.deletedAtMs == null && !it.isArchived }
                .sortedWith(compareBy({ it.sortOrder }, { it.title.lowercase() }))
                .forEach { add(QuickEventTarget.Template(it.id) to it.title) }
            snapshot.macros
                .filter { it.deletedAtMs == null && !it.isArchived }
                .sortedWith(compareBy({ it.sortOrder }, { it.title.lowercase() }))
                .forEach { add(QuickEventTarget.Macro(it.id) to it.title) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.widget_quick_event_choose)
            textSize = 20f
        })
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        if (targets.isEmpty()) {
            group.addView(TextView(this).apply { text = getString(R.string.widget_quick_event_empty) })
        } else {
            targets.forEachIndexed { index, (target, title) ->
                group.addView(RadioButton(this).apply {
                    id = ViewGroup.generateViewId()
                    text = title.ifBlank { getString(R.string.widget_quick_event_untitled) }
                    setOnCheckedChangeListener { _, checked -> if (checked) selected = target }
                    if (index == 0) {
                        isChecked = true
                        selected = target
                    }
                })
            }
        }
        root.addView(group)
        root.addView(Button(this).apply {
            text = getString(R.string.widget_quick_event_save)
            isEnabled = targets.isNotEmpty()
            setOnClickListener {
                val target = selected ?: return@setOnClickListener
                QuickEventWidgetPrefs.save(applicationContext, appWidgetId, target)
                QuickEventWidgetProvider.updateOne(applicationContext, appWidgetId)
                setResult(RESULT_OK, intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                finish()
            }
        })
        setContentView(root)
    }
}
