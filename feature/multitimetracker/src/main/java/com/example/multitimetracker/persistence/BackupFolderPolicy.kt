package com.example.multitimetracker.persistence

object BackupFolderPolicy {
    const val PRIMARY_DB_NAME = "personalhub.db"
    const val EMERGENCY_DB_NAME = "personalhub.db.bak"
    const val TEMP_DB_NAME = "personalhub.db.tmp"

    private val timestampedDbName = Regex("""personalhub_\d{8}_\d{6}\.db""")

    fun isTimestampedDbCopy(name: String): Boolean {
        return timestampedDbName.matches(name.trim())
    }

    fun restorableDbNames(entryNames: List<String>): List<String> {
        val lowerEntries = entryNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associateBy { it.lowercase() }
        return buildList {
            lowerEntries[PRIMARY_DB_NAME]?.let { add(it) }
            lowerEntries[EMERGENCY_DB_NAME]?.let { add(it) }
        }
    }
}
