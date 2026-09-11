package com.gernalix.sostanze.widget

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
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.sostanze.R
import kotlinx.coroutines.runBlocking

class SubstancesWidgetConfigureActivity : Activity() {
    private var selected: Long? = null
    private lateinit var saveButton: Button
    private lateinit var resultGroup: RadioGroup
    private lateinit var emptyResults: TextView
    private lateinit var choices: List<SubstancesWidgetChoice>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        choices = SubstancesWidgetPicker.choices(
            runBlocking { PersonalHubDatabase.get(applicationContext).dao().allSubstances() }
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.widget_substances_choose)
            textSize = 20f
        })
        val search = EditText(this).apply {
            hint = getString(R.string.widget_substances_search)
            isSingleLine = true
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        resultGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        emptyResults = TextView(this).apply {
            text = getString(R.string.widget_substances_no_results)
        }
        val scroller = ScrollView(this).apply {
            isFillViewport = false
            addView(resultGroup)
        }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(emptyResults)
        saveButton = Button(this).apply {
            text = getString(R.string.widget_substances_save)
            isEnabled = false
            setOnClickListener {
                val substanceId = selected ?: return@setOnClickListener
                SubstancesWidgetPrefs.save(applicationContext, appWidgetId, substanceId)
                SubstancesWidgetProvider.updateOne(applicationContext, appWidgetId)
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
            emptyResults.text = getString(R.string.widget_substances_empty)
            emptyResults.visibility = android.view.View.VISIBLE
            saveButton.isEnabled = false
            selected = null
            return
        }
        val visible = SubstancesWidgetPicker.filter(choices, query)
        selected = SubstancesWidgetPicker.visibleSelection(selected, visible)
        emptyResults.text = getString(R.string.widget_substances_no_results)
        emptyResults.visibility = if (visible.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        visible.forEach { choice ->
            resultGroup.addView(RadioButton(this).apply {
                id = ViewGroup.generateViewId()
                text = choice.title.ifBlank { getString(R.string.widget_substances_untitled) }
                isChecked = selected == choice.substanceId
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        selected = choice.substanceId
                        saveButton.isEnabled = true
                    }
                }
            })
        }
        saveButton.isEnabled = selected != null
    }
}
