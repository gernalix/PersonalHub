package com.gernalix.luoghi.capsules.geofence

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gernalix.luoghi.MainActivity
import com.gernalix.luoghi.R
import com.gernalix.luoghi.data.PlaceEntity
import kotlin.math.absoluteValue

@SuppressLint("MissingPermission")
object PlaceGeofenceNotifier {
    const val CHANNEL_ID = "places_geofence"

    fun notifyTransition(context: Context, place: PlaceEntity, transition: PlaceGeofenceTransition, recordedVisit: Boolean) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val title = place.nickname.ifBlank { context.safeString(R.string.unnamed_place, "Place") }
        val text = when {
            recordedVisit && transition == PlaceGeofenceTransition.ENTER -> context.safeString(R.string.geofence_notification_checkin_format, "Checked in at %s.", title)
            recordedVisit && transition == PlaceGeofenceTransition.EXIT -> context.safeString(R.string.geofence_notification_checkout_format, "Checked out from %s.", title)
            transition == PlaceGeofenceTransition.ENTER -> context.safeString(R.string.geofence_notification_enter_format, "Entered %s.", title)
            else -> context.safeString(R.string.geofence_notification_exit_format, "Left %s.", title)
        }
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("personalhub://module/places?placeId=${place.uuid}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(context, notificationId(place.uuid, transition), contentIntent, flags)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(place.uuid, transition), notification)
        }
    }

    fun notifyAmbiguous(context: Context, placeCount: Int) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.safeString(R.string.geofence_ambiguous_title, "Place geofence needs review"))
            .setContentText(context.safeString(R.string.geofence_ambiguous_message_format, "%d geofences fired together.", placeCount))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(AMBIGUOUS_NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.safeString(R.string.geofence_channel_name, "Places geofences"),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.safeString(R.string.geofence_channel_description, "Notifications for Place ENTER and EXIT automation.")
            }
        )
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun notificationId(uuid: String, transition: PlaceGeofenceTransition): Int =
        (uuid.hashCode() xor transition.storageName.hashCode()).absoluteValue

    private const val AMBIGUOUS_NOTIFICATION_ID = 43_190
}

private fun Context.safeString(resId: Int, fallback: String, vararg args: Any): String =
    runCatching {
        if (args.isEmpty()) getString(resId) else getString(resId, *args)
    }.getOrElse {
        if (args.isEmpty()) fallback else fallback.format(*args)
    }
