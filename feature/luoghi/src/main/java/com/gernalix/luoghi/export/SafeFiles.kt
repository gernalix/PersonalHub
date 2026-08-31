package com.gernalix.luoghi.export

import android.content.Context
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

object SafeFiles {
    @Throws(IOException::class)
    fun writeAtomic(target: File, bytes: ByteArray) {
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            runCatching { atomic.failWrite(stream) }
            throw t
        }
    }

    @Throws(IOException::class)
    fun writeAtomicUtf8(target: File, text: String) {
        writeAtomic(target, text.toByteArray(Charsets.UTF_8))
    }

    fun copyFileToDocument(context: Context, source: File, target: DocumentFile) {
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IOException("Cannot open ${target.name} for write")
    }

    fun copyDocumentToFile(context: Context, source: DocumentFile, target: File) {
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Cannot open ${source.name} for read")
    }

    fun replaceDocument(dir: DocumentFile, displayName: String, mime: String = "application/octet-stream"): DocumentFile {
        dir.findFile(displayName)?.delete()
        return dir.createFile(mime, displayName)
            ?: throw IOException("Cannot create SAF file $displayName")
    }
}
