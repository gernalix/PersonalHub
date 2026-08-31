// v395
package com.example.multitimetracker.export

import android.content.Context
import com.example.multitimetracker.R
import androidx.documentfile.provider.DocumentFile
import com.example.multitimetracker.export.VaultFolders
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipBackupExporter {

    data class Result(
        val zipFileName: String
    )

    /**
     * Creates a single ZIP inside the given [parentDir] (SAF), containing the standard CSV/JSON backup files.
     *
     * Implementation strategy:
     * 1) Export to a temporary SAF subfolder.
     * 2) Zip all files from that folder into a single .zip file in [parentDir].
     * 3) Cleanup the temporary folder.
     */
    fun exportCsvJsonZip(
        context: Context,
        parentDir: DocumentFile,
        fileName: String,
        exportBlock: (DocumentFile) -> Unit
    ): Result {
        val tmpDirName = "tmp_export"
        val tmpParent = VaultFolders.ensureRoot(context).tmp
        // Delete any leftover temp folder (inside tmp/).
        tmpParent.findFile(tmpDirName)?.delete()

        val tmpDir = tmpParent.createDirectory(tmpDirName)
            ?: throw IllegalStateException(context.getString(R.string.export_tmp_folder_create_failed))

        try {
            exportBlock(tmpDir)

            val zipDoc = parentDir.createFile("application/zip", fileName)
                ?: throw IllegalStateException(context.getString(R.string.export_zip_create_failed_fmt, fileName))

            context.contentResolver.openOutputStream(zipDoc.uri, "w")?.use { out ->
                ZipOutputStream(out).use { zos ->
                    val files = tmpDir.listFiles().filter { it.isFile }.sortedBy { it.name ?: "" }
                    if (files.isEmpty()) {
                        throw IllegalStateException(context.getString(R.string.export_zip_empty_tmp_folder))
                    }
                    for (f in files) {
                        val entryName = f.name ?: continue
                        val entry = ZipEntry(entryName)
                        zos.putNextEntry(entry)
                        context.contentResolver.openInputStream(f.uri)?.use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                    zos.flush()
                }
            } ?: throw IllegalStateException(context.getString(R.string.export_zip_open_failed_fmt, fileName))

            return Result(zipFileName = fileName)
        } finally {
            // Cleanup temp folder and its contents (best effort).
            runCatching {
                tmpDir.listFiles().forEach { it.delete() }
                tmpDir.delete()
            }
        }
    }
}
