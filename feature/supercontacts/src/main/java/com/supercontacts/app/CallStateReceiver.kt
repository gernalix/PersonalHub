package com.supercontacts.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING,
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                @Suppress("DEPRECATION")
                val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                CallSystemOverlayController.show(context, phoneNumber)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                CallSystemOverlayController.dismiss()
            }
        }
    }

    companion object {
        const val ACTION_CALL_OVERLAY = "com.supercontacts.app.action.CALL_OVERLAY"
        const val EXTRA_PHONE_NUMBER = "com.supercontacts.app.extra.PHONE_NUMBER"
    }
}
