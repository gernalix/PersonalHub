package com.gernalix.sostanze.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SubstanceEntity::class,
        IntakeEventEntity::class,
        StockAdjustmentEntity::class,
        PrescriptionEntity::class,
        InteractionRuleEntity::class,
        InteractionTargetEntity::class,
        NotificationStateEntity::class,
        SettingEntity::class,
        MacroEntity::class,
        MacroItemEntity::class,
    ],
    version = 3,
    exportSchema = false
)
abstract class SostanzeDatabase : RoomDatabase() {
    abstract fun dao(): SostanzeDao

    companion object {
        const val DATABASE_NAME = "sostanze.db"

        @Volatile private var instance: SostanzeDatabase? = null

        fun get(context: Context): SostanzeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SostanzeDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { instance = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE intake_events ADD COLUMN timestamp_utc TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE intake_events ADD COLUMN tap_group_id TEXT")
                db.execSQL("ALTER TABLE stock_adjustments ADD COLUMN timestamp_utc TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE prescriptions ADD COLUMN prescription_date_utc TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notification_state ADD COLUMN scheduled_for_utc TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notification_state ADD COLUMN sent_at_utc TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS macros (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        archived INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_macros_name ON macros(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_macros_archived ON macros(archived)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS macro_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        macro_id INTEGER NOT NULL,
                        substance_id INTEGER NOT NULL,
                        FOREIGN KEY(macro_id) REFERENCES macros(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(substance_id) REFERENCES substances(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_macro_items_macro_id ON macro_items(macro_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_macro_items_substance_id ON macro_items(substance_id)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE substances SET stock_unit = 'mg' WHERE lower(stock_unit) IN ('cpr','capsula','capsule','compressa','compresse','caps','cps','g')")
                db.execSQL("UPDATE substances SET dose_unit = 'mg' WHERE lower(dose_unit) IN ('cpr','capsula','capsule','compressa','compresse','caps','cps','g')")
                db.execSQL("UPDATE intake_events SET dose_unit = 'mg' WHERE lower(dose_unit) IN ('cpr','capsula','capsule','compressa','compresse','caps','cps','g')")
            }
        }
    }
}
