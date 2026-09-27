package com.gernalix.personalhub.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PersonalHubDatabaseMigrations {
    fun canMigrateFrom(version: Int?): Boolean =
        version == PersonalHubDatabase.SCHEMA_VERSION || version == 23

    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP VIEW IF EXISTS `export_audit_events_utc_z`")
            db.execSQL("DROP TABLE IF EXISTS `alert_place_tag_targets`")
            db.execSQL("DROP TABLE IF EXISTS `audit_events`")
            db.execSQL("DROP TABLE IF EXISTS `alert_rules`")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `alert_rules` (
                    `id` TEXT NOT NULL,
                    `domain` TEXT NOT NULL,
                    `trigger` TEXT NOT NULL,
                    `target_kind` TEXT NOT NULL,
                    `entity_id` TEXT,
                    `match_mode` TEXT NOT NULL,
                    `message` TEXT NOT NULL,
                    `scope` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `cooldown_ms` INTEGER NOT NULL,
                    `last_fired_at` INTEGER,
                    `config_json` TEXT NOT NULL DEFAULT '{}',
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `deleted_at` INTEGER,
                    PRIMARY KEY(`id`)
                )""".trimIndent(),
            )
            listOf("domain", "trigger", "target_kind", "entity_id", "enabled", "deleted_at").forEach { column ->
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_rules_" + column + "` ON `alert_rules` (`" + column + "`)")
            }

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `alert_rule_targets` (
                    `rule_id` TEXT NOT NULL,
                    `target_id` TEXT NOT NULL,
                    PRIMARY KEY(`rule_id`, `target_id`),
                    FOREIGN KEY(`rule_id`) REFERENCES `alert_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_rule_targets_rule_id` ON `alert_rule_targets` (`rule_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_rule_targets_target_id` ON `alert_rule_targets` (`target_id`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `alert_firings` (
                    `id` TEXT NOT NULL,
                    `rule_id` TEXT NOT NULL,
                    `domain` TEXT NOT NULL,
                    `trigger` TEXT NOT NULL,
                    `entity_id` TEXT,
                    `entity_label` TEXT,
                    `tag_names` TEXT NOT NULL,
                    `delivery` TEXT NOT NULL,
                    `message` TEXT NOT NULL,
                    `fired_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_firings_rule_id` ON `alert_firings` (`rule_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_firings_domain` ON `alert_firings` (`domain`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_firings_entity_id` ON `alert_firings` (`entity_id`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_alert_firings_fired_at_id` " +
                    "ON `alert_firings` (`fired_at` DESC, `id` DESC)",
            )
        }
    }
}