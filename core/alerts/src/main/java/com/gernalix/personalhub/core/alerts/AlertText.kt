package com.gernalix.personalhub.core.alerts

import android.content.Context
import androidx.annotation.StringRes

/** The existing modules have no Italian resource locale; select scoped Alerts copy without enabling global translation lint. */
object AlertText {
    fun get(context: Context, @StringRes english: Int, @StringRes italian: Int): String =
        context.getString(if (context.resources.configuration.locales[0].language == "it") italian else english)
}
