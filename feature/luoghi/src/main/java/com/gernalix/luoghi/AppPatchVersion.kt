package com.gernalix.luoghi

import android.content.Context

/** Compatibility wrapper retained for callers; value is the host PersonalHub version. */
object AppPatchVersion {
    private const val FALLBACK_VALUE = "unknown"

    @Volatile
    private var cachedValue: String? = null

    fun current(context: Context): String {
        cachedValue?.let { return it }
        return synchronized(this) {
            cachedValue ?: load(context.applicationContext).also { cachedValue = it }
        }
    }

    private fun load(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: FALLBACK_VALUE
}
