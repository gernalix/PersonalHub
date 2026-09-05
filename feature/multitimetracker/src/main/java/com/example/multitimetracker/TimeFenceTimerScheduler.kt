package com.example.multitimetracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.AlarmManagerCompat

object TimeFenceTimerScheduler {
    enum class SchedulePath {
        EXACT,
        EXACT_IDLE,
        ALARM_CLOCK,
        FAILURE,
    }

    data class ScheduleResult(
        val scheduled: Boolean,
        val path: SchedulePath,
        val requestedAtMs: Long,
        val fireAtMs: Long,
        val failureMessage: String? = null,
    )

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return am.canScheduleExactAlarms()
    }

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
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
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
        setExact(am, fireAtMs, pi, showPi, alarmStyle)
    }

    fun cancelTimedSession(context: Context, sessionId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        cancelPendingIntent(am, timedSessionPendingIntent(context, sessionId, legacyIdentity = false))
        cancelPendingIntent(am, timedSessionPendingIntent(context, sessionId, legacyIdentity = true))
    }

    fun schedule(
        context: Context,
        ruleId: Long,
        sessionId: Long,
        expectedSessionStartAtMs: Long,
        fireAtMs: Long,
        title: String,
        message: String,
    ): ScheduleResult {
        val requestedAtMs = System.currentTimeMillis()
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return ScheduleResult(
                scheduled = false,
                path = SchedulePath.FAILURE,
                requestedAtMs = requestedAtMs,
                fireAtMs = fireAtMs,
                failureMessage = "AlarmManager unavailable",
            )
        cancel(context, ruleId, sessionId, expectedSessionStartAtMs)
        val pi = scheduledTimeFencePendingIntent(
            context = context,
            ruleId = ruleId,
            sessionId = sessionId,
            expectedSessionStartAtMs = expectedSessionStartAtMs,
            title = title,
            message = message,
            legacyIdentity = false,
        )
        val showPi = alarmClockShowIntent(
            context = context,
            requestCode = (ruleId xor sessionId xor expectedSessionStartAtMs).hashCode(),
            data = Uri.parse("mtt://time-fence/$ruleId/$sessionId/$expectedSessionStartAtMs/show"),
        )
        return setExactTimerAlert(
            am = am,
            fireAtMs = fireAtMs,
            pi = pi,
            showPi = showPi,
            requestedAtMs = requestedAtMs,
            ruleId = ruleId,
            sessionId = sessionId,
            expectedSessionStartAtMs = expectedSessionStartAtMs,
        )
    }

    fun cancel(
        context: Context,
        ruleId: Long,
        sessionId: Long,
        expectedSessionStartAtMs: Long,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        cancelPendingIntent(
            am,
            scheduledTimeFencePendingIntent(
                context = context,
                ruleId = ruleId,
                sessionId = sessionId,
                expectedSessionStartAtMs = expectedSessionStartAtMs,
                title = "",
                message = "",
                legacyIdentity = false,
            )
        )
        cancelPendingIntent(
            am,
            scheduledTimeFencePendingIntent(
                context = context,
                ruleId = ruleId,
                sessionId = sessionId,
                expectedSessionStartAtMs = expectedSessionStartAtMs,
                title = "",
                message = "",
                legacyIdentity = true,
            )
        )
    }

    private fun immutableFlags(base: Int): Int {
        return base or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    }

    private fun cancelPendingIntent(am: AlarmManager, pi: PendingIntent) {
        am.cancel(pi)
        pi.cancel()
    }

    private fun alarmClockShowIntent(
        context: Context,
        requestCode: Int,
        data: Uri,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            this.data = data
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            immutableFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    }

    private fun timedSessionPendingIntent(
        context: Context,
        sessionId: Long,
        legacyIdentity: Boolean,
    ): PendingIntent {
        val intent = Intent(context, TimeFenceTimerReceiver::class.java).apply {
            action = TimeFenceTimerReceiver.ACTION_FIRE_TIMED_SESSION
            putExtra(TimeFenceTimerReceiver.EXTRA_SESSION_ID, sessionId)
            if (!legacyIdentity) {
                data = Uri.parse("mtt://timed-session/$sessionId")
            }
        }
        return PendingIntent.getBroadcast(
            context,
            sessionId.toInt(),
            intent,
            immutableFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    }

    private fun scheduledTimeFencePendingIntent(
        context: Context,
        ruleId: Long,
        sessionId: Long,
        expectedSessionStartAtMs: Long,
        title: String,
        message: String,
        legacyIdentity: Boolean,
    ): PendingIntent {
        val intent = Intent(context, TimeFenceTimerReceiver::class.java).apply {
            action = TimeFenceTimerReceiver.ACTION_FIRE_TIMER
            putExtra(TimeFenceTimerReceiver.EXTRA_RULE_ID, ruleId)
            putExtra(TimeFenceTimerReceiver.EXTRA_ALERT_SESSION_ID, sessionId)
            putExtra(TimeFenceTimerReceiver.EXTRA_EXPECTED_SESSION_START_AT_MS, expectedSessionStartAtMs)
            putExtra(TimeFenceTimerReceiver.EXTRA_TITLE, title)
            putExtra(TimeFenceTimerReceiver.EXTRA_MESSAGE, message)
            if (!legacyIdentity) {
                data = Uri.parse("mtt://time-fence/$ruleId/$sessionId/$expectedSessionStartAtMs")
            }
        }

        val requestCode = (ruleId xor sessionId xor expectedSessionStartAtMs).hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            immutableFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    }

    private fun setExact(
        am: AlarmManager,
        fireAtMs: Long,
        pi: PendingIntent,
        showPi: PendingIntent,
        alarmStyle: Boolean = false,
    ) {
        val exactDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()
        try {
            if (alarmStyle || exactDenied) {
                AlarmManagerCompat.setAlarmClock(am, fireAtMs, showPi, pi)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
            }
        } catch (se: SecurityException) {
            if (!alarmStyle) {
                runCatching { AlarmManagerCompat.setAlarmClock(am, fireAtMs, showPi, pi) }
                    .onSuccess { return }
            }
            Log.w("MTT_TIMER", "Exact alarm denied; falling back to setWindow fireAtMs=$fireAtMs", se)
            val windowMs = 30_000L
            am.setWindow(AlarmManager.RTC_WAKEUP, fireAtMs, windowMs, pi)
        } catch (_: Throwable) {
        }
    }

    private fun setExactTimerAlert(
        am: AlarmManager,
        fireAtMs: Long,
        pi: PendingIntent,
        showPi: PendingIntent,
        requestedAtMs: Long,
        ruleId: Long,
        sessionId: Long,
        expectedSessionStartAtMs: Long,
    ): ScheduleResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                AlarmManagerCompat.setAlarmClock(am, fireAtMs, showPi, pi)
                logTimerSchedule(
                    path = SchedulePath.ALARM_CLOCK,
                    ruleId = ruleId,
                    sessionId = sessionId,
                    expectedSessionStartAtMs = expectedSessionStartAtMs,
                    requestedAtMs = requestedAtMs,
                    fireAtMs = fireAtMs,
                )
                ScheduleResult(true, SchedulePath.ALARM_CLOCK, requestedAtMs, fireAtMs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
                logTimerSchedule(
                    path = SchedulePath.EXACT_IDLE,
                    ruleId = ruleId,
                    sessionId = sessionId,
                    expectedSessionStartAtMs = expectedSessionStartAtMs,
                    requestedAtMs = requestedAtMs,
                    fireAtMs = fireAtMs,
                )
                ScheduleResult(true, SchedulePath.EXACT_IDLE, requestedAtMs, fireAtMs)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
                logTimerSchedule(
                    path = SchedulePath.EXACT,
                    ruleId = ruleId,
                    sessionId = sessionId,
                    expectedSessionStartAtMs = expectedSessionStartAtMs,
                    requestedAtMs = requestedAtMs,
                    fireAtMs = fireAtMs,
                )
                ScheduleResult(true, SchedulePath.EXACT, requestedAtMs, fireAtMs)
            }
        } catch (se: SecurityException) {
            val message = "${se::class.java.simpleName}: ${se.message}"
            Log.w("MTT_TIMER", "Timer alert exact scheduling failed fireAtMs=$fireAtMs", se)
            ScheduleResult(false, SchedulePath.FAILURE, requestedAtMs, fireAtMs, message)
        } catch (t: Throwable) {
            val message = "${t::class.java.simpleName}: ${t.message}"
            Log.e("MTT_TIMER", "Timer alert scheduling failed fireAtMs=$fireAtMs", t)
            ScheduleResult(false, SchedulePath.FAILURE, requestedAtMs, fireAtMs, message)
        }
    }

    private fun logTimerSchedule(
        path: SchedulePath,
        ruleId: Long,
        sessionId: Long,
        expectedSessionStartAtMs: Long,
        requestedAtMs: Long,
        fireAtMs: Long,
    ) {
        Log.i(
            "MTT_TIMER_ALERT",
            "schedule path=${path.name} ruleId=$ruleId sessionId=$sessionId " +
                "sessionStartAtMs=$expectedSessionStartAtMs timerRequestAtMs=$requestedAtMs " +
                "fireAtMs=$fireAtMs requestLeadMs=${fireAtMs - requestedAtMs}"
        )
    }
}
