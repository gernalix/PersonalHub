package com.example.multitimetracker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.gernalix.personalhub.core.alerts.HubAlarmPlatform
import com.gernalix.personalhub.core.alerts.HubAlarmPrecision
import com.gernalix.personalhub.core.alerts.HubNotificationPlatform
import com.gernalix.personalhub.core.database.DatabaseProfiles

object TimeFenceTimerScheduler {
    fun canScheduleExactAlarms(context: Context): Boolean =
        HubAlarmPlatform.canScheduleExact(context)

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val packageUri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = packageUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = packageUri
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
    }

    fun scheduleTimedSession(
        context: Context,
        sessionId: Long,
        fireAtMs: Long,
        alarmStyle: Boolean = false,
    ) {
        cancelTimedSession(context, sessionId)
        val pi = timedSessionPendingIntent(
            context = context,
            sessionId = sessionId,
            legacyIdentity = false,
        )
        val showPi = alarmClockShowIntent(
            context = context,
            requestCode = sessionId.toInt(),
            data = Uri.parse("mtt://timed-session/$sessionId/show"),
        )
        scheduleExact(context, fireAtMs, pi, showPi, alarmStyle)
    }

    fun cancelTimedSession(context: Context, sessionId: Long) {
        cancelPendingIntent(context, timedSessionPendingIntent(context, sessionId, legacyIdentity = false))
        cancelPendingIntent(context, timedSessionPendingIntent(context, sessionId, legacyIdentity = true))
    }

    fun scheduleRandomAlert(
        context: Context,
        identity: String,
        fireAtMs: Long,
        title: String,
        message: String,
    ) {
        if (fireAtMs <= System.currentTimeMillis()) return
        val pi = randomAlertPendingIntent(context, identity, fireAtMs, title, message)
        val showPi = alarmClockShowIntent(
            context = context,
            requestCode = (identity.hashCode() xor fireAtMs.hashCode()),
            data = Uri.parse("mtt://random-alert/$identity/$fireAtMs/show"),
        )
        scheduleExact(context, fireAtMs, pi, showPi, alarmStyle = false)
    }

    fun cancelRandomAlert(context: Context, identity: String, fireAtMs: Long) {
        cancelPendingIntent(context, randomAlertPendingIntent(context, identity, fireAtMs, "", ""))
    }

    fun cancelLegacyTimerAlert(
        context: Context,
        ruleId: Long,
        sessionId: Long,
        expectedSessionStartAtMs: Long,
    ) {
        cancelPendingIntent(
            context,
            legacyTimerAlertPendingIntent(
                context = context,
                ruleId = ruleId,
                sessionId = sessionId,
                expectedSessionStartAtMs = expectedSessionStartAtMs,
                legacyIdentity = false,
            )
        )
        cancelPendingIntent(
            context,
            legacyTimerAlertPendingIntent(
                context = context,
                ruleId = ruleId,
                sessionId = sessionId,
                expectedSessionStartAtMs = expectedSessionStartAtMs,
                legacyIdentity = true,
            )
        )
    }

    private fun immutableFlags(base: Int): Int =
        HubNotificationPlatform.pendingIntentFlags(base)

    private fun cancelPendingIntent(context: Context, pi: PendingIntent) {
        HubAlarmPlatform.cancel(context, pi)
    }

    private fun alarmClockShowIntent(
        context: Context,
        requestCode: Int,
        data: Uri,
    ): PendingIntent {
        val profileId = DatabaseProfiles.activeProfileId(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            this.data = data.buildUpon().appendQueryParameter("profile_id", profileId).build()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode xor profileId.hashCode(),
            intent,
            immutableFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    }

    private fun timedSessionPendingIntent(
        context: Context,
        sessionId: Long,
        legacyIdentity: Boolean,
    ): PendingIntent {
        val profileId = DatabaseProfiles.activeProfileId(context)
        val intent = Intent(context, TimeFenceTimerReceiver::class.java).apply {
            action = TimeFenceTimerReceiver.ACTION_FIRE_TIMED_SESSION
            putExtra(TimeFenceTimerReceiver.EXTRA_SESSION_ID, sessionId)
            if (!legacyIdentity) {
                putExtra(TimeFenceTimerReceiver.EXTRA_PROFILE_ID, profileId)
                data = Uri.parse("mtt://timed-session/$profileId/$sessionId")
            }
        }
        return PendingIntent.getBroadcast(
            context,
            if (legacyIdentity) sessionId.toInt() else sessionId.toInt() xor profileId.hashCode(),
            intent,
            immutableFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    }

    private fun legacyTimerAlertPendingIntent(
        context: Context,
        ruleId: Long,
        sessionId: Long,
        expectedSessionStartAtMs: Long,
        legacyIdentity: Boolean,
    ): PendingIntent {
        val profileId = DatabaseProfiles.activeProfileId(context)
        val intent = Intent(context, TimeFenceTimerReceiver::class.java).apply {
            action = LEGACY_ACTION_FIRE_TIMER
            if (!legacyIdentity) {
                putExtra(TimeFenceTimerReceiver.EXTRA_PROFILE_ID, profileId)
                data = Uri.parse("mtt://time-fence/$profileId/$ruleId/$sessionId/$expectedSessionStartAtMs")
            }
        }

        val legacyRequestCode = (ruleId xor sessionId xor expectedSessionStartAtMs).hashCode()
        val requestCode = if (legacyIdentity) legacyRequestCode else legacyRequestCode xor profileId.hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            immutableFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    }

    private fun randomAlertPendingIntent(
        context: Context,
        identity: String,
        fireAtMs: Long,
        title: String,
        message: String,
    ): PendingIntent {
        val profileId = DatabaseProfiles.activeProfileId(context)
        val intent = Intent(context, TimeFenceTimerReceiver::class.java).apply {
            action = TimeFenceTimerReceiver.ACTION_RANDOM_ALERT
            data = Uri.parse("mtt://random-alert/$profileId/$identity/$fireAtMs")
            putExtra(TimeFenceTimerReceiver.EXTRA_PROFILE_ID, profileId)
            putExtra(TimeFenceTimerReceiver.EXTRA_RANDOM_ALERT_TITLE, title)
            putExtra(TimeFenceTimerReceiver.EXTRA_RANDOM_ALERT_TEXT, message)
            putExtra(TimeFenceTimerReceiver.EXTRA_NOTIFICATION_ID, (identity.hashCode() xor fireAtMs.hashCode()))
        }
        return PendingIntent.getBroadcast(
            context,
            identity.hashCode() xor fireAtMs.hashCode() xor profileId.hashCode(),
            intent,
            immutableFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    }

    private fun scheduleExact(
        context: Context,
        fireAtMs: Long,
        pi: PendingIntent,
        showPi: PendingIntent,
        alarmStyle: Boolean = false,
    ) {
        val scheduled = HubAlarmPlatform.schedule(
            context = context,
            triggerAtMs = fireAtMs,
            operation = pi,
            precision = HubAlarmPrecision.EXACT,
            showIntent = showPi,
            alarmStyle = alarmStyle,
        )
        if (!scheduled) {
            Log.e("MTT_TIMER", "Unable to schedule timed session fireAtMs=$fireAtMs")
        }
    }

    private const val LEGACY_ACTION_FIRE_TIMER = "com.example.multitimetracker.ACTION_TIMEFENCE_TIMER"
}
