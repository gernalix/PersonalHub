package com.example.multitimetracker.api

import android.net.Uri

object MttPublicSessionsContract {
    const val AUTHORITY = "com.example.multitimetracker.api"
    const val PATH_SESSIONS = "sessions"
    const val PARAM_TAG = "tag"
    const val PARAM_LIMIT = "limit"
    const val COLUMN_ID = "id"
    const val COLUMN_START = "start"
    const val COLUMN_END = "end"
    const val COLUMN_TAGS = "tags"

    fun sessionsUri(tag: String, limit: Int): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(PATH_SESSIONS)
            .appendQueryParameter(PARAM_TAG, tag)
            .appendQueryParameter(PARAM_LIMIT, limit.coerceIn(1, 100).toString())
            .build()

    fun sessionsUriText(tag: String, limit: Int): String =
        "content://$AUTHORITY/$PATH_SESSIONS?$PARAM_TAG=$tag&$PARAM_LIMIT=${limit.coerceIn(1, 100)}"

    val columns = arrayOf(COLUMN_ID, COLUMN_START, COLUMN_END, COLUMN_TAGS)
}
