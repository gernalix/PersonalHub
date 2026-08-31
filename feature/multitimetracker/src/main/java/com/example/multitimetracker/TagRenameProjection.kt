package com.example.multitimetracker

import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag

private fun deriveProjectedSessionTitle(tagIds: Set<Long>, tags: List<Tag>): String {
    if (tagIds.isEmpty()) return ""
    val tagsById = tags.associateBy { it.id }
    return tagIds.toList()
        .sorted()
        .mapNotNull { id -> tagsById[id]?.name?.trim()?.takeIf { it.isNotEmpty() } }
        .joinToString(" · ")
        .trim()
}

internal fun buildAutoDerivedSessionTitleUpdates(
    sessions: List<SessionUi>,
    beforeTags: List<Tag>,
    afterTags: List<Tag>,
): Map<Long, String> {
    if (sessions.isEmpty()) return emptyMap()
    return buildMap {
        sessions.forEach { session ->
            val previousDerivedTitle = deriveProjectedSessionTitle(session.tagIds, beforeTags)
            if (previousDerivedTitle.isBlank()) return@forEach
            val updatedDerivedTitle = deriveProjectedSessionTitle(session.tagIds, afterTags)
            if (updatedDerivedTitle.isBlank() || updatedDerivedTitle == previousDerivedTitle) return@forEach
            if (session.title.trim() == previousDerivedTitle) {
                put(session.id, updatedDerivedTitle)
            }
        }
    }
}

internal fun buildSnapshotDerivedSessionTitleUpdates(
    snapshotTagSessions: List<TaggedSessionRecord>,
    sessions: List<SessionUi>,
    currentTags: List<Tag>,
): Map<Long, String> {
    if (snapshotTagSessions.isEmpty() || sessions.isEmpty()) return emptyMap()
    val legacyDerivedTitleBySessionId = snapshotTagSessions
        .groupBy { it.sessionId }
        .mapValues { (_, records) ->
            records.asSequence()
                .distinctBy { it.tagId }
                .sortedBy { it.tagId }
                .mapNotNull { it.tagName.trim().takeIf { name -> name.isNotEmpty() } }
                .joinToString(" · ")
                .trim()
        }

    return buildMap {
        sessions.forEach { session ->
            val legacyDerivedTitle = legacyDerivedTitleBySessionId[session.id]
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val currentDerivedTitle = deriveProjectedSessionTitle(session.tagIds, currentTags)
            if (currentDerivedTitle.isBlank() || currentDerivedTitle == legacyDerivedTitle) return@forEach
            if (session.title.trim() == legacyDerivedTitle) {
                put(session.id, currentDerivedTitle)
            }
        }
    }
}

internal fun applySessionTitleUpdates(
    sessions: List<SessionUi>,
    sessionTitlesById: Map<Long, String>,
): List<SessionUi> {
    if (sessions.isEmpty() || sessionTitlesById.isEmpty()) return sessions
    return sessions.map { session ->
        val updatedTitle = sessionTitlesById[session.id] ?: return@map session
        if (session.title == updatedTitle) session else session.copy(title = updatedTitle)
    }
}

internal fun applyClosedSessionTitleUpdates(
    sessions: List<ClosedSessionRecord>,
    sessionTitlesById: Map<Long, String>,
): List<ClosedSessionRecord> {
    if (sessions.isEmpty() || sessionTitlesById.isEmpty()) return sessions
    return sessions.map { session ->
        val updatedTitle = sessionTitlesById[session.sessionId] ?: return@map session
        if (session.sessionTitle == updatedTitle) session else session.copy(sessionTitle = updatedTitle)
    }
}

internal fun canonicalizeTaggedSessionRecords(
    tagSessions: List<TaggedSessionRecord>,
    tags: List<Tag>,
    sessionTitlesById: Map<Long, String> = emptyMap(),
): List<TaggedSessionRecord> {
    if (tagSessions.isEmpty()) return tagSessions
    val canonicalTagNamesById = tags.associate { tag -> tag.id to tag.name.trim() }
    return tagSessions.map { record ->
        val canonicalTagName = canonicalTagNamesById[record.tagId]
            ?.takeIf { it.isNotEmpty() && it != "tag_${record.tagId}" }
        val canonicalSessionTitle = sessionTitlesById[record.sessionId]
        val resolvedTagName = canonicalTagName ?: record.tagName
        val resolvedSessionTitle = canonicalSessionTitle ?: record.sessionTitle
        if (resolvedTagName == record.tagName && resolvedSessionTitle == record.sessionTitle) {
            record
        } else {
            record.copy(
                tagName = resolvedTagName,
                sessionTitle = resolvedSessionTitle
            )
        }
    }
}
