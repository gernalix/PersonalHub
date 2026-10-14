package com.gernalix.luoghi.capsules.routedistance

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidRouteIdentityTest {
    @Test fun usesHostPackageAndUppercaseSha1WithoutDelimiters() {
        val identity = AndroidRouteIdentity.fromCertificates(
            "com.gernalix.personalhub.qa", listOf("abc".toByteArray()),
        )!!
        assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", identity.certificateSha1)
        val connection = object : HttpURLConnection(URL("https://example.invalid")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
        }
        identity.applyTo(connection)
        assertEquals("com.gernalix.personalhub.qa", connection.getRequestProperty("X-Android-Package"))
        assertEquals(identity.certificateSha1, connection.getRequestProperty("X-Android-Cert"))
    }

    @Test fun rejectsMissingOrAmbiguousSigningIdentity() {
        assertNull(AndroidRouteIdentity.fromCertificates("", listOf(byteArrayOf(1))))
        assertNull(AndroidRouteIdentity.fromCertificates("com.example", emptyList()))
        assertNull(AndroidRouteIdentity.fromCertificates("com.example", listOf(byteArrayOf())))
        assertNull(AndroidRouteIdentity.fromCertificates("com.example", listOf(byteArrayOf(1), byteArrayOf(2))))
    }
}
