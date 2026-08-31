package com.supercontacts.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.supercontacts.app.data.repository.CallOverlayContactMatch
import com.supercontacts.app.data.repository.AppContainer
import com.supercontacts.app.data.repository.ContactDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CallSystemOverlayController {
    private const val TAG = "SC_CallOverlay"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    fun show(context: Context, phoneNumber: String?) {
        val appContext = context.applicationContext
        scope.launch {
            if (!CallOverlayPermission.canDrawOverlays(appContext)) {
                Log.w(TAG, "Cannot show system call overlay: SYSTEM_ALERT_WINDOW is not allowed")
                requestOverlayPermission(appContext)
                return@launch
            }
            val match: CallOverlayContactMatch? = withContext(Dispatchers.IO) {
                phoneNumber
                    ?.takeIf { it.isNotBlank() }
                    ?.let { AppContainer.contactsRepository(appContext).findContactForCallOverlay(it) }
            }
            showResolved(appContext, phoneNumber, match)
        }
    }

    fun dismiss() {
        scope.launch {
            val view = overlayView ?: return@launch
            runCatching {
                windowManager?.removeView(view)
            }.onFailure { error ->
                Log.w(TAG, "Failed to remove system call overlay", error)
            }
            overlayView = null
            windowManager = null
            Log.i(TAG, "System call overlay dismissed")
        }
    }

    private fun showResolved(context: Context, phoneNumber: String?, match: CallOverlayContactMatch?) {
        dismissCurrent()
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = createOverlayView(context, phoneNumber, match)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 72
        }
        runCatching {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            Log.i(TAG, "System call overlay shown number=${phoneNumber.orEmpty()} matched=${match != null}")
        }.onFailure { error ->
            Log.e(TAG, "Failed to show system call overlay", error)
            requestOverlayPermission(context)
        }
    }

    private fun dismissCurrent() {
        val current = overlayView ?: return
        runCatching {
            windowManager?.removeView(current)
        }
        overlayView = null
        windowManager = null
    }

    private fun createOverlayView(
        context: Context,
        phoneNumber: String?,
        match: CallOverlayContactMatch?,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(35, 30, 46))
            elevation = dp(12).toFloat()
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            addView(card)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = context.getString(com.supercontacts.app.R.string.call_overlay_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeButton = Button(context).apply {
            text = "X"
            minWidth = dp(48)
            minHeight = dp(48)
            setOnClickListener { dismiss() }
            contentDescription = context.getString(com.supercontacts.app.R.string.close)
        }
        header.addView(title)
        header.addView(closeButton)
        card.addView(header)

        TextView(context).apply {
            text = match?.displayName ?: phoneNumber?.takeIf { it.isNotBlank() }
                ?: context.getString(com.supercontacts.app.R.string.call_overlay_number_unknown)
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
            card.addView(this)
        }
        TextView(context).apply {
            text = match?.phone?.takeIf { it.isNotBlank() }
                ?: phoneNumber?.takeIf { it.isNotBlank() }
                ?: context.getString(com.supercontacts.app.R.string.call_overlay_contact_not_found)
            setTextColor(Color.rgb(230, 222, 245))
            textSize = 14f
            setPadding(0, dp(2), 0, dp(8))
            card.addView(this)
        }
        if (match != null) {
            Button(context).apply {
                text = context.getString(com.supercontacts.app.R.string.call_overlay_open_contact)
                minHeight = dp(48)
                setOnClickListener {
                    openContact(context, match.publicId)
                }
                card.addView(this)
            }
        }
        return root
    }

    private fun openContact(context: Context, publicId: String) {
        val uri = Uri.parse(ContactDeepLink.create(publicId))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
        Log.i(TAG, "Open contact requested from system call overlay")
    }

    private fun requestOverlayPermission(context: Context) {
        runCatching {
            context.startActivity(CallOverlayPermission.settingsIntent(context))
        }.onFailure { error ->
            Log.w(TAG, "Unable to open overlay permission settings", error)
        }
    }
}
