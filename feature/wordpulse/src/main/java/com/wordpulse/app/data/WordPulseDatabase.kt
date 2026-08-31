package com.wordpulse.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppStateEntity::class,
        CorrectionEvent::class,
        WordEntry::class,
        WordSession::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class WordPulseDatabase : RoomDatabase() {
    abstract fun wordPulseDao(): WordPulseDao

    companion object {
        const val DATABASE_NAME = "wordpulse.db"

        fun create(context: Context): WordPulseDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                WordPulseDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `correction_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `original_word_entry_id` INTEGER NOT NULL,
                        `original_word` TEXT NOT NULL,
                        `normalized_word` TEXT NOT NULL,
                        `session_id` TEXT NOT NULL,
                        `submitted_at_utc_ms` INTEGER NOT NULL,
                        `corrected_at_utc_ms` INTEGER NOT NULL,
                        `correction_latency_ms` INTEGER NOT NULL,
                        `action_type` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_correction_events_original_word_entry_id` ON `correction_events` (`original_word_entry_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_correction_events_session_id` ON `correction_events` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_correction_events_corrected_at_utc_ms` ON `correction_events` (`corrected_at_utc_ms`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `capture_policy_version` TEXT")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `typing_started_at_utc_ms` INTEGER")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `submitted_at_utc_ms` INTEGER")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `typing_duration_ms` INTEGER")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `final_character_count` INTEGER")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `inserted_character_count` INTEGER")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `deleted_character_count` INTEGER")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `replacement_count` INTEGER")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `correction_action_count` INTEGER")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `longest_inter_key_pause_ms` INTEGER")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `mean_inter_key_interval_ms` REAL")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `inter_key_interval_variability_ms` REAL")
                db.execSQL("ALTER TABLE `word_entries` ADD COLUMN `invalid_input_attempt_count` INTEGER")
            }
        }
    }
}
