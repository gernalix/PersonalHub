package com.gernalix.luoghi.capsules.location

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocationFallbackTest {
    private val now = 1_000_000L
    private val valid = LocationSample(55.0, 12.0, 10.0, now)

    @Test fun timeoutStillUsesRecentAccurateCachedFix() = runBlocking {
        val result = resolveLocationWithFallback(
            current = { delay(100); null }, lastKnown = { valid },
            currentTimeoutMs = 10, now = { now },
        )
        assertEquals(valid, result)
    }

    @Test fun freshFixAvoidsCacheAndValidationUsesCompletionTime() = runBlocking {
        val result = resolveLocationWithFallback(
            current = { valid }, lastKnown = { error("Cache must not be called") }, now = { now },
        )
        assertEquals(valid, result)
    }

    @Test fun fallbackRejectsStaleInaccurateAndInvalidCoordinates() = runBlocking {
        listOf(valid.copy(capturedAt = now - 120_001), valid.copy(accuracyM = 51.0),
            valid.copy(latitude = Double.NaN), valid.copy(longitude = 181.0),
            valid.copy(capturedAt = now + 1)).forEach { invalid ->
            assertNull(resolveLocationWithFallback({ null }, { invalid }, now = { now }))
        }
    }

    @Test fun fallbackHasItsOwnBoundedTimeout() = runBlocking {
        assertNull(resolveLocationWithFallback({ null }, { delay(100); valid },
            fallbackTimeoutMs = 10, now = { now }))
    }

    @Test fun callerCancellationDoesNotStartFallback() = runBlocking {
        var fallbackCalled = false
        val job = launch {
            resolveLocationWithFallback({ awaitCancellation() }, { fallbackCalled = true; valid })
        }
        yield()
        job.cancelAndJoin()
        assertFalse(fallbackCalled)
    }
}
