package com.example.multitimetracker.ui.util

import android.content.Context
import android.widget.Toast
import kotlin.random.Random
import java.util.Calendar

object RewardToastManager {

    private const val PREFS = "reward_toast_prefs"
    private const val KEY_LAST_SHOWN = "last_shown"
    private const val KEY_DAY_ID = "day_id"
    private const val KEY_HOUR_ID = "hour_id"
    private const val KEY_TODAY_COUNT = "today_count"
    private const val KEY_HOUR_COUNT = "hour_count"
    private const val KEY_LIFETIME_COUNT = "lifetime_count"

    private const val BASE_PROBABILITY = 0.25f // 20–30%
    private const val MAX_VARIANTS = 3

    fun onTaskCreated(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // compute day/hour identifiers in local time without java.time (max compatibility)
        val cal = Calendar.getInstance()
        val dayId = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val hourId = dayId * 100 + cal.get(Calendar.HOUR_OF_DAY)

        val prevDayId = prefs.getInt(KEY_DAY_ID, -1)
        val prevHourId = prefs.getInt(KEY_HOUR_ID, -1)

        var todayCount = prefs.getInt(KEY_TODAY_COUNT, 0)
        var hourCount = prefs.getInt(KEY_HOUR_COUNT, 0)
        var lifetime = prefs.getInt(KEY_LIFETIME_COUNT, 0)

        if (dayId != prevDayId) todayCount = 0
        if (hourId != prevHourId) hourCount = 0

        todayCount += 1
        hourCount += 1
        lifetime += 1

        val lastShown = prefs.getBoolean(KEY_LAST_SHOWN, false)

        // Decide whether to show toast (never two consecutive)
        val show = (!lastShown) && (Random.nextFloat() < BASE_PROBABILITY)

        // Persist counters + last-shown flag (if we didn't show, it becomes false to re-enable next time)
        prefs.edit()
            .putInt(KEY_DAY_ID, dayId)
            .putInt(KEY_HOUR_ID, hourId)
            .putInt(KEY_TODAY_COUNT, todayCount)
            .putInt(KEY_HOUR_COUNT, hourCount)
            .putInt(KEY_LIFETIME_COUNT, lifetime)
            .putBoolean(KEY_LAST_SHOWN, show)
            .apply()

        if (!show) return

        val variant = Random.nextInt(MAX_VARIANTS)
        val msg = when (variant) {
            0 -> "${todayCount} task oggi"
            1 -> "${hourCount}° task questa ora"
            else -> "${lifetime} task totali"
        }

        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
