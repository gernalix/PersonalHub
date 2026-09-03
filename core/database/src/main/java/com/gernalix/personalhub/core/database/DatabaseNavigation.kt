package com.gernalix.personalhub.core.database

import android.content.Context
import android.content.Intent

object DatabaseNavigation {
    fun open(context: Context) {
        context.startActivity(Intent().setClassName(context.packageName, "com.gernalix.personalhub.DatabaseActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
