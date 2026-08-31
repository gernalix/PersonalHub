package com.supercontacts.app.data.local

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class ContactWithFields(
    @Embedded val contact: ContactEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "contact_id",
    )
    val fields: List<ContactFieldEntity>,
)

data class ContactWithFieldsAndTags(
    @Embedded val contact: ContactEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "contact_id",
    )
    val fields: List<ContactFieldEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ContactTagCrossRef::class,
            parentColumn = "contact_id",
            entityColumn = "tag_id",
        ),
    )
    val tags: List<TagEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "contact_id",
    )
    val messagingLinks: List<ContactMessagingLinkEntity>,
)
