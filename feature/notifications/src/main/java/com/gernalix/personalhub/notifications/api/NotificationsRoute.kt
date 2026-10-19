package com.gernalix.personalhub.notifications.api
import android.content.Context
import android.content.Intent
import com.gernalix.personalhub.notifications.capsules.archive.NotificationsActivity
object NotificationsRoute {
    const val EXTRA_EVENT_ID = "personalhub.notifications.event_id"
    fun intent(context: Context): Intent = Intent(context, NotificationsActivity::class.java)
    fun eventIntent(context: Context, eventId: Long): Intent {
        require(eventId > 0)
        return intent(context).putExtra(EXTRA_EVENT_ID, eventId)
    }
}
