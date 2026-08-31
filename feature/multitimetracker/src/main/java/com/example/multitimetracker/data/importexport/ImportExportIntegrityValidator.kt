// v324
package com.example.multitimetracker.data.importexport

data class ImportExportCounts(
    val sessions: Int,
    val tags: Int,
    val alerts: Int,
    val chains: Int
)

object ImportExportIntegrityValidator {

    fun compare(
        expected: ImportExportCounts,
        actual: ImportExportCounts
    ): List<String> {

        val errors = mutableListOf<String>()

        if (expected.sessions != actual.sessions) {
            errors.add("Sessions mismatch: expected=${expected.sessions}, actual=${actual.sessions}")
        }
        if (expected.tags != actual.tags) {
            errors.add("Tags mismatch: expected=${expected.tags}, actual=${actual.tags}")
        }
        if (expected.alerts != actual.alerts) {
            errors.add("Alerts mismatch: expected=${expected.alerts}, actual=${actual.alerts}")
        }
        if (expected.chains != actual.chains) {
            errors.add("Chains mismatch: expected=${expected.chains}, actual=${actual.chains}")
        }

        return errors
    }
}
