package com.gernalix.personalhub.core.alerts

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Routes all Alerts entry points through the Timer-owned Alerts screen. */
object UnifiedAlertsRoute {
    enum class Filter { ALL, TIMER, PLACES }

    data class Request(val filter: Filter, val placeId: String? = null)

    fun intent(context: Context, filter: Filter = Filter.ALL, placeId: String? = null): Intent {
        val uri = Uri.Builder().scheme("personalhub").authority("module").path("/timer")
            .appendQueryParameter("alerts", filter.name.lowercase())
        if (placeId != null) uri.appendQueryParameter("placeId", placeId)
        return Intent(Intent.ACTION_VIEW, uri.build()).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    fun parse(intent: Intent?): Request? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "personalhub" || uri.host != "module" || uri.path != "/timer") return null
        val filter = when (uri.getQueryParameter("alerts")) {
            "all" -> Filter.ALL
            "timer" -> Filter.TIMER
            "places" -> Filter.PLACES
            else -> return null
        }
        return Request(filter, uri.getQueryParameter("placeId")?.takeIf(String::isNotBlank))
    }
}
