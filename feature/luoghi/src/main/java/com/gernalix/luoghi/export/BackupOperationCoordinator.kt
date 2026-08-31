package com.gernalix.luoghi.export

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex

object BackupOperationCoordinator {
    val mutex = Mutex()
}

object AutoExportGate {
    private val suppressionDepth = AtomicInteger(0)

    val isSuppressed: Boolean
        get() = suppressionDepth.get() > 0

    suspend fun <T> suppress(block: suspend () -> T): T {
        suppressionDepth.incrementAndGet()
        return try {
            block()
        } finally {
            suppressionDepth.decrementAndGet()
        }
    }
}
