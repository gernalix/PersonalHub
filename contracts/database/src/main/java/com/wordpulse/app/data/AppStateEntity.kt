package com.wordpulse.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "current_session_id") val currentSessionId: String?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
