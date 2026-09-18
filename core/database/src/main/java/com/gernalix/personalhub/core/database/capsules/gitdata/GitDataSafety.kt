package com.gernalix.personalhub.core.database.capsules.gitdata

data class GitBatchSafety(
    val suspicious: Boolean,
    val eventCount: Int,
    val deleteCount: Int,
    val reason: String?,
)

/**
 * Prevents an automatic background push from normalizing a catastrophic local mistake into the
 * remote current-state materialization. History is still local and no data is discarded.
 */
internal object GitDataSafety {
    fun evaluate(bundle: GitExportBundle): GitBatchSafety {
        val deletes = bundle.events.count { it.operation == "DELETE" }
        val events = bundle.events.size
        val currentRows = bundle.manifest.getJSONArray("tables").let { tables ->
            var total = 0L
            for (i in 0 until tables.length()) total += tables.getJSONObject(i).optLong("rows", 0L)
            total
        }
        val destructiveShare = if (currentRows + deletes == 0L) 0.0
            else deletes.toDouble() / (currentRows + deletes).toDouble()
        val reason = when {
            deletes >= 100 && destructiveShare >= 0.20 ->
                "Large destructive batch: " + deletes + " deletes (" +
                    (destructiveShare * 100).toInt() + "% of logical rows)"
            events >= 10_000 ->
                "Unusually large batch: " + events + " history events"
            else -> null
        }
        return GitBatchSafety(
            suspicious = reason != null,
            eventCount = events,
            deleteCount = deletes,
            reason = reason,
        )
    }
}
