package com.gernalix.personalhub.notifications.capsules.archive
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35]) @SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationsTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private lateinit var archive:ArchiveStore
    private lateinit var store:ConversationsStore
    @Before fun setup(){context.noBackupFilesDir.resolve("notifications.sqlite").delete();archive=ArchiveStore(context);store=ConversationsStore(archive)}
    @After fun finish(){archive.close()}
    private fun sender(name:String="Alex",key:String?="sender")=JSONObject().put("name",name).put("key",key)
    private fun message(text:String="hello",time:String?="2026-10-09T10:00:00Z",sender:JSONObject?=sender())=JSONObject().put("text",text).put("timestamp",time).put("sender",sender)
    private fun snapshot(vararg messages:JSONObject,app:String="app.a",thread:String?="thread",key:String="key",group:Boolean=false)=JSONObject()
        .put("package",app).put("key",key).put("user",0).put("captured","2026-10-09T11:00:00Z")
        .put("posted","2026-10-09T11:00:00Z").put("title","Alex").put("shortcut",thread)
        .put("messaging",true).put("isGroup",group).put("messages",JSONArray(messages.toList()))
    private fun append(s:JSONObject,kind:String="POSTED"):Long {val id=archive.append(s,kind);archive.normalizePending();return id}
    @Test fun cumulativeDuplicatesAndDistinctIdenticalTexts(){
        val a=message();val b=message(time="2026-10-09T10:01:00Z")
        append(snapshot(a));append(snapshot(a,b));append(snapshot(a,b))
        assertEquals(2,store.messages().size)
        assertEquals(3,store.sources(store.messages().last().id).size)
        // Two identical tuples in the SAME snapshot have observable multiplicity.
        append(snapshot(a,a,b));assertEquals(3,store.messages().size)
        append(snapshot(a,a,b));assertEquals(3,store.messages().size)
        assertEquals(5,archive.query().size)
    }
    @Test fun groupsHomonymsAndCrossAppRemainSeparate(){
        append(snapshot(message(sender=sender("Alex","one")),message(sender=sender("Alex","two")),group=true))
        append(snapshot(message(sender=sender("Alex","one")),app="app.b"))
        append(snapshot(message(sender=sender("Alex",null)),message(time="2026-10-09T10:01:00Z",sender=sender("Alex",null)),thread="other"))
        assertEquals(3,store.conversations().size)
        assertEquals(5,store.messages().map { it.identity }.distinct().size)
        assertEquals(1,store.conversations().count { it.group })
    }
    @Test fun revisionsPartialAndOutOfOrder(){
        append(snapshot(message("truncated…")))
        append(snapshot(message("complete"),message("older","2026-10-08T10:00:00Z")))
        val rows=store.messages();assertEquals(3,rows.size)
        assertEquals("older",rows.last().text)
        assertTrue(rows.first {it.text=="truncated…"}.partial)
        assertNotNull(rows.first {it.text=="complete"}.revisionOf)
        assertEquals("truncated…",archive.query().last().snapshot.getJSONArray("messages").getJSONObject(0).getString("text"))
    }
    @Test fun reversiblePeopleMappingsAcrossAppsAndProfiles(){
        append(snapshot(message()));append(snapshot(message(),app="app.b"))
        val conversations=store.conversations()
        conversations.forEach { store.map("personal","person-one","conversation",it.id,true) }
        assertEquals(2,store.messages(person="person-one",profile="personal").size)
        assertEquals(0,store.messages(person="person-one",profile="work").size)
        val identity=store.messages().first().identity
        store.map("personal","person-two","identity",identity,true)
        assertEquals(1,store.messages(person="person-two",profile="personal").size)
        store.map("personal","person-one","conversation",conversations.first().id,false)
        assertEquals(1,store.messages(person="person-one",profile="personal").size)
        store.map("personal","person-two","identity",identity,false)
        assertEquals(0,store.messages(person="person-two",profile="personal").size)
        assertEquals(2,store.messages().size);assertEquals(2,store.conversations().size)
    }
    @Test fun idempotentAfterRestartAndBoundedBacklog(){
        repeat(105){archive.append(snapshot(message(time="2026-10-09T10:00:00Z")),"POSTED")}
        assertEquals(100,archive.normalizePending());assertEquals(5,archive.normalizePending());assertEquals(0,archive.normalizePending())
        val rows=store.messages();val sourceCount=store.sources(rows.single().id).size
        assertEquals(100,sourceCount) // provenance UI query is bounded; all 105 remain in the source table
        assertEquals(105,archive.readableDatabase.rawQuery("SELECT COUNT(*) FROM message_sources",null).use { it.moveToFirst();it.getInt(0) })
        archive.close();archive=ArchiveStore(context);store=ConversationsStore(archive)
        assertEquals(0,archive.normalizePending());assertEquals(rows,store.messages());assertEquals(sourceCount,store.sources(rows.single().id).size)
    }
    @Test fun outgoingSummaryMissingAndRemovedNeverInventMessages(){
        val self=sender("Me","self")
        append(snapshot(message(sender=null),message(sender=self)).put("self",self))
        append(snapshot(message()).put("summary",true))
        assertEquals(0,store.messages().size)
        append(snapshot(message()),"REMOVED");assertEquals(0,store.messages().size)
        append(snapshot(message()));append(snapshot(message()),"REMOVED")
        assertEquals(1,store.messages().size)
    }
    @Test fun missingTimesAndWeakThreadsAreExplicitlyUncertain(){
        val s=snapshot(message(time=null,sender=sender("Alex",null)),thread=null)
        append(s);append(s)
        assertEquals(1,store.messages().size);assertEquals("LOW",store.messages().single().confidence)
        assertTrue(store.conversations().single().uncertain)
        append(s,"REMOVED");append(s)
        assertEquals(2,store.conversations().size);assertEquals(2,store.messages().size)
    }
    @Test fun gapObservationSplitsWeakKeysButRetainsProvenThread(){
        val weak=snapshot(message(),thread=null);append(weak);append(weak,"OBSERVED")
        assertEquals(2,store.conversations().size)
        val strong=snapshot(message(),thread="stable",key="strong");append(strong);append(strong,"OBSERVED")
        assertEquals(3,store.conversations().size);assertEquals(3,store.messages().size)
    }
    @Test fun historicOverlapAndSlidingWindow(){
        val a=message("a");val b=message("b","2026-10-09T10:01:00Z");val c=message("c","2026-10-09T10:02:00Z")
        append(snapshot(a,b));append(snapshot(b,c).put("historicMessages",JSONArray().put(a).put(b)))
        assertEquals(3,store.messages().size)
        assertEquals(3,store.sources(store.messages().first{it.text=="b"}.id).size)
    }
    @Test fun searchAppPersonAndDateFilters(){
        append(snapshot(message("one")));append(snapshot(message("two"),app="app.b"))
        val row=store.messages().first{it.text=="one"};store.map("personal","p","identity",row.identity,true)
        assertEquals(1,store.messages(person="p",profile="personal",search="one",app="app.a",from="2026-10-09T00:00:00Z",until="2026-10-10T00:00:00Z").size)
        assertEquals(0,store.messages(person="p",profile="personal",app="app.b").size)
        assertEquals(0,store.messages(until="2026-10-09T00:00:00Z").size)
    }
    @Test fun fallbackOnlyForBoundedMessageNotificationsWithThread(){
        val s=snapshot(thread=null).put("messaging",false).put("category","msg").put("text","fallback")
        append(s);assertEquals(0,store.messages().size)
        append(s.put("shortcut","known"));assertEquals("LOW",store.messages().single().confidence)
        assertEquals("",store.messages().single().sender)
    }
    @Test fun paginatedMessagesAndThreadsKeepEveryRowInTimeOrder(){
        repeat(105){i->append(snapshot(message("item $i", "2026-10-09T10:00:00Z"),thread="thread-$i",key="key-$i"))}
        val first=store.messages();val second=store.messages(before=first.last())
        assertEquals(100,first.size);assertEquals(5,second.size);assertEquals(105,(first+second).map{it.id}.distinct().size)
        val threads=store.conversations();val next=store.conversations(after=threads.last())
        assertEquals(100,threads.size);assertEquals(5,next.size)
        assertEquals(threads.first(),store.conversation(threads.first().id))
    }
    @Test fun migrationFromPhaseOnePreservesRawAndSearch(){
        archive.close();val file=context.noBackupFilesDir.resolve("notifications.sqlite");file.delete()
        // Frozen v1 schema fixture: migration must add only the normalized projection.
        val old=object:SQLiteOpenHelper(context,file.path,null,1){
            override fun onCreate(db:SQLiteDatabase){
                db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,package TEXT NOT NULL,nkey TEXT NOT NULL,captured TEXT NOT NULL,payload TEXT NOT NULL,reason INTEGER)")
                db.execSQL("CREATE TABLE active(nkey TEXT PRIMARY KEY)")
                db.execSQL("CREATE VIRTUAL TABLE event_search USING fts4(content)")
                db.execSQL("CREATE TRIGGER immutable_update BEFORE UPDATE ON events BEGIN SELECT RAISE(ABORT,'immutable event'); END")
                db.execSQL("CREATE TRIGGER immutable_delete BEFORE DELETE ON events BEGIN SELECT RAISE(ABORT,'immutable event'); END")
            }
            override fun onUpgrade(db:SQLiteDatabase,o:Int,n:Int){error("not expected")}
        }
        val raw=snapshot(message()).toString()
        old.writableDatabase.execSQL("INSERT INTO events VALUES(1,'POSTED','app.a','key','2026-10-09T11:00:00Z',?,NULL)",arrayOf(raw))
        old.writableDatabase.execSQL("INSERT INTO event_search(docid,content) VALUES(1,'hello')");old.close()
        archive=ArchiveStore(context);store=ConversationsStore(archive)
        assertEquals(1,archive.normalizePending());assertEquals(raw,archive.event(1)!!.snapshot.toString())
        assertEquals(1,archive.query("hello").size);assertEquals(1,store.messages().size)
        assertEquals(2,archive.readableDatabase.version)
    }
}
