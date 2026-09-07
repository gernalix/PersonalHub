package com.example.multitimetracker.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.example.multitimetracker.R
import com.example.multitimetracker.core.quickevent.DefaultQuickEventCore
import com.example.multitimetracker.core.quickevent.QuickEventTarget

class QuickEventWidgetConfigureActivity : Activity() {
    private var selected: QuickEventTarget? = null
    private lateinit var saveButton: Button
    private lateinit var resultGroup: RadioGroup
    private lateinit var emptyResults: TextView
    private lateinit var choices: List<QuickEventWidgetChoice>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val snapshot = DefaultQuickEventCore(applicationContext).readSnapshot()
        choices = QuickEventWidgetPicker.ordered(buildList {
            snapshot.templates
                .filter { it.deletedAtMs == null && !it.isArchived }
                .forEach { add(QuickEventWidgetChoice(QuickEventTarget.Template(it.id), it.title, it.sortOrder)) }
            snapshot.macros
                .filter { it.deletedAtMs == null && !it.isArchived }
                .forEach { add(QuickEventWidgetChoice(QuickEventTarget.Macro(it.id), it.title, it.sortOrder)) }
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.widget_quick_event_choose)
            textSize = 20f
        })
        val search = EditText(this).apply {
            hint = getString(R.string.widget_quick_event_search)
            isSingleLine = true
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        resultGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        emptyResults = TextView(this).apply {
            text = getString(R.string.widget_quick_event_no_results)
        }
        val scroller = ScrollView(this).apply {
            isFillViewport = false
            addView(resultGroup)
        }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(emptyResults)
        saveButton = Button(this).apply {
            text = getString(R.string.widget_quick_event_save)
            isEnabled = false
            setOnClickListener {
                val target = selected ?: return@setOnClickListener
                QuickEventWidgetPrefs.save(applicationContext, appWidgetId, target)
                QuickEventWidgetProvider.updateOne(applicationContext, appWidgetId)
                setResult(RESULT_OK, intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                finish()
            }
        }
        root.addView(saveButton)
        setContentView(root)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderChoices(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        renderChoices("")
    }

    private fun renderChoices(query: String) {
        resultGroup.removeAllViews()
        if (choices.isEmpty()) {
            emptyResults.text = getString(R.string.widget_quick_event_empty)
            emptyResults.visibility = android.view.View.VISIBLE
            saveButton.isEnabled = false
            selected = null
            return
        }
        val visible = QuickEventWidgetPicker.filter(choices, query)
        selected = QuickEventWidgetPicker.visibleSelection(selected, visible)
        emptyResults.text = getString(R.string.widget_quick_event_no_results)
        emptyResults.visibility = if (visible.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        visible.forEach { choice ->
            resultGroup.addView(RadioButton(this).apply {
                id = ViewGroup.generateViewId()
                text = choice.title.ifBlank { getString(R.string.widget_quick_event_untitled) }
                isChecked = selected == choice.target
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        selected = choice.target
                        saveButton.isEnabled = true
                    }
                }
            })
        }
        saveButton.isEnabled = selected != null
    }
}
