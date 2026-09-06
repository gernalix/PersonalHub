package com.supercontacts.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class ContactEventWithContactName(
    @Embedded val event: ContactEventEntity,
    @ColumnInfo(name = "contact_display_name")
    val contactDisplayName: String,
)
