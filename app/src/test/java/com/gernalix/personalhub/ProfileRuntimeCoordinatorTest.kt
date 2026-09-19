package com.gernalix.personalhub

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRuntimeCoordinatorTest {
    @Test
    fun restoreRunsAfterSuccessfulNoOpSwitch() = runBlocking {
        var retired = 0
        var restored = 0

        val changed = ProfileRuntimeCoordinator.retireSwitchRestore(
            retire = { retired += 1 },
            switch = { false },
            restore = { restored += 1 },
        )

        assertFalse(changed)
        assertEquals(1, retired)
        assertEquals(1, restored)
    }

    @Test
    fun restoreRunsAfterFailedRetireAndPrimaryFailureIsPreserved() = runBlocking {
        val retireFailure = IllegalStateException("retire failed")
        var restored = 0
        var thrown: Throwable? = null

        try {
            ProfileRuntimeCoordinator.retireSwitchRestore(
                retire = { throw retireFailure },
                switch = { true },
                restore = { restored += 1 },
            )
        } catch (failure: Throwable) {
            thrown = failure
        }

        assertSame(retireFailure, thrown)
        assertEquals(1, restored)
    }

    @Test
    fun restoreRunsAfterFailedSwitchAndPrimaryFailureIsPreserved() = runBlocking {
        val switchFailure = IllegalStateException("switch failed")
        var restored = 0
        var thrown: Throwable? = null

        try {
            ProfileRuntimeCoordinator.retireSwitchRestore(
                retire = {},
                switch = { throw switchFailure },
                restore = { restored += 1 },
            )
        } catch (failure: Throwable) {
            thrown = failure
        }

        assertSame(switchFailure, thrown)
        assertEquals(1, restored)
    }

    @Test
    fun restoreFailureIsSuppressedWhenSwitchAlreadyFailed() = runBlocking {
        val switchFailure = IllegalStateException("switch failed")
        val restoreFailure = IllegalStateException("restore failed")
        var thrown: Throwable? = null

        try {
            ProfileRuntimeCoordinator.retireSwitchRestore(
                retire = {},
                switch = { throw switchFailure },
                restore = { throw restoreFailure },
            )
        } catch (failure: Throwable) {
            thrown = failure
        }

        assertSame(switchFailure, thrown)
        assertTrue(thrown?.suppressed?.contains(restoreFailure) == true)
    }
}
