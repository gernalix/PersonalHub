package com.gernalix.personalhub.core.migration

import android.database.sqlite.SQLiteDatabase
import com.gernalix.personalhub.core.model.SourceTablePlan
import com.gernalix.personalhub.core.model.SourceTableRows

class MigrationSourceDatabaseScanner(
    private val database: SQLiteDatabase,
) {
    fun scan(plans: List<SourceTablePlan>): List<SourceTableRows> =
        plans.map { scan(it) }

    fun scan(plan: SourceTablePlan): SourceTableRows {
        require(plan.sourceIdColumns.isNotEmpty()) {
            "sourceIdColumns must not be empty for ${plan.sourceApp.key}.${plan.sourceTable}"
        }
        val idExpressions = plan.sourceIdColumns.map(::quoteIdentifierPath)
        val tableName = quoteIdentifierPath(plan.sourceTable)
        val ids = database.rawQuery(
            "SELECT ${idExpressions.joinToString(", ")} FROM $tableName ORDER BY ${idExpressions.joinToString(", ")}",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val sourceId = readSourceId(plan, cursor)
                    require(!sourceId.isNullOrBlank()) {
                        "Blank source id in ${plan.sourceApp.key}.${plan.sourceTable}"
                    }
                    add(sourceId)
                }
            }
        }
        return SourceTableRows(
            sourceApp = plan.sourceApp,
            sourceTable = plan.sourceTable,
            sourceIds = ids,
        )
    }

    private fun readSourceId(
        plan: SourceTablePlan,
        cursor: android.database.Cursor,
    ): String =
        if (plan.sourceIdColumns.size == 1) {
            cursor.getString(0)
        } else {
            plan.sourceIdColumns.mapIndexed { index, column ->
                val value = cursor.getString(index)
                require(!value.isNullOrBlank()) {
                    "Blank source id segment $column in ${plan.sourceApp.key}.${plan.sourceTable}"
                }
                "$column=$value"
            }.joinToString("|")
        }

    companion object {
        fun quoteIdentifierPath(identifierPath: String): String =
            identifierPath
                .split(".")
                .joinToString(".") { part ->
                    require(part.isNotBlank()) { "Identifier segment must not be blank" }
                    "\"${part.replace("\"", "\"\"")}\""
                }
    }
}
