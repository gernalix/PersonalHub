package com.example.multitimetracker.capsules.remotesync

import com.example.multitimetracker.BuildConfig

internal data class RemoteSyncConfig(
    val baseUrl: String,
    val token: String,
) {
    val isEnabled: Boolean
        get() = RemoteSyncContract.validateBaseUrl(baseUrl) != null &&
            token.isNotBlank() && token.none(Char::isWhitespace)

    companion object {
        fun fromBuildConfig(): RemoteSyncConfig = RemoteSyncConfig(
            baseUrl = BuildConfig.MTT_DATASETTE_SYNC_BASE_URL,
            token = BuildConfig.MTT_DATASETTE_SYNC_TOKEN,
        )
    }
}
