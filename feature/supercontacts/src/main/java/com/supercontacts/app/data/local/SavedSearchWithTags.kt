package com.supercontacts.app.data.local

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class SavedSearchWithTags(
    @Embedded val savedSearch: SavedSearchEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = SavedSearchTagCrossRef::class,
            parentColumn = "saved_search_id",
            entityColumn = "tag_id",
        ),
    )
    val tags: List<TagEntity>,
)
