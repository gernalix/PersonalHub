// v324
package com.example.multitimetracker.data.importexport

object FallbackRestoreManager {

    fun shouldTriggerFallback(errors: List<String>): Boolean {
        return errors.isNotEmpty()
    }

    fun buildUserMessage(errors: List<String>): String {
        if (errors.isEmpty()) return "Import successful. All counts match."

        return buildString {
            append("Import integrity check failed:\n")
            errors.forEach {
                append("- ")
                append(it)
                append("\n")
            }
            append("\nFallback restore is recommended.")
        }
    }
}
