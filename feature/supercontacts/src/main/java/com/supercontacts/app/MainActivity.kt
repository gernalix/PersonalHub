package com.supercontacts.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supercontacts.app.ui.app.CallOverlaySignal
import com.supercontacts.app.ui.app.SuperContactsApp
import com.supercontacts.app.ui.theme.SuperContactsTheme

class MainActivity : ComponentActivity() {
    private var latestIntent by mutableStateOf<Intent?>(null)
    private var callOverlaySignal by mutableStateOf<CallOverlaySignal?>(null)
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        latestIntent = intent
        handleCallOverlayIntent(intent)
        enableEdgeToEdge()
        setContent {
            SuperContactsTheme {
                SuperContactsApp(
                    launchIntent = latestIntent,
                    callOverlaySignal = callOverlaySignal,
                    onCallPermissionsReady = ::registerPhoneStateListenerIfAllowed,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        latestIntent = intent
        handleCallOverlayIntent(intent)
    }

    override fun onDestroy() {
        phoneStateListener?.let { listener ->
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
        }
        phoneStateListener = null
        super.onDestroy()
    }

    private fun registerPhoneStateListenerIfAllowed() {
        if (!hasAnyCallPermission()) return
        if (phoneStateListener != null) return
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated by Android; retained for broad device compatibility.")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    CallSystemOverlayController.show(this@MainActivity, phoneNumber)
                } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                    CallSystemOverlayController.dismiss()
                }
            }
        }
        phoneStateListener = listener
        @Suppress("DEPRECATION")
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun handleCallOverlayIntent(intent: Intent?) {
        if (intent?.action != CallStateReceiver.ACTION_CALL_OVERLAY) return
        showCallOverlay(intent.getStringExtra(CallStateReceiver.EXTRA_PHONE_NUMBER))
    }

    private fun showCallOverlay(phoneNumber: String?) {
        callOverlaySignal = CallOverlaySignal(
            sequence = System.currentTimeMillis(),
            phoneNumber = phoneNumber,
        )
    }

    private fun hasAnyCallPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
}
