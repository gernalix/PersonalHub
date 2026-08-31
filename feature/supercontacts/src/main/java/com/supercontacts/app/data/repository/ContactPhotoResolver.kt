package com.supercontacts.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.supercontacts.app.data.storage.SafRootContract
import java.io.File

class ContactPhotoResolver(context: Context) {
    private val appContext = context.applicationContext
    private val safRootContract = SafRootContract(appContext)

    fun resolveDocumentForRead(rootUri: Uri, reference: String): DocumentFile? {
        val uri = safRootContract.resolvePhotoUriForRead(rootUri, reference) ?: return null
        return documentFile(uri)
    }

    fun resolveDocumentForWrite(rootUri: Uri, reference: String): DocumentFile? {
        val uri = safRootContract.resolvePhotoUriForWrite(rootUri, reference) ?: return null
        return documentFile(uri)
    }

    fun resolveDocumentForDelete(rootUri: Uri, reference: String): DocumentFile? {
        val uri = safRootContract.resolvePhotoUriForDelete(rootUri, reference) ?: return null
        return documentFile(uri)
    }

    fun knownDocumentPhotoDirectories(rootUri: Uri): List<DocumentFile> {
        safRootContract.canonicalPhotoDirectoryUri(rootUri, create = false)
        return safRootContract.knownPhotoDirectoryUris(rootUri).mapNotNull(::documentFile)
    }

    fun canonicalDocumentPhotoDirectory(rootUri: Uri, create: Boolean): DocumentFile? {
        val uri = safRootContract.canonicalPhotoDirectoryUri(rootUri, create) ?: return null
        return documentFile(uri)
    }

    private fun documentFile(uri: Uri): DocumentFile? {
        return when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return null
                DocumentFile.fromFile(File(path))
            }

            else -> DocumentFile.fromSingleUri(appContext, uri)
        }
    }

    private data class PhotoDirectoryCandidate<T>(
        val order: Int,
        val name: String,
        val value: T,
    )

    companion object {
        const val PhotoDirectory = "photos"
        private val DuplicatePhotoDirectoryRegex = Regex("""photos \((\d+)\)""")

        fun relativePhotoReference(fileName: String): String = "$PhotoDirectory/$fileName"

        fun isRelativePhotoReference(reference: String): Boolean {
            if (reference != reference.trim()) return false
            if (!reference.startsWith("$PhotoDirectory/") || reference.contains("..")) return false
            val fileName = reference.substringAfter('/')
            return fileName.isNotBlank() && !fileName.contains('/')
        }

        fun fileNameFromReference(reference: String): String? {
            if (!isRelativePhotoReference(reference)) return null
            return reference.substringAfter('/')
        }

        fun knownFilePhotoDirectories(root: File): List<File> =
            root.listFiles().orEmpty()
                .mapNotNull { file ->
                    val order = photoDirectoryOrder(file.name) ?: return@mapNotNull null
                    file.takeIf { it.isDirectory }?.let { PhotoDirectoryCandidate(order, file.name, it) }
                }
                .sortedWith(compareBy<PhotoDirectoryCandidate<File>> { it.order }.thenBy { it.name })
                .map { it.value }

        fun canonicalFilePhotoDirectory(root: File, create: Boolean): File? {
            val knownDirectories = knownFilePhotoDirectories(root)
            if (knownDirectories.isNotEmpty()) {
                return consolidateFilePhotoDirectories(root)
                    ?: knownDirectories.firstOrNull { it.name == PhotoDirectory }
                    ?: knownDirectories.first()
            }
            if (!create) return null
            return File(root, PhotoDirectory).apply { mkdirs() }
        }

        fun resolveFileForRead(root: File, reference: String): File? {
            val fileName = fileNameFromReference(reference) ?: return null
            consolidateFilePhotoDirectories(root)
            return knownFilePhotoDirectories(root)
                .map { directory -> File(directory, fileName) }
                .firstOrNull { it.isFile }
        }

        fun resolveFileForWrite(root: File, reference: String): File? {
            resolveFileForRead(root, reference)?.let { return it }
            val fileName = fileNameFromReference(reference) ?: return null
            val photosDirectory = canonicalFilePhotoDirectory(root, create = true) ?: return null
            return File(photosDirectory, fileName)
        }

        fun resolveFileForDelete(root: File, reference: String): File? =
            resolveFileForRead(root, reference)

        fun consolidateFilePhotoDirectories(root: File): File? {
            val knownDirectories = knownFilePhotoDirectories(root)
            if (knownDirectories.isEmpty()) return null
            val canonical = File(root, PhotoDirectory).apply { mkdirs() }
            knownDirectories
                .filterNot { it.canonicalPath == canonical.canonicalPath }
                .forEach { duplicate ->
                    duplicate.listFiles().orEmpty()
                        .filter { it.isFile && it.name.isNotBlank() }
                        .forEach { source ->
                            val target = uniqueCanonicalTarget(canonical, source.name, duplicate.name)
                            source.renameTo(target).takeIf { it } ?: runCatching {
                                source.copyTo(target, overwrite = false)
                            }.getOrNull()
                            if (target.isFile) {
                                source.delete()
                            }
                        }
                    if (duplicate.listFiles().orEmpty().none { it.isFile }) {
                        duplicate.deleteRecursively()
                    }
                }
            return canonical.takeIf { it.isDirectory }
        }

        fun isPhotoDirectoryName(name: String): Boolean =
            photoDirectoryOrder(name) != null

        fun photoDirectoryOrder(name: String): Int? =
            when {
                name == PhotoDirectory -> 0
                else -> DuplicatePhotoDirectoryRegex.matchEntire(name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            }

        private fun uniqueCanonicalTarget(canonical: File, sourceName: String, sourceDirectory: String): File {
            val exact = File(canonical, sourceName)
            if (!exact.exists()) return exact
            val dot = sourceName.lastIndexOf('.').takeIf { it > 0 && it < sourceName.lastIndex }
            val base = dot?.let { sourceName.substring(0, it) } ?: sourceName
            val extension = dot?.let { sourceName.substring(it) } ?: ""
            val suffix = sourceDirectory
                .lowercase()
                .replace(Regex("""[^a-z0-9]+"""), "_")
                .trim('_')
                .ifBlank { "legacy" }
            var candidate = File(canonical, "${base}_from_$suffix$extension")
            var index = 2
            while (candidate.exists()) {
                candidate = File(canonical, "${base}_from_$suffix-$index$extension")
                index += 1
            }
            return candidate
        }
    }
}
