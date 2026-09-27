package com.gernalix.personalhub.core.alerts

import android.content.Context
import androidx.annotation.StringRes

/** Alerts copy is intentionally English-only across PersonalHub. */
object AlertText {
    fun get(context: Context, @StringRes english: Int, @Suppress("UNUSED_PARAMETER") @StringRes italian: Int): String =
        context.getString(english)
}
