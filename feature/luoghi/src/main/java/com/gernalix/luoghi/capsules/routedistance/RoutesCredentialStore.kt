package com.gernalix.luoghi.capsules.routedistance

import android.content.Context
import com.gernalix.luoghi.capsules.googlecredentials.GoogleCredentialKind
import com.gernalix.luoghi.capsules.googlecredentials.GoogleCredentialStore

internal class RoutesCredentialStore(
    context: Context,
    keyAlias: String = GoogleCredentialKind.ROUTES.keyAlias,
) {
    private val store = GoogleCredentialStore(context, GoogleCredentialKind.ROUTES, keyAlias)
    fun resolve(legacy: () -> String): String = store.resolve(legacy)
    fun save(value: String) = store.save(value)
}
