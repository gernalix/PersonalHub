package com.supercontacts.app

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallOverlayRequestGateTest {
    @Test
    fun dismissInvalidatesPendingLookup() {
        val gate = CallOverlayRequestGate()
        val first = gate.begin()
        gate.invalidate()
        assertFalse(gate.isCurrent(first))
    }

    @Test
    fun newerCallInvalidatesOlderLookup() {
        val gate = CallOverlayRequestGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun android16And17SideloadsRequireRestrictedSettingsPrimer() {
        assertTrue(
            CallOverlayPermission.requiresRestrictedSettingsPrimer(
                sdkInt = 36,
                packageSource = PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE,
            ),
        )
        assertTrue(
            CallOverlayPermission.requiresRestrictedSettingsPrimer(
                sdkInt = 37,
                packageSource = PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE,
            ),
        )
    }

    @Test
    fun storeAndPreAndroid16InstallsDoNotRequireRestrictedSettingsPrimer() {
        assertFalse(
            CallOverlayPermission.requiresRestrictedSettingsPrimer(
                sdkInt = 37,
                packageSource = PackageInstaller.PACKAGE_SOURCE_STORE,
            ),
        )
        assertFalse(
            CallOverlayPermission.requiresRestrictedSettingsPrimer(
                sdkInt = 35,
                packageSource = PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE,
            ),
        )
    }
}
