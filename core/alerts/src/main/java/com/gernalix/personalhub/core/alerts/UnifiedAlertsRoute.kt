package com.gernalix.personalhub.core.alerts

import android.content.Context
import android.content.Intent
import android.net.Uri

/** One PersonalHub Alerts route for every registered compatible domain. */
object UnifiedAlertsRoute {
    data class Request(val domain: AlertDomain? = null, val entityId: String? = null)

    fun intent(context: Context, domain: AlertDomain? = null, entityId: String? = null): Intent {
        val uri = Uri.Builder().scheme("personalhub").authority("module").path("/timer")
            .appendQueryParameter("alerts", domain?.moduleId ?: "all")
        if (entityId != null) uri.appendQueryParameter("entityId", entityId)
        return Intent(Intent.ACTION_VIEW, uri.build()).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    fun parse(intent: Intent?): Request? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "personalhub" || uri.host != "module" || uri.path != "/timer") return null
        val raw = uri.getQueryParameter("alerts") ?: return null
        val domain = if (raw == "all") null else AlertDomain.fromModuleId(raw) ?: return null
        val entityId = uri.getQueryParameter("entityId")
            ?: uri.getQueryParameter("placeId") // backward-compatible deep link
        return Request(domain, entityId?.takeIf(String::isNotBlank))
    }
}