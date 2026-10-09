package com.gernalix.personalhub.notifications.capsules.archive

/** All associations belong exclusively to the private archive, never to Git-versioned People rows. */
internal class ConversationsStore(private val archive: ArchiveStore) {
    @Synchronized fun conversations(limit: Int=100, after: ConversationRow?=null, app: String=""): List<ConversationRow> = archive.readableDatabase.rawQuery("SELECT id,app,title,uncertain,is_group FROM conversations WHERE (?='' OR app=?) AND (app>? OR (app=? AND (title>? OR (title=? AND id>?)))) ORDER BY app,title,id LIMIT ?",arrayOf(app,app,after?.app.orEmpty(),after?.app.orEmpty(),after?.title.orEmpty(),after?.title.orEmpty(),after?.id.orEmpty(),limit.coerceIn(1,100).toString())).use { c ->
        buildList { while(c.moveToNext())add(ConversationRow(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)!=0,c.getInt(4)!=0)) }
    }
    fun conversation(id: String): ConversationRow? = archive.readableDatabase.rawQuery("SELECT id,app,title,uncertain,is_group FROM conversations WHERE id=?",arrayOf(id)).use { c -> if(c.moveToFirst())ConversationRow(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)!=0,c.getInt(4)!=0)else null }
    fun apps(): List<String> = archive.readableDatabase.rawQuery("SELECT DISTINCT app FROM conversations ORDER BY app",null).use { c -> buildList { while(c.moveToNext())add(c.getString(0)) } }
    fun identities(conversation: String): List<IdentityRow> = archive.readableDatabase.rawQuery("SELECT DISTINCT i.id,i.name,i.reliable FROM identities i JOIN messages m ON m.identity=i.id WHERE m.conversation=?",arrayOf(conversation)).use { c ->
        buildList { while(c.moveToNext())add(IdentityRow(c.getString(0),c.getString(1),c.getInt(2)!=0)) }
    }
    fun sources(message: Long, limit: Int=100): List<SourceRow> = archive.readableDatabase.rawQuery("SELECT event,slot,historic FROM message_sources WHERE message=? ORDER BY event DESC,slot LIMIT ?",arrayOf(message.toString(),limit.coerceIn(1,100).toString())).use { c -> buildList { while(c.moveToNext())add(SourceRow(c.getLong(0),c.getInt(1),c.getInt(2)!=0)) } }
    fun map(profile: String, person: String, kind: String, target: String, enabled: Boolean) = synchronized(archive) {
        require(profile.isNotBlank() && person.isNotBlank() && kind in setOf("conversation","identity"))
        val table = if(kind=="conversation") "conversations" else "identities"
        check(archive.readableDatabase.rawQuery("SELECT 1 FROM $table WHERE id=?",arrayOf(target)).use { it.moveToFirst() })
        if(enabled) archive.writableDatabase.execSQL("INSERT OR IGNORE INTO people_mappings VALUES(?,?,?,?)",arrayOf(profile,person,kind,target))
        else archive.writableDatabase.delete("people_mappings","profile=? AND person=? AND target_kind=? AND target=?",arrayOf(profile,person,kind,target))
        ArchiveStore.changes.value += 1
    }
    fun linked(profile: String, kind: String, target: String): List<String> = archive.readableDatabase.rawQuery("SELECT person FROM people_mappings WHERE profile=? AND target_kind=? AND target=?",arrayOf(profile,kind,target)).use { c -> buildList { while(c.moveToNext())add(c.getString(0)) } }
    fun messages(conversation: String="", person: String="", profile: String="", app: String="", search: String="", from: String="", until: String="", limit: Int=100, before: MessageRow?=null): List<MessageRow> {
        val clauses=mutableListOf("1=1");val args=mutableListOf<String>()
        if(conversation.isNotBlank()){clauses+="m.conversation=?";args+=conversation}
        if(person.isNotBlank()) { clauses+="EXISTS(SELECT 1 FROM people_mappings p WHERE p.profile=? AND p.person=? AND ((p.target_kind='conversation' AND p.target=m.conversation) OR (p.target_kind='identity' AND p.target=m.identity)))";args+=profile;args+=person }
        if(app.isNotBlank()){clauses+="c.app=?";args+=app}
        if(from.isNotBlank()){clauses+="m.time>=?";args+=utc(from)}
        if(until.isNotBlank()){clauses+="m.time<?";args+=utc(until)}
        val terms=Regex("[\\p{L}\\p{N}_]+").findAll(search).map { "\"${it.value}\"*" }.toList()
        if(terms.isNotEmpty()){clauses+="m.id IN (SELECT docid FROM message_search WHERE content MATCH ?)";args+=terms.joinToString(" AND ")}
        if(before!=null) { clauses+="(m.time<? OR (m.time=? AND m.id<?))";args+=listOf(before.time,before.time,before.id.toString()) }
        args+=limit.coerceIn(1,100).toString()
        return archive.readableDatabase.rawQuery("SELECT m.id,m.conversation,m.identity,i.name,m.text,m.time,m.confidence,m.partial,m.revision_of,c.app,c.title FROM messages m JOIN conversations c ON c.id=m.conversation JOIN identities i ON i.id=m.identity WHERE ${clauses.joinToString(" AND ")} ORDER BY m.time DESC,m.id DESC LIMIT ?",args.toTypedArray()).use { c ->
            buildList { while(c.moveToNext())add(MessageRow(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getInt(7)!=0,if(c.isNull(8))null else c.getLong(8),c.getString(9),c.getString(10))) }
        }
    }
}
