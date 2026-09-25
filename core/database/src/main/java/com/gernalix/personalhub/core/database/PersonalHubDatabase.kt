package com.gernalix.personalhub.core.database

import android.content.Context
import com.gernalix.personalhub.contracts.database.PlaceReferenceReader
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.capsules.sync.*
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSettings
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataTracking
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryStore
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest

@Database(entities = [
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceProduct::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceTitle::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceChain::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceStore::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceTransaction::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceTag::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceTransactionTag::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceTransfer::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceMacro::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceRecurrence::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceRecurrenceTag::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceRecurrenceOverride::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceAttachment::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinancePhotoIndex::class,
    com.gernalix.personalhub.core.database.capsules.soldi.FinanceOwnedItem::class,
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
    com.gernalix.luoghi.data.CheckInAttemptEntity::class,
    com.gernalix.luoghi.data.CheckInAttemptCandidateEntity::class,
    com.gernalix.luoghi.data.GlobalStatsStateEntity::class,
    com.gernalix.luoghi.data.RouteDistanceCacheEntity::class,
    com.gernalix.luoghi.data.HistoryAuditLogEntity::class,
    com.gernalix.luoghi.data.HistoryActionEntity::class,
    com.gernalix.luoghi.data.PlaceGeofenceConfigEntity::class,
    com.gernalix.luoghi.data.PlaceGeofenceTransitionLogEntity::class,
    com.gernalix.luoghi.data.PlaceTagEntity::class,
    com.gernalix.luoghi.data.PlaceTagCrossRef::class,
    com.gernalix.personalhub.alerts.AlertRuleEntity::class,
    com.gernalix.personalhub.alerts.AlertPlaceTagTargetEntity::class,
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
    com.wordpulse.app.data.PvtResultEntity::class,
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
    HubEntityBinding::class, HubContextType::class, HubContextTypeField::class, HubContext::class, HubContextMember::class,
    HubTagEntity::class, HubTagAlias::class, HubTagAssignment::class, HubTagParent::class, HubSavedTagFilter::class,
    HubResource::class, HubActivityEntity::class,
    SinceWhenCounterEntity::class,
    SinceWhenMigrationState::class,

], version = 23, exportSchema = true)
abstract class PersonalHubDatabase : RoomDatabase(), PlaceReferenceReader {
    abstract fun contactsDao(): com.supercontacts.app.data.local.ContactsDao
    abstract fun placeDao(): com.gernalix.luoghi.data.PlaceDao
    abstract fun alertDao(): com.gernalix.personalhub.alerts.AlertDao
    abstract fun dao(): com.gernalix.sostanze.data.SostanzeDao
    abstract fun wordPulseDao(): com.wordpulse.app.data.WordPulseDao
    abstract fun financeDao(): com.gernalix.personalhub.core.database.capsules.soldi.FinanceDao
    abstract fun photoDao(): PeoplePhotoDao
    abstract fun hubContextDao(): HubContextDao
    abstract fun hubTagDao(): HubTagDao
    abstract fun hubResourceDao(): HubResourceDao
    abstract fun activityDao(): HubActivityDao
    abstract fun sinceWhenCounterDao(): SinceWhenCounterDao

    final override suspend fun referenceCount(placeId: String): Int =
        financeDao().transactionCountForPlace(placeId) + financeDao().storeCountForPlace(placeId)

    companion object {
        const val DATABASE_NAME = "personalhub.db"
        const val DB_NAME = DATABASE_NAME
        const val SCHEMA_VERSION = 23
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
        fun closeInstance() = synchronized(this) { instance?.close(); instance = null }
        fun resetForTests() = closeInstance()


        private fun schemaFingerprint(db: SupportSQLiteDatabase): String {
            val digest = MessageDigest.getInstance("SHA-256")
            db.query("SELECT type, name, sql FROM sqlite_master WHERE type IN ('table','index','trigger') ORDER BY type, name").use { cursor ->
                while (cursor.moveToNext()) {
                    for (index in 0..2) {
                        digest.update((cursor.getString(index) ?: "").toByteArray(Charsets.UTF_8))
                        digest.update(0)
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun build(context: Context, name: String): PersonalHubDatabase {
            DatabaseGate.configureAutoExport(context)
            return Room.databaseBuilder(context, PersonalHubDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .openHelperFactory(GatedOpenHelperFactory())
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        val appVersion = runCatching {
                            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                                context.packageManager.getPackageInfo(context.packageName, 0),
                            )
                        }.getOrDefault(0L)
                        val gitHistoryEnabled = runCatching {
                            GitDataSettings.configuration(context).enabled
                        }.getOrDefault(false)
                        val manifestPrefs = context.getSharedPreferences("hub_open_manifest", Context.MODE_PRIVATE)
                        fun manifest() = "v2:$appVersion:$gitHistoryEnabled:${schemaFingerprint(db)}"
                        if (manifestPrefs.getString(name, null) == manifest()) {
                            if (gitHistoryEnabled) GitDataTracking.resumeInstalled(db)
                            return
                        }
                        db.execSQL("INSERT OR IGNORE INTO hub_generation(id, generation) VALUES (1, 0)")
                        LEGACY_TIMER_SYNC_TABLES.forEach { table ->
                            listOf("INSERT", "UPDATE", "DELETE").forEach { op ->
                                db.execSQL("DROP TRIGGER IF EXISTS `hub_dirty_${table}_$op`")
                            }
                        }
                        val tables = SyncJournal.tables(db)
                        SyncJournal.install(db)
                        if (gitHistoryEnabled) {
                            HubActivityCapture.uninstall(db)
                            GitHistoryStore.install(db)
                            // Install history triggers before feature code can perform the first
                            // post-open write. GitDataSync.start() later handles scheduling and a
                            // full reconciliation when the cached manifest requires it.
                            GitDataTracking.install(db, enqueueAll = false)
                        } else {
                            GitDataTracking.uninstall(db)
                            HubActivityCapture.install(db, appVersion)
                        }
                        val installedDirty = db.query("SELECT name, sql FROM sqlite_master WHERE type='trigger' AND name LIKE 'hub_dirty_%'").use { cursor ->
                            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
                        }
                        fun normalized(sql: String) = sql.replace("IF NOT EXISTS ", "").replace(Regex("\\s+"), " ").trim()
                        tables.forEach { table ->
                            listOf("INSERT", "UPDATE", "DELETE").forEach { op ->
                                val name = "hub_dirty_${table}_$op"
                                val expected = "CREATE TRIGGER `$name` AFTER $op ON `$table` BEGIN UPDATE hub_generation SET generation=generation+1 WHERE id=1; END"
                                if (installedDirty[name]?.let(::normalized) != expected) {
                                    db.execSQL("DROP TRIGGER IF EXISTS `$name`")
                                    db.execSQL(expected)
                                }
                            }
                        }
                        check(manifestPrefs.edit().putString(name, manifest()).commit()) {
                            "Could not persist database open manifest"
                        }
                    }
                }).build()
        }

    }
}
