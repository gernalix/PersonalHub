// v471
// v349
package com.example.multitimetracker.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import com.example.multitimetracker.MainActivity
import com.example.multitimetracker.R

/**
 * A tiny transparent Activity used as a reliable place to trigger haptics and toast.
 * Some launchers/ROMs suppress Toast/Vibration when triggered from an AppWidgetProvider.
 */
class QuickSessionWidgetClickActivity : Activity() {

    companion object {
        private fun vibrateStrong(context: Context) {
            runCatching {
                val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                if (vibrator?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(60, 255))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(60)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appCtx = applicationContext

        // Start a new running session immediately.
        val result = QuickSessionRunner.run(appCtx)

        // Open the app (user can immediately see/stop the running session).
        if (result is QuickSessionRunner.Result.Success) {
            vibrateStrong(appCtx)
            Toast.makeText(appCtx, getString(R.string.quick_session_started), Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(appCtx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        } else {
            Toast.makeText(appCtx, getString(R.string.quick_session_start_failed), Toast.LENGTH_SHORT).show()
        }

        // Close immediately (no UI).
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

