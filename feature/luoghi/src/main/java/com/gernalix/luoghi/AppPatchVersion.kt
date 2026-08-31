package com.gernalix.luoghi

import android.content.Context

object AppPatchVersion {
    private const val PATCH_VERSION_ASSET = "patch-version.txt"
    private const val FALLBACK_VALUE = "unknown"

    @Volatile
    private var cachedValue: String? = null

    fun current(context: Context): String {
        cachedValue?.let { return it }
        return synchronized(this) {
            cachedValue ?: load(context.applicationContext).also { cachedValue = it }
        }
    }

    private fun load(context: Context): String {
        return runCatching {
            context.assets.open(PATCH_VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: FALLBACK_VALUE
    }
}
