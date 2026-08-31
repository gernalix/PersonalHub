package com.supercontacts.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallOverlayDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION_DEBUG_SHOW_CALL_OVERLAY) return
        CallSystemOverlayController.show(
            context = context,
            phoneNumber = intent.getStringExtra(CallStateReceiver.EXTRA_PHONE_NUMBER),
        )
    }

    companion object {
        const val ACTION_DEBUG_SHOW_CALL_OVERLAY = "com.supercontacts.app.action.DEBUG_SHOW_CALL_OVERLAY"
    }
}
