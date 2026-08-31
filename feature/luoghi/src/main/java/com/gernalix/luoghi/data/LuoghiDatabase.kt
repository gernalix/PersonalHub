package com.gernalix.luoghi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaceEntity::class,
        PlaceAliasEntity::class,
        PlaceLinkEntity::class,
        PlaceEventEntity::class,
        GlobalStatsStateEntity::class,
        RouteDistanceCacheEntity::class,
        HistoryAuditLogEntity::class,
        HistoryActionEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class LuoghiDatabase : RoomDatabase() {
    abstract fun placeDao(): PlaceDao

    companion object {
        const val DB_NAME = "luoghi.db"

        @Volatile
        private var instance: LuoghiDatabase? = null

        fun get(context: Context): LuoghiDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, DB_NAME)
                    .also { instance = it }
            }
        }

        fun openStaging(context: Context, databaseName: String): LuoghiDatabase =
            build(context.applicationContext, databaseName)

        private fun build(context: Context, databaseName: String): LuoghiDatabase =
            Room.databaseBuilder(context, LuoghiDatabase::class.java, databaseName)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        fun resetForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS place_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        place_id TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        lat REAL,
                        lon REAL,
                        accuracy_m REAL,
                        source TEXT NOT NULL,
                        notes TEXT,
                        FOREIGN KEY(place_id) REFERENCES places(uuid) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_place_events_place_id ON place_events(place_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_place_events_event_type ON place_events(event_type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_place_events_timestamp ON place_events(timestamp)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN first_check_in_at_place INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS global_stats_state (
                        id INTEGER NOT NULL,
                        first_check_in_at_global INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO global_stats_state(id, first_check_in_at_global)
                    SELECT 1, first_check_in_at_global
                    FROM (
                        SELECT MIN(timestamp) AS first_check_in_at_global
                        FROM place_events
                        WHERE event_type = 'CHECK_IN'
                    )
                    WHERE first_check_in_at_global IS NOT NULL
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE places
                    SET first_check_in_at_place = (
                        SELECT MIN(timestamp)
                        FROM place_events
                        WHERE place_events.place_id = places.uuid
                          AND place_events.event_type = 'CHECK_IN'
                    )
                    WHERE EXISTS (
                        SELECT 1
                        FROM place_events
                        WHERE place_events.place_id = places.uuid
                          AND place_events.event_type = 'CHECK_IN'
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS route_distance_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        origin_place_id TEXT NOT NULL,
                        destination_place_id TEXT NOT NULL,
                        travel_mode TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        distance_meters INTEGER NOT NULL,
                        duration_seconds INTEGER NOT NULL,
                        computed_at INTEGER NOT NULL,
                        origin_latitude_snapshot REAL,
                        origin_longitude_snapshot REAL,
                        destination_latitude_snapshot REAL,
                        destination_longitude_snapshot REAL,
                        origin_version INTEGER,
                        destination_version INTEGER,
                        metadata TEXT,
                        FOREIGN KEY(origin_place_id) REFERENCES places(uuid) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(destination_place_id) REFERENCES places(uuid) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_route_distance_cache_origin_place_id ON route_distance_cache(origin_place_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_route_distance_cache_destination_place_id ON route_distance_cache(destination_place_id)")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_route_distance_cache_origin_place_id_destination_place_id_travel_mode_provider
                    ON route_distance_cache(origin_place_id, destination_place_id, travel_mode, provider)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE place_events RENAME TO place_events_old")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS place_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        event_uuid TEXT NOT NULL,
                        session_uuid TEXT NOT NULL,
                        place_id TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        lat REAL,
                        lon REAL,
                        accuracy_m REAL,
                        source TEXT NOT NULL,
                        notes TEXT,
                        FOREIGN KEY(place_id) REFERENCES places(uuid) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO place_events(
                        id,
                        event_uuid,
                        session_uuid,
                        place_id,
                        event_type,
                        timestamp,
                        lat,
                        lon,
                        accuracy_m,
                        source,
                        notes
                    )
                    SELECT
                        id,
                        lower(
                            hex(randomblob(4)) || '-' ||
                            hex(randomblob(2)) || '-' ||
                            '4' || substr(hex(randomblob(2)), 2) || '-' ||
                            substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' ||
                            hex(randomblob(6))
                        ),
                        '',
                        place_id,
                        event_type,
                        timestamp,
                        lat,
                        lon,
                        accuracy_m,
                        source,
                        notes
                    FROM place_events_old
                    """.trimIndent()
                )
                db.execSQL("UPDATE place_events SET session_uuid = event_uuid WHERE event_type = 'CHECK_IN'")
                db.execSQL(
                    """
                    UPDATE place_events
                    SET session_uuid = COALESCE(
                        (
                            SELECT checkins.event_uuid
                            FROM place_events AS checkins
                            WHERE checkins.place_id = place_events.place_id
                              AND checkins.event_type = 'CHECK_IN'
                              AND (
                                  checkins.timestamp < place_events.timestamp
                                  OR (checkins.timestamp = place_events.timestamp AND checkins.id < place_events.id)
                              )
                            ORDER BY checkins.timestamp DESC, checkins.id DESC
                            LIMIT 1
                        ),
                        event_uuid
                    )
                    WHERE event_type = 'CHECK_OUT'
                    """.trimIndent()
                )
                db.execSQL("UPDATE place_events SET session_uuid = event_uuid WHERE session_uuid = ''")
                db.execSQL("DROP TABLE place_events_old")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_place_events_event_uuid ON place_events(event_uuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_place_events_session_uuid ON place_events(session_uuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_place_events_place_id ON place_events(place_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_place_events_event_type ON place_events(event_type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_place_events_timestamp ON place_events(timestamp)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS history_audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        audit_uuid TEXT NOT NULL,
                        action_uuid TEXT,
                        action TEXT NOT NULL,
                        entity_type TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        session_uuid TEXT,
                        operated_at INTEGER NOT NULL,
                        before_json TEXT,
                        after_json TEXT,
                        source TEXT NOT NULL,
                        reason TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_audit_log_action ON history_audit_log(action)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_audit_log_entity_type ON history_audit_log(entity_type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_audit_log_entity_id ON history_audit_log(entity_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_audit_log_session_uuid ON history_audit_log(session_uuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_audit_log_operated_at ON history_audit_log(operated_at)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS history_actions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        action_uuid TEXT NOT NULL,
                        action_type TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        session_uuid TEXT,
                        before_json TEXT NOT NULL,
                        after_json TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        source TEXT NOT NULL,
                        reason TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_history_actions_action_uuid ON history_actions(action_uuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_actions_action_type ON history_actions(action_type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_actions_entity_id ON history_actions(entity_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_actions_session_uuid ON history_actions(session_uuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_actions_status ON history_actions(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_actions_created_at ON history_actions(created_at)")
            }
        }
    }
}
