package com.supercontacts.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class ContactInitiativeWithContactName(
    @Embedded val initiative: ContactInitiativeEntity,
    @ColumnInfo(name = "contact_display_name")
    val contactDisplayName: String,
)
