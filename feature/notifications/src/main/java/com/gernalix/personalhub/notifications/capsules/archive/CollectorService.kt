package com.gernalix.personalhub.notifications.capsules.archive

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

/** Callbacks detach small text snapshots; a single bounded writer preserves callback order. */
class CollectorService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<() -> Unit>(256)
    override fun onCreate() {
        super.onCreate()
        scope.launch { for (write in queue) try { write() } catch (_: Exception) { failure() } }
    }
    private fun failure() { getSharedPreferences("notification_status",MODE_PRIVATE).edit().putBoolean("capture_error",true).apply() }
    private fun enqueue(write: () -> Unit) { if(queue.trySend(write).isFailure) failure() }
    private fun capture(sbn: StatusBarNotification, kind: String, ranking: RankingMap? = null, reason: Int? = null) {
        try {
            val rank = Ranking()
            val conversation = if(android.os.Build.VERSION.SDK_INT >= 30 && ranking?.getRanking(sbn.key,rank)==true) rank.channel?.conversationId else null
            val snapshot = NotificationSnapshot.capture(sbn, conversation=conversation)
            enqueue { ArchiveStore.get(this).append(snapshot,kind,reason) }
        } catch (_: Exception) { failure() }
    }
    override fun onListenerConnected() {
        enqueue { ArchiveStore.get(this).connected() }
        // Snapshot of current state, never a claim that we observed its original post.
        try { activeNotifications?.forEach { capture(it,"OBSERVED") } } catch (_: Exception) { failure() }
        getSharedPreferences("notification_status",MODE_PRIVATE).edit().putBoolean("connected",true).apply()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) = capture(sbn,"POSTED",rankingMap)
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) = capture(sbn,"REMOVED",rankingMap,reason)
    override fun onListenerDisconnected() {
        getSharedPreferences("notification_status",MODE_PRIVATE).edit().putBoolean("connected",false).apply()
        requestRebind(ComponentName(this,CollectorService::class.java))
    }
    override fun onDestroy() {
        queue.close() // drain detached snapshots; no permanent worker or timer
        super.onDestroy()
    }
}
