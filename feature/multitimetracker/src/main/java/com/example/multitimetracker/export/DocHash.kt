// v269
package com.example.multitimetracker.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

object DocHash {

    fun sha256Hex(context: Context, doc: DocumentFile): String {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(doc.uri).use { input ->
            requireNotNull(input) { "openInputStream returned null" }
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
