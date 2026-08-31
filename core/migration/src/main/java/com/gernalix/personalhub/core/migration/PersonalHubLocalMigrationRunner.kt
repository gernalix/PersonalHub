package com.gernalix.personalhub.core.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.gernalix.personalhub.core.model.MigrationAuditReport
import com.gernalix.personalhub.core.model.MigrationInventory
import com.gernalix.personalhub.core.model.MigrationVerifier
import com.gernalix.personalhub.core.model.SourceApp
import com.gernalix.personalhub.core.model.SourceTablePlan
import com.gernalix.personalhub.core.model.SourceTableRows

class PersonalHubLocalMigrationRunner(
    context: Context,
    private val store: MigrationMappingStore = MigrationMappingStore(context),
) {
    private val appContext = context.applicationContext

    fun run(): LocalMigrationRunResult {
        val sourceRows = localSources.flatMap(::scanLocalSource)
        val mappings = MigrationVerifier.mappingsFor(sourceRows)
        val report = MigrationVerifier.verify(sourceRows, mappings)
        check(report.passed) {
            "Local migration verification failed before writing mappings."
        }
        store.replaceAll(mappings)
        return LocalMigrationRunResult(
            report = report,
            mappingDatabaseIntegrity = store.integrityCheck(),
            mappingCountsBySourceTable = store.mappingCountsBySourceTable(),
        )
    }

    private fun scanLocalSource(source: LocalSourceDatabase): List<SourceTableRows> {
        val dbFile = appContext.getDatabasePath(source.databaseName)
        if (!dbFile.isFile) return source.emptyRows()
        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val scanner = MigrationSourceDatabaseScanner(db)
            source.plans.map { plan ->
                val tableScanPlan = plan.copy(sourceTable = source.localTableName(plan))
                if (!db.hasTable(tableScanPlan.sourceTable) || !db.hasColumns(tableScanPlan.sourceTable, tableScanPlan.sourceIdColumns)) {
                    SourceTableRows(plan.sourceApp, plan.sourceTable, emptyList())
                } else {
                    scanner.scan(tableScanPlan).copy(sourceTable = plan.sourceTable)
                }
            }
        }
    }

    private fun LocalSourceDatabase.emptyRows(): List<SourceTableRows> =
        plans.map { plan -> SourceTableRows(plan.sourceApp, plan.sourceTable, emptyList()) }

    private fun SQLiteDatabase.hasTable(tableName: String): Boolean =
        rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(tableName.substringAfterLast(".")),
        ).use { cursor -> cursor.moveToFirst() }

    private fun SQLiteDatabase.hasColumns(tableName: String, columns: List<String>): Boolean {
        val tableColumns = rawQuery(
            "PRAGMA table_info(${MigrationSourceDatabaseScanner.quoteIdentifierPath(tableName)})",
            emptyArray(),
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        return tableColumns.containsAll(columns)
    }

    data class LocalMigrationRunResult(
        val report: MigrationAuditReport,
        val mappingDatabaseIntegrity: String,
        val mappingCountsBySourceTable: Map<String, Int>,
    )

    private data class LocalSourceDatabase(
        val sourceApp: SourceApp,
        val databaseName: String,
        val tablePrefix: String? = null,
    ) {
        val plans: List<SourceTablePlan> =
            MigrationInventory.plans.filter { plan ->
                plan.sourceApp == sourceApp &&
                    if (tablePrefix == null) {
                        "." !in plan.sourceTable
                    } else {
                        plan.sourceTable.startsWith("$tablePrefix.")
                    }
            }

        fun localTableName(plan: SourceTablePlan): String =
            tablePrefix?.let { plan.sourceTable.removePrefix("$it.") } ?: plan.sourceTable
    }

    companion object {
        private val localSources = listOf(
            LocalSourceDatabase(SourceApp.LUOGHI, "luoghi.db"),
            LocalSourceDatabase(SourceApp.MULTITIMETRACKER, "multitimer.db"),
            LocalSourceDatabase(SourceApp.MULTITIMETRACKER, "mtt_remote_sync.db", tablePrefix = "mtt_remote_sync"),
            LocalSourceDatabase(SourceApp.SOSTANZE, "sostanze.db"),
            LocalSourceDatabase(SourceApp.SUPERCONTACTS, "super_contacts.db"),
            LocalSourceDatabase(SourceApp.WORDPULSE, "wordpulse.db"),
        )
    }
}

private inline fun <T> SQLiteDatabase.use(block: (SQLiteDatabase) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
