package com.example.multitimetracker.api

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray

class MttPublicSessionsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (uri.authority != MttPublicSessionsContract.AUTHORITY ||
            uri.pathSegments.singleOrNull() != MttPublicSessionsContract.PATH_SESSIONS
        ) {
            return MatrixCursor(MttPublicSessionsContract.columns)
        }
        val tag = uri.getQueryParameter(MttPublicSessionsContract.PARAM_TAG).orEmpty()
        val limit = uri.getQueryParameter(MttPublicSessionsContract.PARAM_LIMIT)
            ?.toIntOrNull()
            ?: 3
        val appContext = requireNotNull(context).applicationContext
        val rows = MttPublicSessionsRepository(appContext).latestSessionsByTag(tag, limit)
        return MatrixCursor(MttPublicSessionsContract.columns).apply {
            rows.forEach { row ->
                addRow(
                    arrayOf<Any?>(
                        row.id,
                        row.startMs,
                        row.endMs,
                        JSONArray(row.tags).toString(),
                    )
                )
            }
        }
    }

    override fun getType(uri: Uri): String? =
        if (uri.authority == MttPublicSessionsContract.AUTHORITY &&
            uri.pathSegments.singleOrNull() == MttPublicSessionsContract.PATH_SESSIONS
        ) {
            "vnd.android.cursor.dir/vnd.com.example.multitimetracker.session"
        } else {
            null
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
