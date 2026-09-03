package com.gernalix.personalhub.core.database

import android.content.Context
import com.gernalix.personalhub.core.database.capsules.sync.*
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [
    com.supercontacts.app.data.local.BackupMetadataEntity::class,
    com.supercontacts.app.data.local.ContactEntity::class,
    com.supercontacts.app.data.local.ContactFieldEntity::class,
    com.supercontacts.app.data.local.ContactEventEntity::class,
    com.supercontacts.app.data.local.ContactInitiativeEntity::class,
    com.supercontacts.app.data.local.ContactMessagingLinkEntity::class,
    com.supercontacts.app.data.local.SavedSearchEntity::class,
    com.supercontacts.app.data.local.SavedSearchTagCrossRef::class,
    com.supercontacts.app.data.local.TagEntity::class,
    com.supercontacts.app.data.local.ContactTagCrossRef::class,
    com.gernalix.luoghi.data.PlaceEntity::class,
    com.gernalix.luoghi.data.PlaceAliasEntity::class,
    com.gernalix.luoghi.data.PlaceLinkEntity::class,
    com.gernalix.luoghi.data.PlaceEventEntity::class,
    com.gernalix.luoghi.data.GlobalStatsStateEntity::class,
    com.gernalix.luoghi.data.RouteDistanceCacheEntity::class,
    com.gernalix.luoghi.data.HistoryAuditLogEntity::class,
    com.gernalix.luoghi.data.HistoryActionEntity::class,
    com.gernalix.sostanze.data.SubstanceEntity::class,
    com.gernalix.sostanze.data.IntakeEventEntity::class,
    com.gernalix.sostanze.data.StockAdjustmentEntity::class,
    com.gernalix.sostanze.data.PrescriptionEntity::class,
    com.gernalix.sostanze.data.InteractionRuleEntity::class,
    com.gernalix.sostanze.data.InteractionTargetEntity::class,
    com.gernalix.sostanze.data.NotificationStateEntity::class,
    com.gernalix.sostanze.data.SettingEntity::class,
    com.gernalix.sostanze.data.MacroEntity::class,
    com.gernalix.sostanze.data.MacroItemEntity::class,
    com.wordpulse.app.data.AppStateEntity::class,
    com.wordpulse.app.data.CorrectionEvent::class,
    com.wordpulse.app.data.WordEntry::class,
    com.wordpulse.app.data.WordSession::class,
    com.gernalix.personalhub.core.database.TimerAuditEvents::class,
    com.gernalix.personalhub.core.database.TimerIntegrityStats::class,
    com.gernalix.personalhub.core.database.TimerQuickEventEntries::class,
    com.gernalix.personalhub.core.database.TimerQuickEventEntryFieldValues::class,
    com.gernalix.personalhub.core.database.TimerQuickEventEntryTags::class,
    com.gernalix.personalhub.core.database.TimerQuickEventMacroActions::class,
    com.gernalix.personalhub.core.database.TimerQuickEventMacroTags::class,
    com.gernalix.personalhub.core.database.TimerQuickEventMacros::class,
    com.gernalix.personalhub.core.database.TimerQuickEventTemplateFields::class,
    com.gernalix.personalhub.core.database.TimerQuickEventTemplateTags::class,
    com.gernalix.personalhub.core.database.TimerQuickEventTemplates::class,
    com.gernalix.personalhub.core.database.TimerSessionTags::class,
    com.gernalix.personalhub.core.database.TimerSessions::class,
    com.gernalix.personalhub.core.database.TimerSnapshot::class,
    com.gernalix.personalhub.core.database.TimerSnapshotHistory::class,
    com.gernalix.personalhub.core.database.TimerSnapshotPayloads::class,
    com.gernalix.personalhub.core.database.TimerUiPrefsMirror::class,
    com.gernalix.personalhub.core.database.TimerSyncMeta::class,
    com.gernalix.personalhub.core.database.TimerSyncQueue::class,
    com.gernalix.personalhub.core.database.TimerSyncShadow::class,
    PeoplePhoto::class, HubGeneration::class, HubPreferences::class, HubSyncPending::class, HubSyncKnown::class,
], version = 3, exportSchema = true)
abstract class PersonalHubDatabase : RoomDatabase() {
    abstract fun contactsDao(): com.supercontacts.app.data.local.ContactsDao
    abstract fun placeDao(): com.gernalix.luoghi.data.PlaceDao
    abstract fun dao(): com.gernalix.sostanze.data.SostanzeDao
    abstract fun wordPulseDao(): com.wordpulse.app.data.WordPulseDao
    abstract fun photoDao(): PeoplePhotoDao

    companion object {
        const val DATABASE_NAME = "personalhub.db"
        const val DB_NAME = DATABASE_NAME
        const val SCHEMA_VERSION = 3
        const val APP_ID = "com.gernalix.personalhub"
        const val BACKUP_FORMAT_VERSION = 1
        @Volatile private var instance: PersonalHubDatabase? = null
        fun get(context: Context): PersonalHubDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext, DATABASE_NAME).also { instance = it }
        }
        fun getInstance(context: Context) = get(context)
        fun create(context: Context) = get(context)
        fun openTemporary(context: Context, name: String) = build(context, name)
        fun openStaging(context: Context, name: String) = build(context, name)
        fun canMigrateFrom(version: Int) = version == SCHEMA_VERSION
        fun closeInstance() = synchronized(this) { instance?.close(); instance = null }
        fun resetForTests() = closeInstance()
        private fun build(context: Context, name: String): PersonalHubDatabase {
            DatabaseGate.configureAutoExport(context)
            return Room.databaseBuilder(context, PersonalHubDatabase::class.java, name)
                .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `hub_preferences` (`namespace` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`namespace`))")
                        db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
                    }
                })
                .addMigrations(object : androidx.room.migration.Migration(2, 3) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        SyncJournal.create(db)
                        // Timer compatibility indices were previously created lazily outside Room.
                        // Declare and create the same indices before Room validates the upgrade.
                        val schema = org.json.JSONObject(context.assets.open("com.gernalix.personalhub.core.database.PersonalHubDatabase/3.json").bufferedReader().use { it.readText() }).getJSONObject("database").getJSONArray("entities")
                        for (i in 0 until schema.length()) {
                            val entity = schema.getJSONObject(i)
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                        }
                    }
                })
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .openHelperFactory(GatedOpenHelperFactory())
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("INSERT OR IGNORE INTO hub_generation(id, generation) VALUES (1, 0)")
                        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata','hub_generation','hub_sync_pending','hub_sync_known')").use { c ->
                            buildList { while (c.moveToNext()) add(c.getString(0)) }
                        }
                        SyncJournal.install(db)
                        tables.forEach { table ->
                            listOf("INSERT", "UPDATE", "DELETE").forEach { op ->
                                db.execSQL("CREATE TRIGGER IF NOT EXISTS `hub_dirty_${table}_$op` AFTER $op ON `$table` BEGIN UPDATE hub_generation SET generation=generation+1 WHERE id=1; END")
                            }
                        }
                    }
                }).build()
        }
    }
}
