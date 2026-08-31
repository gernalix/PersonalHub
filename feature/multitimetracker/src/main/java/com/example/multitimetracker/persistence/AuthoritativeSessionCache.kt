// v471
package com.example.multitimetracker.persistence

import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.session.SessionCore
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag

internal data class AuthoritativeSessionCache(
    val tags: List<Tag>,
    val closedSessions: List<ClosedSessionRecord>,
    val tagSessions: List<TaggedSessionRecord>,
    val chronologySessions: List<SessionUi>,
    val runningSessions: List<SessionUi>,
    val activeTagTotalsMsByTagId: Map<Long, Long>,
    val runningMinStartByTagId: Map<Long, Long>,
    val tagTotalsMsByTagId: Map<Long, Long>,
    val tagLastUsedMsByTagId: Map<Long, Long>,
    val activeTagStartByTagId: Map<Long, Long>,
)

internal object AuthoritativeSessionCacheBuilder {

    fun fromSessionTables(
        tags: List<Tag>,
        sessionCore: SessionCore,
    ): AuthoritativeSessionCache {
        val nowMs = System.currentTimeMillis()
        val allSessions = normalizeExpiredTimedSessions(sessionCore.readAllSessions(), nowMs)
        return fromReadModel(
            tags = tags,
            chronologySessions = allSessions,
            runningSessions = allSessions.filter { it.endMs == null && it.deletedAtMs == null },
        )
    }

    fun fromReadModel(
        tags: List<Tag>,
        chronologySessions: List<SessionUi>,
        runningSessions: List<SessionUi>,
    ): AuthoritativeSessionCache {
        val tagNamesById = tags.associateBy({ it.id }, { it.name })
        val closedSessionRows = chronologySessions.filter { it.deletedAtMs == null && it.endMs != null }

        val closedSessions = closedSessionRows.map { session ->
            ClosedSessionRecord(
                sessionId = session.id,
                sessionTitle = session.title,
                startTs = session.startMs,
                endTs = session.endMs ?: session.startMs,
            )
        }

        val tagSessions = ArrayList<TaggedSessionRecord>(closedSessionRows.size * 2)
        closedSessionRows.forEach { session ->
            val end = session.endMs ?: return@forEach
            session.tagIds.forEach { tagId ->
                tagSessions.add(
                    TaggedSessionRecord(
                        tagId = tagId,
                        tagName = tagNamesById[tagId] ?: "tag_$tagId",
                        sessionId = session.id,
                        sessionTitle = session.title,
                        startTs = session.startMs,
                        endTs = end,
                    )
                )
            }
        }

        val runningMinStartByTagId = linkedMapOf<Long, Long>()
        runningSessions.forEach { session ->
            session.tagIds.forEach { tagId ->
                val previous = runningMinStartByTagId[tagId]
                if (previous == null || session.startMs < previous) {
                    runningMinStartByTagId[tagId] = session.startMs
                }
            }
        }

        val activeTagIds = runningSessions.asSequence()
            .flatMap { it.tagIds.asSequence() }
            .toSet()
        val allTagIds = chronologySessions.asSequence()
            .flatMap { it.tagIds.asSequence() }
            .toSet()

        val closedIntervalsByTagId = linkedMapOf<Long, MutableList<Pair<Long, Long>>>()
        closedSessionRows.forEach { session ->
            val endMs = session.endMs ?: return@forEach
            session.tagIds.forEach { tagId ->
                closedIntervalsByTagId.getOrPut(tagId) { mutableListOf() }.add(session.startMs to endMs)
            }
        }

        fun unionTotalsFor(tagIds: Set<Long>): Map<Long, Long> = buildMap(tagIds.size) {
            tagIds.forEach { tagId ->
                put(tagId, unionTotalMs(closedIntervalsByTagId[tagId].orEmpty()))
            }
        }

        val activeTotals = unionTotalsFor(activeTagIds)
        val allTotals = unionTotalsFor(allTagIds)

        val tagLastUsedMsByTagId = linkedMapOf<Long, Long>()
        chronologySessions.forEach { session ->
            session.tagIds.forEach { tagId ->
                val previous = tagLastUsedMsByTagId[tagId]
                if (previous == null || session.startMs > previous) {
                    tagLastUsedMsByTagId[tagId] = session.startMs
                }
            }
        }

        val activeCountByTagId = mutableMapOf<Long, Int>()
        runningSessions.forEach { session ->
            session.tagIds.forEach { tagId ->
                activeCountByTagId[tagId] = (activeCountByTagId[tagId] ?: 0) + 1
            }
        }

        val alignedTags = tags.map { tag ->
            tag.copy(
                totalMs = allTotals[tag.id] ?: 0L,
                activeChildrenCount = activeCountByTagId[tag.id] ?: 0,
                lastStartedAtMs = runningMinStartByTagId[tag.id],
            )
        }

        return AuthoritativeSessionCache(
            tags = alignedTags,
            closedSessions = closedSessions,
            tagSessions = tagSessions,
            chronologySessions = chronologySessions,
            runningSessions = runningSessions,
            activeTagTotalsMsByTagId = activeTotals,
            runningMinStartByTagId = runningMinStartByTagId,
            tagTotalsMsByTagId = allTotals,
            tagLastUsedMsByTagId = tagLastUsedMsByTagId,
            activeTagStartByTagId = runningMinStartByTagId,
        )
    }

    private fun unionTotalMs(intervals: List<Pair<Long, Long>>): Long {
        if (intervals.isEmpty()) return 0L
        val sorted = intervals.sortedBy { it.first }
        var total = 0L
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for (index in 1 until sorted.size) {
            val (start, end) = sorted[index]
            if (end <= start) continue
            if (start <= curEnd) {
                if (end > curEnd) curEnd = end
            } else {
                total += curEnd - curStart
                curStart = start
                curEnd = end
            }
        }
        total += curEnd - curStart
        return total.coerceAtLeast(0L)
    }

    private fun normalizeExpiredTimedSessions(
        sessions: List<SessionUi>,
        nowMs: Long,
    ): List<SessionUi> {
        return sessions.map { session ->
            val expectedEndMs = session.expectedEndMs
            if (session.endMs == null && expectedEndMs != null && nowMs >= expectedEndMs) {
                session.copy(endMs = expectedEndMs)
            } else {
                session
            }
        }
    }
}



