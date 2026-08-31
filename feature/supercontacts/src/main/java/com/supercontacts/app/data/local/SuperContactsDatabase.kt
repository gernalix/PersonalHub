package com.supercontacts.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BackupMetadataEntity::class,
        ContactEntity::class,
        ContactFieldEntity::class,
        ContactEventEntity::class,
        ContactInitiativeEntity::class,
        ContactMessagingLinkEntity::class,
        SavedSearchEntity::class,
        SavedSearchTagCrossRef::class,
        TagEntity::class,
        ContactTagCrossRef::class,
    ],
    version = SuperContactsDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class SuperContactsDatabase : RoomDatabase() {
    abstract fun contactsDao(): ContactsDao

    companion object {
        const val APP_ID = "com.supercontacts.app"
        const val DATABASE_NAME = "super_contacts.db"
        const val SCHEMA_VERSION = 14
        const val BACKUP_FORMAT_VERSION = 1

        @Volatile
        private var instance: SuperContactsDatabase? = null

        fun getInstance(context: Context): SuperContactsDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(
                    context = context.applicationContext,
                    name = DATABASE_NAME,
                ).also { instance = it }
            }

        fun openTemporary(context: Context, name: String): SuperContactsDatabase =
            buildDatabase(context.applicationContext, name)

        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        fun canMigrateFrom(version: Int): Boolean {
            if (version == SCHEMA_VERSION) return true
            if (version <= 0) return false
            var cursor = version
            while (cursor < SCHEMA_VERSION) {
                val migration = ALL_MIGRATIONS.firstOrNull { it.startVersion == cursor } ?: return false
                cursor = migration.endVersion
            }
            return cursor == SCHEMA_VERSION
        }

        private fun buildDatabase(
            context: Context,
            name: String,
        ): SuperContactsDatabase =
            Room.databaseBuilder(
                context,
                SuperContactsDatabase::class.java,
                name,
            ).addMigrations(*ALL_MIGRATIONS).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tags` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `normalized_name` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_tags` (
                        `contact_id` INTEGER NOT NULL,
                        `tag_id` INTEGER NOT NULL,
                        `added_at` INTEGER NOT NULL,
                        PRIMARY KEY(`contact_id`, `tag_id`),
                        FOREIGN KEY(`contact_id`) REFERENCES `contacts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_normalized_name` ON `tags` (`normalized_name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_tags_tag_id` ON `contact_tags` (`tag_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_tags_contact_id` ON `contact_tags` (`contact_id`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `contact_id` INTEGER NOT NULL,
                        `entity_type` TEXT NOT NULL,
                        `action_type` TEXT NOT NULL,
                        `field_type` TEXT,
                        `old_value` TEXT,
                        `new_value` TEXT,
                        `occurred_at` INTEGER NOT NULL,
                        FOREIGN KEY(`contact_id`) REFERENCES `contacts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_events_contact_id` ON `contact_events` (`contact_id`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contact_fields` ADD COLUMN `latitude` REAL")
                db.execSQL("ALTER TABLE `contact_fields` ADD COLUMN `longitude` REAL")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_initiatives` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `contact_id` INTEGER NOT NULL,
                        `timestamp_utc` INTEGER NOT NULL,
                        `initiative_type` TEXT NOT NULL,
                        FOREIGN KEY(`contact_id`) REFERENCES `contacts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_initiatives_contact_id` ON `contact_initiatives` (`contact_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_initiatives_timestamp_utc` ON `contact_initiatives` (`timestamp_utc`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_initiatives_contact_id_timestamp_utc` ON `contact_initiatives` (`contact_id`, `timestamp_utc`)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `backup_metadata` (
                        `id` INTEGER NOT NULL,
                        `app_id` TEXT NOT NULL,
                        `schema_version` INTEGER NOT NULL,
                        `backup_format_version` INTEGER NOT NULL,
                        `exported_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contact_events` ADD COLUMN `event_type` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `contact_events` ADD COLUMN `metadata_json` TEXT")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `deleted_at` INTEGER")
                db.execSQL(
                    """
                    UPDATE `contact_events`
                    SET `event_type` = CASE
                        WHEN `entity_type` = 'contact' AND `action_type` = 'created' THEN 'CONTACT_ADD'
                        WHEN `entity_type` = 'contact' AND `action_type` = 'opened' THEN 'CONTACT_OPEN'
                        WHEN `entity_type` = 'contact' AND `action_type` = 'deleted' THEN 'CONTACT_DELETE'
                        WHEN `entity_type` = 'field' AND `action_type` = 'added' THEN 'FIELD_ADD'
                        WHEN `entity_type` = 'field' AND `action_type` = 'opened' THEN 'FIELD_OPEN'
                        WHEN `entity_type` = 'field' AND `action_type` = 'updated' THEN 'FIELD_EDIT'
                        WHEN `entity_type` = 'field' AND `action_type` = 'deleted' THEN 'FIELD_DELETE'
                        WHEN `entity_type` = 'tag' AND `action_type` = 'added' THEN 'FIELD_ADD'
                        WHEN `entity_type` = 'tag' AND `action_type` = 'deleted' THEN 'FIELD_DELETE'
                        WHEN `entity_type` = 'initiative' THEN 'INITIATIVE'
                        ELSE ''
                    END
                    WHERE `event_type` = ''
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_events_event_type` ON `contact_events` (`event_type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_events_occurred_at` ON `contact_events` (`occurred_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_events_event_type_occurred_at` ON `contact_events` (`event_type`, `occurred_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_events_contact_id_event_type_occurred_at` ON `contact_events` (`contact_id`, `event_type`, `occurred_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contacts_deleted_at` ON `contacts` (`deleted_at`)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `public_id` TEXT")
                db.execSQL(
                    """
                    UPDATE `contacts`
                    SET `public_id` = 'c-' || lower(hex(randomblob(16)))
                    WHERE `public_id` IS NULL OR `public_id` = ''
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_contacts_public_id` ON `contacts` (`public_id`)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contact_fields` ADD COLUMN `description` TEXT")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contact_fields` ADD COLUMN `country_code` TEXT")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_searches` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `public_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `query` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_searches_public_id` ON `saved_searches` (`public_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_searches_created_at` ON `saved_searches` (`created_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_searches_updated_at` ON `saved_searches` (`updated_at`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_search_tags` (
                        `saved_search_id` INTEGER NOT NULL,
                        `tag_id` INTEGER NOT NULL,
                        PRIMARY KEY(`saved_search_id`, `tag_id`),
                        FOREIGN KEY(`saved_search_id`) REFERENCES `saved_searches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_search_tags_saved_search_id` ON `saved_search_tags` (`saved_search_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_search_tags_tag_id` ON `saved_search_tags` (`tag_id`)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_messaging_links` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `contact_id` INTEGER NOT NULL,
                        `platform` TEXT NOT NULL,
                        `normalized_phone` TEXT NOT NULL,
                        `deep_link` TEXT NOT NULL,
                        `generation_status` TEXT NOT NULL,
                        `verification_status` TEXT NOT NULL,
                        `last_scan_at` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        FOREIGN KEY(`contact_id`) REFERENCES `contacts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_messaging_links_contact_id` ON `contact_messaging_links` (`contact_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_contact_messaging_links_contact_id_platform_normalized_phone` ON `contact_messaging_links` (`contact_id`, `platform`, `normalized_phone`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_messaging_links_platform` ON `contact_messaging_links` (`platform`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_messaging_links_normalized_phone` ON `contact_messaging_links` (`normalized_phone`)")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Address 2 is stored as a new contact_fields.field_type value.
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `archived_at` INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contacts_archived_at` ON `contacts` (`archived_at`)")
            }
        }

        private val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
        )
    }
}
