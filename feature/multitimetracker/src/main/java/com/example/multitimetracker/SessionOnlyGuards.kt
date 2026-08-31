// v471
package com.example.multitimetracker

internal const val LEGACY_TASK_PATH_ERROR = "LEGACY TASK PATH USED - FORBIDDEN AFTER v471"

internal fun failLegacyTaskPath(): Nothing = throw IllegalStateException(LEGACY_TASK_PATH_ERROR)

internal fun requireSessionOnlyMode(sessionOnlyModeEnabled: Boolean) {
    if (!sessionOnlyModeEnabled) {
        failLegacyTaskPath()
    }
}

internal fun requireNoLegacyRuntimeBootstrap(
    hasAuthoritativeChronology: Boolean,
    hasLegacySnapshotData: Boolean,
) {
    if (!hasAuthoritativeChronology && hasLegacySnapshotData) {
        failLegacyTaskPath()
    }
}
