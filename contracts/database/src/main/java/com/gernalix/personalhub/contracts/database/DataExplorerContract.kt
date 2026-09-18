package com.gernalix.personalhub.contracts.database

import android.content.Intent

object DataExplorerContract {
    const val ACTION_OPEN = "com.gernalix.personalhub.action.DATA_EXPLORER"
    const val EXTRA_TABLE = "com.gernalix.personalhub.extra.DATA_EXPLORER_TABLE"
    private val safeName = Regex("[A-Za-z0-9_-]+")

    fun intent(packageName: String, table: String? = null): Intent {
        val name = table?.trim()?.takeIf { it.isNotEmpty() }
        require(name == null || safeName.matches(name))
        return Intent(ACTION_OPEN).setPackage(packageName).apply {
            name?.let { putExtra(EXTRA_TABLE, it) }
        }
    }

    fun table(intent: Intent?): String? = intent
        ?.getStringExtra(EXTRA_TABLE)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && safeName.matches(it) }
}
