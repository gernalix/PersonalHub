package com.example.multitimetracker.capsules.tags.public

import com.example.multitimetracker.model.Tag

data class TagsSnapshotProjection(
    val tags: List<Tag>,
    val activeTagStartByTagId: Map<Long, Long>,
)
