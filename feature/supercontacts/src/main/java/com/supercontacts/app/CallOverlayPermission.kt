package com.supercontacts.app

import android.annotation.SuppressLint
import androidx.core.net.toUri
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings

object CallOverlayPermission {
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun requiresRestrictedSettingsPrimer(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val packageSource = runCatching {
            context.packageManager
                .getInstallSourceInfo(context.packageName)
                .packageSource
        }.getOrNull()
        return requiresRestrictedSettingsPrimer(
            sdkInt = Build.VERSION.SDK_INT,
            packageSource = packageSource,
        )
    }

    // These package-source constants are inlined integers. The pure policy below
    // accepts a supplied SDK for tests and requires API36 before using the values.
    @SuppressLint("InlinedApi")
    internal fun requiresRestrictedSettingsPrimer(
        sdkInt: Int,
        packageSource: Int?,
    ): Boolean =
        sdkInt >= Build.VERSION_CODES.BAKLAVA &&
            (
                packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
                    packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
            )

    fun appInfoIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ("package:${context.packageName}").toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            ("package:${context.packageName}").toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
