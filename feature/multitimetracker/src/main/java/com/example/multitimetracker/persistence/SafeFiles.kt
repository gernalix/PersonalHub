package com.example.multitimetracker.persistence

import android.util.AtomicFile
import java.io.File
import java.io.IOException

/**
 * File helpers designed for file-based persistence (CSV/JSON) to avoid partial writes and to
 * offer a best-effort recovery path.
 *
 * Notes:
 * - For regular [File] paths we use Android's [AtomicFile], which writes to a *.new temp and
 *   keeps a *.bak backup automatically.
 * - We keep filenames stable (no versioned filenames) for easy third-party sync.
 */
object SafeFiles {

    /**
     * Atomically writes [bytes] to [target]. The parent dir must exist.
     */
    @Throws(IOException::class)
    fun writeAtomic(target: File, bytes: ByteArray) {
        val af = AtomicFile(target)
        val fos = af.startWrite()
        try {
            fos.write(bytes)
            fos.flush()
            af.finishWrite(fos)
        } catch (t: Throwable) {
            //noinspection KotlinConstantConditions
            try {
                af.failWrite(fos)
            } catch (_: Throwable) {
                // ignore
            }
            throw t
        }
    }

    /**
     * Atomically writes [text] to [target] as UTF-8.
     */
    @Throws(IOException::class)
    fun writeAtomicUtf8(target: File, text: String) {
        writeAtomic(target, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Best-effort read. Attempts the main file first; if it fails, tries the AtomicFile backup.
     */
    fun readBestEffortUtf8(target: File): String? {
        val af = AtomicFile(target)
        return runCatching {
            if (!target.exists()) return@runCatching null
            af.readFully().toString(Charsets.UTF_8)
        }.getOrElse {
            // If the main read failed, try the explicit .bak as a last resort.
            val bak = File(target.parentFile, target.name + ".bak")
            runCatching {
                if (!bak.exists()) null else bak.readText(Charsets.UTF_8)
            }.getOrNull()
        }
    }
}
