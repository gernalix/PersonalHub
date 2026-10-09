package com.gernalix.personalhub.notifications.capsules.archive
import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35]) @SQLiteMode(SQLiteMode.Mode.NATIVE)
class CollectorServiceTest {
    @Test fun callbacksPersistOrderedSnapshotsAsynchronously() = runBlocking {
        val context:Context=ApplicationProvider.getApplicationContext()
        context.noBackupFilesDir.resolve("notifications.sqlite").delete()
        val lifecycle=Robolectric.buildService(CollectorService::class.java).create()
        val service=lifecycle.get();val store=ArchiveStore.get(context)
        fun notification(text:String)=StatusBarNotification("synthetic.app","synthetic.app",5,null,1,1,0,
            Notification.Builder(context,"synthetic").setContentTitle("Synthetic").setContentText(text).build(),android.os.Process.myUserHandle(),1000)
        service.onNotificationPosted(notification("first"));service.onNotificationPosted(notification("second"));service.onNotificationRemoved(notification("second"))
        withTimeout(5000){ArchiveStore.changes.first { store.query().size==3 }}
        val rows=store.query();assertEquals(listOf("REMOVED","UPDATED","POSTED"),rows.map{it.kind})
        assertEquals("first",rows.last().snapshot.getString("text"))
        lifecycle.destroy()
        val writerScope=CollectorService::class.java.getDeclaredField("scope").apply{isAccessible=true}.get(service) as kotlinx.coroutines.CoroutineScope
        withTimeout(5000){writerScope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach{it.join()}}
        store.close()
        ArchiveStore::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        ArchiveStore(context).use { assertEquals(3,it.query().size) }
    }
}
