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
    HubResource::class, HubActivityEntity::class,
], version = 15, exportSchema = true)
abstract class PersonalHubDatabase : RoomDatabase(), PlaceReferenceReader {
    abstract fun contactsDao(): com.supercontacts.app.data.local.ContactsDao
    abstract fun placeDao(): com.gernalix.luoghi.data.PlaceDao
    abstract fun dao(): com.gernalix.sostanze.data.SostanzeDao
    abstract fun wordPulseDao(): com.wordpulse.app.data.WordPulseDao
    abstract fun financeDao(): com.gernalix.personalhub.core.database.capsules.soldi.FinanceDao
    abstract fun photoDao(): PeoplePhotoDao
    abstract fun hubContextDao(): HubContextDao
    abstract fun hubResourceDao(): HubResourceDao
    abstract fun activityDao(): HubActivityDao

    final override suspend fun referenceCount(placeId: String): Int =
        financeDao().transactionCountForPlace(placeId) + financeDao().storeCountForPlace(placeId)

    companion object {
        const val DATABASE_NAME = "personalhub.db"
        const val DB_NAME = DATABASE_NAME
        const val SCHEMA_VERSION = 15
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
        fun canMigrateFrom(version: Int): Boolean =
            version in 1..SCHEMA_VERSION

        internal fun productionMigrationEdges(context: Context) =
            productionMigrations(context).map { it.startVersion to it.endVersion }.toSet()
        internal fun hasProductionMigrationPath(context: Context, from: Int): Boolean {
            if (from == SCHEMA_VERSION) return true
            if (from !in 1 until SCHEMA_VERSION) return false
            val edges = productionMigrationEdges(context)
            val reachable = mutableSetOf(from)
            while (true) {
                val next = edges
                    .filter { it.first in reachable }
                    .map { it.second }
                    .filter { it <= SCHEMA_VERSION }
                    .toSet() - reachable
                if (next.isEmpty()) return false
                if (SCHEMA_VERSION in next) return true
                reachable += next
            }
        }
        fun closeInstance() = synchronized(this) { instance?.close(); instance = null }
        fun resetForTests() = closeInstance()
        private fun productionMigrations(context: Context): Array<androidx.room.migration.Migration> = arrayOf(
            object : androidx.room.migration.Migration(1, 2) {
                                override fun migrate(db: SupportSQLiteDatabase) {
                                    db.execSQL("CREATE TABLE IF NOT EXISTS `hub_preferences` (`namespace` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`namespace`))")
                                    db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
                                }
                            },
            object : androidx.room.migration.Migration(2, 3) {
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
                            },
            com.gernalix.personalhub.core.database.capsules.soldi.FinanceMigration(context), com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccountsMigration(context),
            object : androidx.room.migration.Migration(5, 6) {
                                override fun migrate(db: SupportSQLiteDatabase) {
                                    db.execSQL("ALTER TABLE substances ADD COLUMN canonical_name TEXT NOT NULL DEFAULT ''")
                                    db.execSQL("ALTER TABLE substances ADD COLUMN dose_times_csv TEXT NOT NULL DEFAULT ''")
                                    db.execSQL("ALTER TABLE substances ADD COLUMN days_mask INTEGER NOT NULL DEFAULT 127")
                                    db.execSQL("UPDATE substances SET canonical_name=lower(trim(name))")
                                    db.execSQL("UPDATE substances SET canonical_name=canonical_name || '#legacy:' || id WHERE id NOT IN (SELECT min(id) FROM substances GROUP BY lower(trim(name)))")
                                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_substances_canonical_name ON substances(canonical_name)")
                                    db.execSQL("ALTER TABLE intake_events ADD COLUMN quantity REAL NOT NULL DEFAULT 1.0")
                                    db.execSQL("ALTER TABLE intake_events ADD COLUMN applied_stock_delta REAL NOT NULL DEFAULT 0.0")
                                    db.execSQL("ALTER TABLE intake_events ADD COLUMN prescription_id INTEGER")
                                    db.execSQL("ALTER TABLE prescriptions ADD COLUMN order_epoch_day INTEGER NOT NULL DEFAULT 0")
                                    db.execSQL("ALTER TABLE prescriptions ADD COLUMN package_dose_count INTEGER NOT NULL DEFAULT 0")
                                    db.execSQL("ALTER TABLE prescriptions ADD COLUMN remaining_doses INTEGER NOT NULL DEFAULT 0")
                                    db.execSQL("ALTER TABLE prescriptions ADD COLUMN dose_mg REAL NOT NULL DEFAULT 0.0")
                                    db.execSQL("ALTER TABLE prescriptions ADD COLUMN frequency_period TEXT NOT NULL DEFAULT 'DAY'")
                                    db.execSQL("ALTER TABLE prescriptions ADD COLUMN frequency_count INTEGER NOT NULL DEFAULT 1")
                                    db.execSQL("ALTER TABLE prescriptions ADD COLUMN doctor_contact_id INTEGER")
                                    db.execSQL("ALTER TABLE prescriptions ADD COLUMN finance_transaction_id INTEGER")
                                    db.execSQL("UPDATE prescriptions SET order_epoch_day=prescription_epoch_day, package_dose_count=max(1, CAST(quantity_prescribed AS INTEGER)), remaining_doses=max(1, CAST(quantity_prescribed AS INTEGER)), dose_mg=quantity_prescribed")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS index_intake_events_prescription_id ON intake_events(prescription_id)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS index_prescriptions_order_epoch_day ON prescriptions(order_epoch_day)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS index_prescriptions_doctor_contact_id ON prescriptions(doctor_contact_id)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS index_prescriptions_finance_transaction_id ON prescriptions(finance_transaction_id)")
                                }
                            },
            object : androidx.room.migration.Migration(6, 7) {
                                override fun migrate(db: SupportSQLiteDatabase) {
                                    db.execSQL("CREATE TABLE IF NOT EXISTS `hub_entity_bindings` (`id` TEXT NOT NULL, `module_id` TEXT NOT NULL, `entity_kind` TEXT NOT NULL, `canonical_id` TEXT NOT NULL, `lifecycle` TEXT NOT NULL, `updated_at` TEXT NOT NULL, PRIMARY KEY(`id`))")
                                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_hub_entity_bindings_module_id_entity_kind_canonical_id` ON `hub_entity_bindings` (`module_id`, `entity_kind`, `canonical_id`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_entity_bindings_lifecycle` ON `hub_entity_bindings` (`lifecycle`)")
                                    db.execSQL("CREATE TABLE IF NOT EXISTS `hub_context_types` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `created_at` TEXT NOT NULL, `updated_at` TEXT NOT NULL, PRIMARY KEY(`id`))")
                                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_hub_context_types_name` ON `hub_context_types` (`name`)")
                                    db.execSQL("CREATE TABLE IF NOT EXISTS `hub_context_type_fields` (`context_type_id` TEXT NOT NULL, `field_id` TEXT NOT NULL, `position` INTEGER NOT NULL, `label` TEXT NOT NULL, `role` TEXT NOT NULL, `accepted_module_id` TEXT, `accepted_entity_kind` TEXT, `accepted_capability` TEXT, `min_cardinality` INTEGER NOT NULL, `max_cardinality` INTEGER, PRIMARY KEY(`context_type_id`, `field_id`), FOREIGN KEY(`context_type_id`) REFERENCES `hub_context_types`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_hub_context_type_fields_context_type_id_position` ON `hub_context_type_fields` (`context_type_id`, `position`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_context_type_fields_accepted_module_id_accepted_entity_kind` ON `hub_context_type_fields` (`accepted_module_id`, `accepted_entity_kind`)")
                                    db.execSQL("CREATE TABLE IF NOT EXISTS `hub_contexts` (`id` TEXT NOT NULL, `context_type_id` TEXT, `title` TEXT, `created_at` TEXT NOT NULL, `updated_at` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`context_type_id`) REFERENCES `hub_context_types`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_contexts_context_type_id` ON `hub_contexts` (`context_type_id`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_contexts_updated_at` ON `hub_contexts` (`updated_at`)")
                                    db.execSQL("CREATE TABLE IF NOT EXISTS `hub_context_members` (`context_id` TEXT NOT NULL, `entity_id` TEXT NOT NULL, `role` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`context_id`, `entity_id`, `role`), FOREIGN KEY(`context_id`) REFERENCES `hub_contexts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`entity_id`) REFERENCES `hub_entity_bindings`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_context_members_entity_id` ON `hub_context_members` (`entity_id`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_context_members_context_id_role` ON `hub_context_members` (`context_id`, `role`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_context_members_entity_id_context_id` ON `hub_context_members` (`entity_id`, `context_id`)")
                                    db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
                                }
                            },
            object : androidx.room.migration.Migration(7, 8) {
                                override fun migrate(db: SupportSQLiteDatabase) {
                                    db.execSQL("CREATE TABLE IF NOT EXISTS `hub_resources` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT, `value` TEXT NOT NULL, `persistedPermission` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))")
                                    db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
                                }
                            },
            object : androidx.room.migration.Migration(8, 9) {
                                override fun migrate(db: SupportSQLiteDatabase) {
                                    db.execSQL("ALTER TABLE hub_context_types ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
                                    db.execSQL("UPDATE hub_context_types SET locked=1 WHERE id='timer_activity'")
                                    db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
                                }
                            },
            object : androidx.room.migration.Migration(9, 10) {
                                override fun migrate(db: SupportSQLiteDatabase) {
                                    db.execSQL("CREATE TABLE IF NOT EXISTS `place_geofence_configs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `place_uuid` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `enter_enabled` INTEGER NOT NULL, `exit_enabled` INTEGER NOT NULL, `enter_action` TEXT NOT NULL, `exit_action` TEXT NOT NULL, `last_enter_at` INTEGER, `last_exit_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`place_uuid`) REFERENCES `places`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_place_geofence_configs_place_uuid` ON `place_geofence_configs` (`place_uuid`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_geofence_configs_enabled` ON `place_geofence_configs` (`enabled`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_geofence_configs_updated_at` ON `place_geofence_configs` (`updated_at`)")
                                    db.execSQL("CREATE TABLE IF NOT EXISTS `place_geofence_transition_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `place_uuid` TEXT NOT NULL, `transition` TEXT NOT NULL, `bucket` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)")
                                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_place_geofence_transition_log_place_uuid_transition_bucket` ON `place_geofence_transition_log` (`place_uuid`, `transition`, `bucket`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_geofence_transition_log_created_at` ON `place_geofence_transition_log` (`created_at`)")
                                    db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
                                }
                            },
            object : androidx.room.migration.Migration(10, 11) {
                                override fun migrate(db: SupportSQLiteDatabase) {
                                    db.execSQL("CREATE TABLE IF NOT EXISTS `hub_activity_log` (`id` TEXT NOT NULL, `occurred_at` INTEGER NOT NULL, `module_id` TEXT NOT NULL, `action` TEXT NOT NULL, `entity_kind` TEXT, `entity_id` TEXT, `entity_label` TEXT, `detail_key` TEXT, `detail_value` TEXT, `origin` TEXT NOT NULL, `is_system` INTEGER NOT NULL, `source_table` TEXT NOT NULL, `source_row_key` TEXT, `payload_kind` TEXT, `payload_columns` TEXT, `before_payload` TEXT, `after_payload` TEXT, `payload_version` INTEGER NOT NULL, `app_version` INTEGER NOT NULL, `group_id` TEXT, `reversible` INTEGER NOT NULL, `status` TEXT NOT NULL, `reverted_at` INTEGER, `reverts_activity_id` TEXT, PRIMARY KEY(`id`))")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_activity_log_occurred_at_id` ON `hub_activity_log` (`occurred_at`, `id`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_activity_log_module_id_occurred_at` ON `hub_activity_log` (`module_id`, `occurred_at`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_activity_log_module_id_entity_kind_entity_id` ON `hub_activity_log` (`module_id`, `entity_kind`, `entity_id`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_activity_log_status_occurred_at` ON `hub_activity_log` (`status`, `occurred_at`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_activity_log_group_id_occurred_at` ON `hub_activity_log` (`group_id`, `occurred_at`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_activity_log_reverts_activity_id` ON `hub_activity_log` (`reverts_activity_id`)")
                                    HubActivityCapture.createInternalTables(db)
                                    db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
                                }
                            },
            com.gernalix.personalhub.core.database.capsules.soldi.FinanceAdvancedMigration(),
            object : androidx.room.migration.Migration(12, 13) {
                                override fun migrate(db: SupportSQLiteDatabase) {
                                    db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `median_inter_key_interval_ms` REAL")
                                    db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `p95_inter_key_interval_ms` REAL")
                                    db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `inter_key_interval_cv` REAL")
                                    db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `micro_pause_count` INTEGER")
                                    db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `last_edit_to_submit_ms` INTEGER")
                                    db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `fatigue_score` INTEGER")
                                    db.execSQL("""
                                        CREATE TABLE IF NOT EXISTS `pvt_results` (
                                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                            `started_at_utc_ms` INTEGER NOT NULL,
                                            `completed_at_utc_ms` INTEGER NOT NULL,
                                            `duration_ms` INTEGER NOT NULL,
                                            `trial_count` INTEGER NOT NULL,
                                            `median_reaction_time_ms` REAL,
                                            `p90_reaction_time_ms` REAL,
                                            `lapse_count` INTEGER NOT NULL,
                                            `false_start_count` INTEGER NOT NULL,
                                            `paired_fatigue_score` INTEGER,
                                            `speed_domain_score` INTEGER,
                                            `rhythm_domain_score` INTEGER,
                                            `control_domain_score` INTEGER,
                                            `session_drift_domain_score` INTEGER,
                                            `sleep_context_domain_score` INTEGER,
                                            `hours_awake` REAL
                                        )
                                    """.trimIndent())
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_pvt_results_completed_at_utc_ms` ON `pvt_results` (`completed_at_utc_ms`)")
                                    db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
                                }
                            },
            object : androidx.room.migration.Migration(13, 14) {
                                override fun migrate(db: SupportSQLiteDatabase) {
                                    db.execSQL("""
                                        CREATE TABLE IF NOT EXISTS `check_in_attempts` (
                                            `id` TEXT NOT NULL,
                                            `started_at` INTEGER NOT NULL,
                                            `finished_at` INTEGER,
                                            `source` TEXT NOT NULL,
                                            `stage` TEXT NOT NULL,
                                            `outcome` TEXT NOT NULL,
                                            `lat` REAL,
                                            `lon` REAL,
                                            `accuracy_m` REAL,
                                            `selected_place_id` TEXT,
                                            `matched_place_id` TEXT,
                                            `error_code` TEXT,
                                            `error_message` TEXT,
                                            PRIMARY KEY(`id`)
                                        )
                                    """.trimIndent())
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_check_in_attempts_started_at` ON `check_in_attempts` (`started_at`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_check_in_attempts_finished_at` ON `check_in_attempts` (`finished_at`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_check_in_attempts_outcome` ON `check_in_attempts` (`outcome`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_check_in_attempts_matched_place_id` ON `check_in_attempts` (`matched_place_id`)")
                                    db.execSQL("""
                                        CREATE TABLE IF NOT EXISTS `check_in_attempt_candidates` (
                                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                            `attempt_id` TEXT NOT NULL,
                                            `place_id` TEXT NOT NULL,
                                            `distance_m` REAL NOT NULL,
                                            `threshold_m` REAL NOT NULL,
                                            `rank` INTEGER NOT NULL,
                                            `result` TEXT NOT NULL,
                                            FOREIGN KEY(`attempt_id`) REFERENCES `check_in_attempts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                                            FOREIGN KEY(`place_id`) REFERENCES `places`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE
                                        )
                                    """.trimIndent())
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_check_in_attempt_candidates_attempt_id` ON `check_in_attempt_candidates` (`attempt_id`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_check_in_attempt_candidates_place_id` ON `check_in_attempt_candidates` (`place_id`)")
                                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_check_in_attempt_candidates_attempt_id_place_id` ON `check_in_attempt_candidates` (`attempt_id`, `place_id`)")
                                    db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
                                }
                            },
            object : androidx.room.migration.Migration(14, 15) {
                                override fun migrate(db: SupportSQLiteDatabase) {
                                    db.execSQL("""
                                        CREATE TABLE IF NOT EXISTS `check_in_attempt_candidates_new` (
                                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                            `attempt_id` TEXT NOT NULL,
                                            `place_id` TEXT NOT NULL,
                                            `place_name_snapshot` TEXT,
                                            `distance_m` REAL NOT NULL,
                                            `threshold_m` REAL NOT NULL,
                                            `rank` INTEGER NOT NULL,
                                            `result` TEXT NOT NULL,
                                            FOREIGN KEY(`attempt_id`) REFERENCES `check_in_attempts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                                        )
                                    """.trimIndent())
                                    db.execSQL("""
                                        INSERT INTO `check_in_attempt_candidates_new` (
                                            `id`, `attempt_id`, `place_id`, `place_name_snapshot`,
                                            `distance_m`, `threshold_m`, `rank`, `result`
                                        )
                                        SELECT c.`id`, c.`attempt_id`, c.`place_id`,
                                               COALESCE(NULLIF(p.`nickname`, ''), p.`address`),
                                               c.`distance_m`, c.`threshold_m`, c.`rank`, c.`result`
                                        FROM `check_in_attempt_candidates` c
                                        LEFT JOIN `places` p ON p.`uuid` = c.`place_id`
                                    """.trimIndent())
                                    db.execSQL("DROP TABLE `check_in_attempt_candidates`")
                                    db.execSQL("ALTER TABLE `check_in_attempt_candidates_new` RENAME TO `check_in_attempt_candidates`")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_check_in_attempt_candidates_attempt_id` ON `check_in_attempt_candidates` (`attempt_id`)")
                                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_check_in_attempt_candidates_place_id` ON `check_in_attempt_candidates` (`place_id`)")
                                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_check_in_attempt_candidates_attempt_id_place_id` ON `check_in_attempt_candidates` (`attempt_id`, `place_id`)")
                                    db.execSQL("UPDATE hub_generation SET generation=generation+1 WHERE id=1")
                                }
                            },
        ) + DeclarativeMigrations.load(context)

        private fun build(context: Context, name: String): PersonalHubDatabase {
            DatabaseGate.configureAutoExport(context)
            return Room.databaseBuilder(context, PersonalHubDatabase::class.java, name)
                .addMigrations(*productionMigrations(context))
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .openHelperFactory(GatedOpenHelperFactory())
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("INSERT OR IGNORE INTO hub_generation(id, generation) VALUES (1, 0)")
                        val tables = SyncJournal.tables(db)
                        SyncJournal.install(db)
                        val appVersion = runCatching {
                            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                                context.packageManager.getPackageInfo(context.packageName, 0),
                            )
                        }.getOrDefault(0L)
                        val gitHistoryEnabled = runCatching {
                            GitDataSettings.configuration(context).enabled
                        }.getOrDefault(false)
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
