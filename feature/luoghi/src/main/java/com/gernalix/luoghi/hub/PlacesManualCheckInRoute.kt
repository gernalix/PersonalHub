package com.gernalix.luoghi.hub

import android.content.Context
import android.content.Intent
import com.gernalix.luoghi.MainActivity

object PlacesManualCheckInRoute {
    const val ACTION = "com.gernalix.luoghi.action.MANUAL_CHECK_IN"
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java).setAction(ACTION)
}
