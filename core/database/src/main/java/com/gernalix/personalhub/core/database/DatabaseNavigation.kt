package com.gernalix.personalhub.core.database

import android.content.Context
import android.content.Intent
import android.net.Uri

object DatabaseNavigation {
    fun open(context: Context) {
        context.startActivity(Intent().setClassName(context.packageName, "com.gernalix.personalhub.DatabaseActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openImport(context: Context, source: Uri) {
        context.startActivity(
            Intent().setClassName(context.packageName, "com.gernalix.personalhub.DatabaseActivity")
                .setData(source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}
