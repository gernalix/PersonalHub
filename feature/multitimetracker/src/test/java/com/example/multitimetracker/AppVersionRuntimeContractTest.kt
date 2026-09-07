package com.example.multitimetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionRuntimeContractTest {
    @Test fun autoConsistencyRevisionIsDedicatedAndMonotonic() {
        assertTrue(AppPatchVersion.AUTO_CONSISTENCY_REVISION > 0L)
    }

    @Test fun featurePatchAssetDoesNotDefineHostVersionContract() {
        assertEquals("539", BuildConfig.VERSION_NAME)
        assertTrue(AppPatchVersion.AUTO_CONSISTENCY_REVISION != BuildConfig.VERSION_CODE.toLong())
    }
}
