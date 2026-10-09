package com.gernalix.personalhub.notifications.capsules.archive

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

internal fun stableKey(vararg values: String): String = MessageDigest.getInstance("SHA-256")
    .digest(JSONArray(values.toList()).toString().toByteArray()).joinToString("") { "%02x".format(it) }
internal fun JSONObject.value(key: String): String = if(isNull(key)) "" else optString(key)
internal fun utc(value: String): String = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.parse(value))

internal data class ConversationRow(val id: String, val app: String, val title: String, val uncertain: Boolean, val group: Boolean)
internal data class MessageRow(val id: Long, val conversation: String, val identity: String, val sender: String,
    val text: String, val time: String, val confidence: String, val partial: Boolean, val revisionOf: Long?, val app: String, val title: String)
internal data class IdentityRow(val id: String, val name: String, val reliable: Boolean)
internal data class SourceRow(val event: Long, val slot: Int, val historic: Boolean)

/** A derived, local-only projection. Missing raw events cannot be reconstructed. */
internal object NormalizedArchive {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY, app TEXT NOT NULL, title TEXT NOT NULL, uncertain INTEGER NOT NULL, is_group INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE notification_threads(nkey TEXT PRIMARY KEY, conversation TEXT NOT NULL REFERENCES conversations(id), removed INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE identities(id TEXT PRIMARY KEY, name TEXT NOT NULL, reliable INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, conversation TEXT NOT NULL REFERENCES conversations(id), identity TEXT NOT NULL REFERENCES identities(id), signature TEXT NOT NULL, occurrence INTEGER NOT NULL, text TEXT NOT NULL, time TEXT NOT NULL, confidence TEXT NOT NULL, partial INTEGER NOT NULL, revision_of INTEGER REFERENCES messages(id), UNIQUE(conversation,signature,occurrence))")
        db.execSQL("CREATE INDEX messages_timeline ON messages(conversation,time,id)")
        db.execSQL("CREATE INDEX messages_identity ON messages(identity,time)")
        db.execSQL("CREATE INDEX messages_revision ON messages(conversation,identity,time)")
        db.execSQL("CREATE INDEX messages_time ON messages(time DESC,id DESC)")
        db.execSQL("CREATE INDEX conversations_app ON conversations(app,title,id)")
        db.execSQL("CREATE TABLE message_sources(message INTEGER NOT NULL REFERENCES messages(id), event INTEGER NOT NULL REFERENCES events(id), slot INTEGER NOT NULL, historic INTEGER NOT NULL, PRIMARY KEY(event,slot,historic))")
        db.execSQL("CREATE INDEX message_sources_message ON message_sources(message,event)")
        db.execSQL("CREATE TABLE people_mappings(profile TEXT NOT NULL, person TEXT NOT NULL, target_kind TEXT NOT NULL CHECK(target_kind IN ('conversation','identity')), target TEXT NOT NULL, PRIMARY KEY(profile,person,target_kind,target))")
        db.execSQL("CREATE INDEX mappings_target ON people_mappings(profile,target_kind,target)")
        db.execSQL("CREATE TABLE normalization_state(id INTEGER PRIMARY KEY CHECK(id=1), last_event INTEGER NOT NULL)")
        db.execSQL("INSERT INTO normalization_state VALUES(1,0)")
        db.execSQL("CREATE VIRTUAL TABLE message_search USING fts4(content)")
    }
    fun process(db: SQLiteDatabase, limit: Int): Int {
        db.beginTransaction()
        try {
            val last = db.rawQuery("SELECT last_event FROM normalization_state WHERE id=1",null).use { it.moveToFirst();it.getLong(0) }
            val events = db.rawQuery("SELECT id,kind,payload FROM events WHERE id>? ORDER BY id LIMIT ?",arrayOf(last.toString(),limit.coerceIn(1,100).toString())).use { c ->
                buildList { while(c.moveToNext())add(Triple(c.getLong(0),c.getString(1),JSONObject(c.getString(2)))) }
            }
            for((id,kind,snapshot) in events) {
                normalize(db,id,kind,snapshot)
                db.execSQL("UPDATE normalization_state SET last_event=? WHERE id=1",arrayOf(id))
            }
            db.setTransactionSuccessful()
            return events.size
        } finally { db.endTransaction() }
    }
    private fun normalize(db: SQLiteDatabase, event: Long, kind: String, s: JSONObject) {
        val key = s.getString("key")
        if(kind=="REMOVED") { db.execSQL("UPDATE notification_threads SET removed=1 WHERE nkey=?",arrayOf(key));return }
        if(s.optBoolean("summary")) return // aggregates remain raw, never duplicate their children
        val structured = s.optBoolean("messaging")
        val items = s.optJSONArray("messages") ?: JSONArray()
        val history = s.optJSONArray("historicMessages") ?: JSONArray()
        val fallback = !structured && s.value("category")=="msg" && s.value("text").isNotBlank() &&
            s.value("title").isNotBlank() && (s.value("shortcut").isNotBlank() || s.value("conversation").isNotBlank()) &&
            !s.value("text").contains('\n') && (s.optJSONArray("lines")?.length() ?: 0)<=1
        if(items.length()==0 && history.length()==0 && !fallback) return
        val appScope = stableKey(s.getString("package"),s.value("user"))
        val strong = if(s.value("shortcut").isNotBlank()) "shortcut:"+s.value("shortcut") else if(s.value("conversation").isNotBlank()) "conversation:"+s.value("conversation") else ""
        val prior = db.rawQuery("SELECT conversation,removed FROM notification_threads WHERE nkey=?",arrayOf(key)).use {
            if(it.moveToFirst()) it.getString(0) to it.getInt(1) else null
        }
        val conversation = if(strong.isNotBlank()) stableKey(appScope,"thread",strong)
            else if(kind!="OBSERVED" && prior!=null && prior.second==0) prior.first else stableKey(appScope,"observed-key",key,event.toString())
        val title = s.value("conversationTitle").ifBlank { s.value("title").ifBlank { s.getString("package") } }
        db.execSQL("INSERT OR IGNORE INTO conversations VALUES(?,?,?,?,?)",arrayOf(conversation,s.getString("package"),title,if(strong.isBlank()) 1 else 0,if(s.optBoolean("isGroup")) 1 else 0))
        db.execSQL("INSERT OR REPLACE INTO notification_threads VALUES(?,?,0)",arrayOf(key,conversation))
        // A repeated tuple in one observed snapshot has multiplicity; identical text is not a global key.
        val counts = mutableMapOf<String,Int>()
        fun extract(m: JSONObject, slot: Int, historic: Boolean, fallbackText: Boolean=false) {
            val sender = m.optJSONObject("sender")
            val self = s.optJSONObject("self")
            if(!fallbackText && (sender==null || isSelf(sender,self))) return // null sender represents self in MessagingStyle
            val text = m.value("text")
            val stamp = m.value("timestamp")
            val mime = m.value("mime")
            if(text.isBlank() && mime.isBlank()) return
            val senderToken = sender?.value("key").orEmpty().ifBlank { sender?.value("uri").orEmpty() }
            val senderName = sender?.value("name").orEmpty()
            val reliableIdentity = senderToken.isNotBlank()
            val senderSignature = if(reliableIdentity) senderToken else senderName
            val signature = stableKey(senderSignature,stamp,text,mime,m.value("dataUri"),if(fallbackText) s.value("posted") else "structured")
            val occurrence = counts.getOrDefault(signature,0);counts[signature]=occurrence+1
            val existing = db.rawQuery("SELECT id FROM messages WHERE conversation=? AND signature=? AND occurrence=?",arrayOf(conversation,signature,occurrence.toString())).use { if(it.moveToFirst())it.getLong(0) else null }
            if(existing!=null) {
                db.execSQL("INSERT OR IGNORE INTO message_sources VALUES(?,?,?,?)",arrayOf(existing,event,slot,if(historic)1 else 0));return
            }
            // Name-only senders remain independent ambiguous identities; never merge homonyms by name.
            val identity = if(reliableIdentity) stableKey(appScope,"sender",senderToken) else stableKey(conversation,signature,occurrence.toString(),"uncertain-sender")
            db.execSQL("INSERT OR IGNORE INTO identities VALUES(?,?,?)",arrayOf(identity,senderName,if(reliableIdentity)1 else 0))
            val partial = text.endsWith("…") || text.endsWith("...") || text.isBlank() || mime.isNotBlank()
            val confidence = if(fallbackText || stamp.isBlank()) "LOW" else if(reliableIdentity) "HIGH" else "MEDIUM"
            val time = utc(stamp.ifBlank { s.getString("captured") })
            // No stable platform message ID: this is only a possible revision, never an asserted edit.
            val possible = if(reliableIdentity && stamp.isNotBlank()) db.rawQuery("SELECT id FROM messages WHERE conversation=? AND identity=? AND time=? AND text!=? LIMIT 2",arrayOf(conversation,identity,time,text)).use {
                if(!it.moveToFirst()) null else it.getLong(0).takeIf { _ -> !it.moveToNext() }
            } else null
            db.execSQL("INSERT INTO messages(conversation,identity,signature,occurrence,text,time,confidence,partial,revision_of) VALUES(?,?,?,?,?,?,?,?,?)",arrayOf(conversation,identity,signature,occurrence,text,time,confidence,if(partial)1 else 0,possible))
            val message = db.rawQuery("SELECT last_insert_rowid()",null).use { it.moveToFirst();it.getLong(0) }
            db.execSQL("INSERT INTO message_search(docid,content) VALUES(?,?)",arrayOf(message,"$text\n$senderName"))
            db.execSQL("INSERT INTO message_sources VALUES(?,?,?,?)",arrayOf(message,event,slot,if(historic)1 else 0))
        }
        for(i in 0 until history.length())extract(history.getJSONObject(i),i,true)
        // Historic/current overlap represents the same snapshot message, without double-counting.
        counts.clear()
        for(i in 0 until items.length())extract(items.getJSONObject(i),i,false)
        if(fallback) extract(JSONObject().put("text",s.value("text")).put("timestamp",s.value("when").ifBlank { s.value("posted") }),0,false,true)
    }
    private fun isSelf(sender: JSONObject, self: JSONObject?): Boolean {
        if(self==null)return false
        for(field in listOf("key","uri")) if(sender.value(field).isNotBlank() && sender.value(field)==self.value(field))return true
        return sender.value("key").isBlank() && sender.value("uri").isBlank() && sender.value("name").isNotBlank() && sender.value("name")==self.value("name")
    }
}
