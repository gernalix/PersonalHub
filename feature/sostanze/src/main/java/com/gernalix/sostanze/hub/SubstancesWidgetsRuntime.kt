package com.gernalix.sostanze.hub

import android.content.Context
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Choreographer
import com.gernalix.sostanze.widget.SubstancesWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rehydrate existing widgets after process/launcher recovery and profile handoffs. */
object SubstancesWidgetsRuntime {
    fun install(app: Application) {
        // A resumed screen can be the database gate, followed by its ready recreation.
        // Keep the latest request even when that recreation races an earlier refresh.
        val requests = Channel<Unit>(Channel.CONFLATED)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (requests.receiveCatching().isSuccess) {
                try {
                    restoreActiveProfile(app)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w("SubstancesWidget", "Widget recovery will retry on foreground: ${failure.javaClass.simpleName}")
                }
            }
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                Choreographer.getInstance().postFrameCallback {
                    Choreographer.getInstance().postFrameCallback { requests.trySend(Unit) }
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    suspend fun restoreActiveProfile(context: Context) = withContext(Dispatchers.IO) {
        SubstancesWidgetProvider.updateAll(context.applicationContext)
    }
}
