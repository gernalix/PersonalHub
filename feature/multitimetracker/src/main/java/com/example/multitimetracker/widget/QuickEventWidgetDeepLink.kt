package com.example.multitimetracker.widget

import android.content.Intent
import android.net.Uri
import com.example.multitimetracker.core.quickevent.QuickEventTarget
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract

object QuickEventWidgetDeepLink {
    private const val MODULE_ID = "timer"
    private const val PARAM_TEMPLATE = "quickEventTemplateId"
    private const val PARAM_MACRO = "quickEventMacroId"

    fun uriFor(target: QuickEventTarget): Uri =
        when (target) {
            is QuickEventTarget.Template -> HubDeepLinkContract.moduleUri(
                MODULE_ID,
                PARAM_TEMPLATE to target.templateId.toString(),
            )
            is QuickEventTarget.Macro -> HubDeepLinkContract.moduleUri(
                MODULE_ID,
                PARAM_MACRO to target.macroId.toString(),
            )
        }

    fun requestFrom(intent: Intent?): QuickEventTarget? {
        val uri = intent?.data?.takeIf { HubDeepLinkContract.isModuleUri(it, MODULE_ID) } ?: return null
        uri.getQueryParameter(PARAM_TEMPLATE)?.toLongOrNull()?.let { return QuickEventTarget.Template(it) }
        uri.getQueryParameter(PARAM_MACRO)?.toLongOrNull()?.let { return QuickEventTarget.Macro(it) }
        return null
    }
}
