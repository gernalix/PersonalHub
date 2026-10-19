package com.gernalix.luoghi.ui.places

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.gernalix.luoghi.capsules.mapviewer.OsmAndMapPlan

/** Resolves through Android's map-app default, preferring the complete street address. */
internal object PlaceDirections {
    fun intent(address: String?, latitude: Double?, longitude: Double?): Intent? {
        val destination = address?.trim()?.takeIf(String::isNotEmpty)
        val uri = if (destination != null) {
            "geo:0,0?q=${Uri.encode(destination)}"
        } else {
            if (latitude == null || longitude == null || !OsmAndMapPlan.valid(latitude, longitude)) return null
            "geo:$latitude,$longitude?q=$latitude,$longitude"
        }
        return Intent(Intent.ACTION_VIEW, uri.toUri())
    }
}
