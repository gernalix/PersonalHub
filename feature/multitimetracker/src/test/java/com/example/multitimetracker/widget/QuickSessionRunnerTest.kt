package com.example.multitimetracker.widget

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickSessionRunnerTest {
    @Test
    fun forcedSessionWriteFailureProducesNoAuditOrBroadcast() {
        var audits = 0
        var broadcasts = 0

        val result = QuickSessionRunner.run(
            context = ApplicationProvider.getApplicationContext(),
            sessionStarter = object : QuickSessionRunner.SessionStarter {
                override fun ensureRunningSessionRow(title: String, startMs: Long, tagIds: Set<Long>, nowMs: Long): Long {
                    error("forced failure")
                }
            },
            auditInsert = { _, _, _ -> audits += 1 },
            notifyChanged = { broadcasts += 1 },
        )

        assertTrue(result is QuickSessionRunner.Result.Failure)
        assertEquals(0, audits)
        assertEquals(0, broadcasts)
    }

    @Test
    fun normalPathCreatesOneSessionAuditAndBroadcast() {
        var sessionWrites = 0
        var audits = 0
        var broadcasts = 0

        val result = QuickSessionRunner.run(
            context = ApplicationProvider.getApplicationContext(),
            sessionStarter = object : QuickSessionRunner.SessionStarter {
                override fun ensureRunningSessionRow(title: String, startMs: Long, tagIds: Set<Long>, nowMs: Long): Long {
                    sessionWrites += 1
                    return 42L
                }
            },
            auditInsert = { _, _, _ -> audits += 1 },
            notifyChanged = { broadcasts += 1 },
        )

        assertEquals(QuickSessionRunner.Result.Success(42L, (result as QuickSessionRunner.Result.Success).title), result)
        assertEquals(1, sessionWrites)
        assertEquals(1, audits)
        assertEquals(1, broadcasts)
    }
}
