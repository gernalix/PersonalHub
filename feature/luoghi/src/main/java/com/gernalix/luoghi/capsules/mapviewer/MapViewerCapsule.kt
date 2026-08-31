package com.gernalix.luoghi.capsules.mapviewer

import android.content.Context
import android.content.Intent
import com.gernalix.luoghi.MapViewerActivity
import com.gernalix.luoghi.R
import com.gernalix.luoghi.data.PlaceEntity

object MapViewerCapsule {
    fun globalMapIntent(context: Context, places: List<PlaceEntity>): Intent =
        Intent(MapViewerActivity.ACTION_SHOW_MAP).setClass(context, MapViewerActivity::class.java)
            .putExtra(MapViewerActivity.EXTRA_TITLE, context.getString(R.string.map_default_title))
            .putExtra(MapViewerActivity.EXTRA_CALLER_MODE, "GLOBAL")
            .putExtra(MapViewerActivity.EXTRA_MARKER_MODE, "COMPACT_LABEL")
            .putStringArrayListExtra(
                MapViewerActivity.EXTRA_PLACE_UUIDS,
                ArrayList(places.map { it.uuid }),
            )
            .putStringArrayListExtra(
                MapViewerActivity.EXTRA_LABELS,
                ArrayList(places.map { it.nickname.ifBlank { it.uuid.take(8) } }),
            )
}
