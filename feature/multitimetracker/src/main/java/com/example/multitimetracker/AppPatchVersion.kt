package com.example.multitimetracker

import android.content.Context

/** Compatibility wrapper retained for callers; value is the host PersonalHub version. */
object AppPatchVersion {
    const val AUTO_CONSISTENCY_REVISION = 1L

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
