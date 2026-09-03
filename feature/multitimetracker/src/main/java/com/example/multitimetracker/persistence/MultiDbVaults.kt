package com.example.multitimetracker.persistence

import android.content.Context

/** Timer headings refer to the one PersonalHub database. Separate vault switching was retired. */
object MultiDbVaults {
    data class ActivationNotice(val vaultName: String, val verified: Boolean)
    fun getActiveVaultName(context: Context) = "PersonalHub"
    fun consumePendingActivationNotice(context: Context, activeSignature: String): ActivationNotice? = null
}
