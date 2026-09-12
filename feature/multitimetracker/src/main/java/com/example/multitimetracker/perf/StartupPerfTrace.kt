package com.example.multitimetracker.perf

import android.os.SystemClock
import android.util.Log
import com.example.multitimetracker.BuildConfig
import java.util.Locale

internal object StartupPerfTrace {
    private const val LOG_TAG = "MTT_STARTUP"
    private val enabled: Boolean
        get() = BuildConfig.DEBUG || BuildConfig.APPLICATION_ID.endsWith(".devicetest")

    private val lock = Any()
    private var appOnCreateNs: Long = 0L
    private var activityOnCreateNs: Long = 0L
    private var firstFrameNs: Long = 0L
    private var homeReadyLogged = false
    private var activityLaunchCount = 0

    fun <T> section(name: String, block: () -> T): T {
        if (!enabled) return block()
        val startNs = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            val durationMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
            Log.i(LOG_TAG, "$name=${formatMs(durationMs)}ms")
        }
    }

    fun mark(message: String) {
        if (enabled) Log.i(LOG_TAG, message)
    }

    fun applicationOnCreate() {
        if (!enabled) return
        synchronized(lock) {
            appOnCreateNs = SystemClock.elapsedRealtimeNanos()
            activityOnCreateNs = 0L
            firstFrameNs = 0L
            homeReadyLogged = false
            activityLaunchCount = 0
        }
        Log.i(LOG_TAG, "application_on_create cold_process=true")
    }

    fun activityOnCreateStart() {
        if (!enabled) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val event = synchronized(lock) {
            val cold = activityLaunchCount == 0
            activityLaunchCount += 1
            activityOnCreateNs = nowNs
            firstFrameNs = 0L
            homeReadyLogged = false
            val appDelta = deltaMs(appOnCreateNs, nowNs)
            "activity_on_create_start cold_start=$cold app_to_activity_ms=${formatNullableMs(appDelta)}"
        }
        Log.i(LOG_TAG, event)
    }

    fun firstFrame() {
        if (!enabled) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val event = synchronized(lock) {
            firstFrameNs = nowNs
            val appDelta = deltaMs(appOnCreateNs, nowNs)
            val activityDelta = deltaMs(activityOnCreateNs, nowNs)
            "first_frame app_to_first_frame_ms=${formatNullableMs(appDelta)} activity_to_first_frame_ms=${formatNullableMs(activityDelta)}"
        }
        Log.i(LOG_TAG, event)
    }

    fun homeReady(state: String, runningSessions: Int) {
        if (!enabled) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val event = synchronized(lock) {
            if (homeReadyLogged) return
            homeReadyLogged = true
            val appDelta = deltaMs(appOnCreateNs, nowNs)
            val activityDelta = deltaMs(activityOnCreateNs, nowNs)
            val firstFrameDelta = deltaMs(firstFrameNs, nowNs)
            "home_ready state=$state running_sessions=$runningSessions " +
                "app_to_home_ready_ms=${formatNullableMs(appDelta)} " +
                "activity_to_home_ready_ms=${formatNullableMs(activityDelta)} " +
                "first_frame_to_home_ready_ms=${formatNullableMs(firstFrameDelta)}"
        }
        Log.i(LOG_TAG, event)
    }

    private fun deltaMs(startNs: Long, endNs: Long): Double? {
        if (startNs <= 0L) return null
        return (endNs - startNs) / 1_000_000.0
    }

    private fun formatNullableMs(value: Double?): String =
        value?.let(::formatMs) ?: "unknown"

    private fun formatMs(value: Double): String =
        String.format(Locale.US, "%.2f", value)
}
