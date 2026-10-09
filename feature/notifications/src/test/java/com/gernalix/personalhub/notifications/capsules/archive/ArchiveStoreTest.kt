package com.gernalix.personalhub.notifications.capsules.archive
import android.app.Notification
import android.app.Person
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class ArchiveStoreTest {
    private lateinit var store: ArchiveStore
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun setup(){ context.noBackupFilesDir.resolve("notifications.sqlite").delete(); store=ArchiveStore(context) }
    @After fun close(){store.close()}
    private fun snapshot(key:String="key", text:String="hello", captured:String="2026-10-09T10:00:00Z", app:String="test.app")=JSONObject().put("package",app).put("key",key).put("text",text).put("captured",captured)
    @Test fun appendOnlyRestartRemovalAndReconnection(){
        val first=store.append(snapshot(),"POSTED")
        store.append(snapshot(text="edited"),"POSTED");store.append(snapshot(),"REMOVED",7)
        assertEquals(listOf("REMOVED","UPDATED","POSTED"),store.query().map{it.kind})
        assertEquals("hello",store.event(first)!!.snapshot.getString("text"))
        try {store.writableDatabase.execSQL("UPDATE events SET kind='REMOVED'");fail()}catch(_:android.database.SQLException){}
        try {store.writableDatabase.execSQL("DELETE FROM events");fail()}catch(_:android.database.SQLException){}
        store.close();store=ArchiveStore(context)
        assertEquals(3,store.query().size)
        store.connected();store.append(snapshot(),"OBSERVED");store.append(snapshot(),"POSTED")
        assertEquals("UPDATED",store.query().first().kind)
        assertEquals(5,store.query().size)
    }
    @Test fun ftsAppDateAndSafeQuery(){
        store.append(snapshot(text="hello world"),"POSTED")
        store.append(snapshot(key="b",text="other",app="second",captured="2026-10-08T10:00:00Z"),"POSTED")
        assertEquals(1,store.query("hello", "test.app", "2026-10-09T00:00:00Z","2026-10-10T00:00:00Z").size)
        assertEquals(0,store.query("hello","second").size)
        assertEquals(0,store.query("quote OR secret").size)
        assertEquals(2,store.query("\"'").size)
        assertEquals("2026-03-28T23:00:00Z" to "2026-03-29T22:00:00Z",archiveDateBounds("2026-03-29","2026-03-29",ZoneId.of("Europe/Copenhagen")))
    }
    @Test fun structuredAndMissingNotificationFields(){
        val sender=Person.Builder().setName("Same name").setKey("sender-id").build()
        val n=Notification.Builder(context,"channel").setStyle(Notification.MessagingStyle(Person.Builder().setName("Me").build()).addMessage("hello",1000,sender)).build()
        val sbn=StatusBarNotification("test.app","test.app",1,null,1,1,0,n,android.os.Process.myUserHandle(),2000)
        val s=NotificationSnapshot.capture(sbn,3000,"thread")
        assertEquals("sender-id",s.getJSONArray("messages").getJSONObject(0).getJSONObject("sender").getString("key"))
        assertEquals("1970-01-01T00:00:01Z",s.getJSONArray("messages").getJSONObject(0).getString("timestamp"))
        assertEquals("thread",s.getString("conversation"))
        assertEquals(0,NotificationSnapshot.capture(StatusBarNotification("test.app","test.app",2,null,1,1,0,Notification(),android.os.Process.myUserHandle(),2000)).getJSONArray("messages").length())
    }
    @Test fun pagingUsesCapturedTimeWithNonMonotonicClock(){
        repeat(105){i->store.append(snapshot(key="$i", captured=if(i==104)"2026-10-08T10:00:00Z" else "2026-10-09T10:00:00Z"),"POSTED")}
        val first=store.query();val rest=store.query(before=first.last().id)
        assertEquals(100,first.size);assertEquals(5,rest.size);assertEquals(105,(first+rest).map{it.id}.distinct().size)
    }
    @Test fun coherentLocalExport(){
        store.append(snapshot(),"POSTED")
        val out=java.io.ByteArrayOutputStream();store.export(out)
        assertEquals("SQLite format 3",out.toByteArray().take(15).map{it.toInt().toChar()}.joinToString(""))
        assertFalse(context.noBackupFilesDir.resolve("notifications.sqlite.export").exists())
    }
}
