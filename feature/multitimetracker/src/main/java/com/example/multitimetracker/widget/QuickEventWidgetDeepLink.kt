package com.example.multitimetracker.widget

import android.content.Intent
import android.net.Uri
import com.example.multitimetracker.core.quickevent.QuickEventTarget

object QuickEventWidgetDeepLink {
    private const val PARAM_TEMPLATE = "quickEventTemplateId"
    private const val PARAM_MACRO = "quickEventMacroId"

    fun uriFor(target: QuickEventTarget): Uri {
        val builder = Uri.Builder().scheme("personalhub").authority("module").path("timer")
        when (target) {
            is QuickEventTarget.Template -> builder.appendQueryParameter(PARAM_TEMPLATE, target.templateId.toString())
            is QuickEventTarget.Macro -> builder.appendQueryParameter(PARAM_MACRO, target.macroId.toString())
        }
        return builder.build()
    }

    fun requestFrom(intent: Intent?): QuickEventTarget? {
        val uri = intent?.data?.takeIf { it.scheme == "personalhub" && it.host == "module" && it.path == "/timer" } ?: return null
        uri.getQueryParameter(PARAM_TEMPLATE)?.toLongOrNull()?.let { return QuickEventTarget.Template(it) }
        uri.getQueryParameter(PARAM_MACRO)?.toLongOrNull()?.let { return QuickEventTarget.Macro(it) }
        return null
    }
}
