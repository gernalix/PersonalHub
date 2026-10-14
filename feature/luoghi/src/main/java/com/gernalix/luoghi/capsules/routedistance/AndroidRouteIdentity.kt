package com.gernalix.luoghi.capsules.routedistance

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.HttpURLConnection
import java.security.MessageDigest

internal data class AndroidRouteIdentity(
    val packageName: String,
    val certificateSha1: String,
) {
    fun applyTo(connection: HttpURLConnection) {
        connection.setRequestProperty("X-Android-Package", packageName)
        connection.setRequestProperty("X-Android-Cert", certificateSha1)
    }

    companion object {
        fun fromContext(context: Context): AndroidRouteIdentity? = runCatching {
            val packageName = context.packageName
            val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                    .signatures?.map { it.toByteArray() }.orEmpty()
            }
            fromCertificates(packageName, certificates)
        }.getOrNull()

        fun fromCertificates(packageName: String, certificates: List<ByteArray>): AndroidRouteIdentity? {
            if (packageName.isBlank() || certificates.size != 1 || certificates.single().isEmpty()) return null
            val fingerprint = MessageDigest.getInstance("SHA-1").digest(certificates.single())
                .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }
            return AndroidRouteIdentity(packageName, fingerprint)
        }
    }
}
