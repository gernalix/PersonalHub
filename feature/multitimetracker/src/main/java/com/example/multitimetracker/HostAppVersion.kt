package com.example.multitimetracker

import android.content.Context
import android.os.Build

data class HostAppVersion(
    val code: Long,
    val name: String
) {
    companion object {
        fun current(context: Context): HostAppVersion {
            val appContext = context.applicationContext
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            val name = info.versionName?.takeIf { it.isNotBlank() } ?: code.toString()
            return HostAppVersion(code = code, name = name)
        }
    }
}
