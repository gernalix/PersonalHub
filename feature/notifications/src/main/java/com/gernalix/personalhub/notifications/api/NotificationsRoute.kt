package com.gernalix.personalhub.notifications.api
import android.content.Context
import android.content.Intent
import com.gernalix.personalhub.notifications.capsules.archive.NotificationsActivity
object NotificationsRoute {
    fun intent(context: Context): Intent = Intent(context, NotificationsActivity::class.java)
}
