package com.example.multitimetracker.persistence

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class PersistentSnapshotLoadState {
    Loading,
    Ready,
    Failed,
}

internal enum class PersistentSaveOrigin {
    STATE_MUTATION,
    APP_BACKGROUND,
    INITIALIZATION,
    IMPORT,
}

internal sealed interface PersistentSaveResult<out T> {
    data class Saved<T>(val revision: Long, val value: T) : PersistentSaveResult<T>
    data class SkippedNotReady(val state: PersistentSnapshotLoadState) : PersistentSaveResult<Nothing>
    data class SkippedStale(val revision: Long, val latestCompletedRevision: Long) : PersistentSaveResult<Nothing>
}

/** Serializes snapshot writes and refuses persistence until authoritative loading completes. */
internal class PersistentSnapshotSaveGate(
    private val beforeLock: (Long) -> Unit = {},
) {
    private val stateLock = Any()
    private val writeLock = ReentrantLock(true)

    @Volatile
    private var loadState = PersistentSnapshotLoadState.Loading
    private var loadGeneration = 0L
    private var nextRevision = 0L
    private var latestCompletedRevision = 0L

    fun markLoading() {
        writeLock.withLock {
            synchronized(stateLock) {
                loadGeneration += 1L
                loadState = PersistentSnapshotLoadState.Loading
            }
        }
    }

    fun markReady() {
        writeLock.withLock {
            synchronized(stateLock) {
                loadState = PersistentSnapshotLoadState.Ready
            }
        }
    }

    fun markFailed() {
        writeLock.withLock {
            synchronized(stateLock) {
                loadGeneration += 1L
                loadState = PersistentSnapshotLoadState.Failed
            }
        }
    }

    fun currentLoadState(): PersistentSnapshotLoadState = loadState

    fun <T> execute(
        origin: PersistentSaveOrigin,
        block: (revision: Long, origin: PersistentSaveOrigin) -> T,
    ): PersistentSaveResult<T> {
        val (revision, requestGeneration) = synchronized(stateLock) {
            if (loadState != PersistentSnapshotLoadState.Ready) {
                return PersistentSaveResult.SkippedNotReady(loadState)
            }
            nextRevision += 1L
            nextRevision to loadGeneration
        }

        beforeLock(revision)
        return writeLock.withLock {
            synchronized(stateLock) {
                if (loadState != PersistentSnapshotLoadState.Ready) {
                    return@withLock PersistentSaveResult.SkippedNotReady(loadState)
                }
                if (requestGeneration != loadGeneration || revision < latestCompletedRevision) {
                    return@withLock PersistentSaveResult.SkippedStale(
                        revision = revision,
                        latestCompletedRevision = latestCompletedRevision,
                    )
                }
            }

            val value = block(revision, origin)
            synchronized(stateLock) {
                latestCompletedRevision = maxOf(latestCompletedRevision, revision)
            }
            PersistentSaveResult.Saved(revision = revision, value = value)
        }
    }
}
