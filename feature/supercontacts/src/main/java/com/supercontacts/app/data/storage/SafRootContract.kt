package com.supercontacts.app.data.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

class SafRootContract(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun enforce(rootUri: Uri, ensureBackup: Boolean = false): RootContractReport {
        return when (rootUri.scheme) {
            "file" -> {
                val path = rootUri.path ?: return RootContractReport()
                enforceFileRoot(File(path))
            }

            else -> enforceContentRoot(rootUri, ensureBackup)
        }
    }

    fun canonicalPhotoDirectoryUri(rootUri: Uri, create: Boolean): Uri? {
        return when (rootUri.scheme) {
            "file" -> {
                val root = File(rootUri.path ?: return null)
                val photos = canonicalFilePhotoDirectory(root, create) ?: return null
                Uri.fromFile(photos)
            }

            else -> canonicalContentPhotoDirectory(rootUri, create)?.uri
        }
    }

    fun knownPhotoDirectoryUris(rootUri: Uri): List<Uri> {
        return when (rootUri.scheme) {
            "file" -> {
                val path = rootUri.path ?: return emptyList()
                knownFilePhotoDirectories(File(path)).map(Uri::fromFile)
            }

            else -> knownContentPhotoDirectories(rootUri).map { it.uri }
        }
    }

    fun resolvePhotoUriForRead(rootUri: Uri, reference: String): Uri? {
        val fileName = fileNameFromReference(reference) ?: return null
        return when (rootUri.scheme) {
            "file" -> {
                val root = File(rootUri.path ?: return null)
                resolveFilePhotoForRead(root, reference)?.let(Uri::fromFile)
            }

            else -> {
                findPhotoByDocumentId(rootUri, PhotoDirectory, fileName)?.uri
                    ?: knownContentPhotoDirectories(rootUri)
                    .firstNotNullOfOrNull { directory ->
                        findChild(directory.uri, fileName)?.takeIf { it.isFile }?.uri
                            ?: findPhotoByDocumentId(rootUri, directory.name, fileName)?.uri
                    }
            }
        }
    }

    fun resolvePhotoUriForWrite(rootUri: Uri, reference: String): Uri? {
        val fileName = fileNameFromReference(reference) ?: return null
        resolvePhotoUriForRead(rootUri, reference)?.let { return it }
        return when (rootUri.scheme) {
            "file" -> {
                val root = File(rootUri.path ?: return null)
                val target = resolveFilePhotoForWrite(root, reference) ?: return null
                Uri.fromFile(target)
            }

            else -> {
                val photos = canonicalContentPhotoDirectory(rootUri, create = true) ?: return null
                findChild(photos.uri, fileName)?.takeIf { it.isFile }?.uri
                    ?: createContentChild(photos.uri, "image/jpeg", fileName)?.takeIf { it.name == fileName }?.uri
            }
        }
    }

    fun resolvePhotoUriForDelete(rootUri: Uri, reference: String): Uri? =
        resolvePhotoUriForRead(rootUri, reference)

    fun resolveBackupUri(rootUri: Uri, create: Boolean): Uri? =
        when (rootUri.scheme) {
            "file" -> {
                val root = File(rootUri.path ?: return null).apply { mkdirs() }
                val backup = File(root, BackupFileName)
                when {
                    backup.exists() || create -> Uri.fromFile(backup)
                    else -> knownFileBackupDuplicates(root).maxByOrNull { it.lastModified() }?.let(Uri::fromFile)
                }
            }

            else -> resolveContentBackup(rootUri, create)?.uri
        }

    fun writeBackup(rootUri: Uri, snapshotFile: File) {
        when (rootUri.scheme) {
            "file" -> writeBackupToFileRoot(File(rootUri.path ?: error("Missing file root path")), snapshotFile)
            else -> writeBackupToContentRoot(rootUri, snapshotFile)
        }
    }

    fun rootEntryNames(rootUri: Uri): List<String> {
        return when (rootUri.scheme) {
            "file" -> {
                val path = rootUri.path ?: return emptyList()
                File(path).listFiles().orEmpty().map(File::getName).sorted()
            }

            else -> knownContentRootDocuments(rootUri).map { it.name }.distinct().sorted()
        }
    }

    private fun enforceFileRoot(root: File): RootContractReport {
        root.mkdirs()
        var movedPhotos = 0
        val canonicalPhotos = canonicalFilePhotoDirectory(root, create = true)
        if (canonicalPhotos != null) {
            knownFilePhotoDirectories(root)
                .filterNot { it.canonicalPath == canonicalPhotos.canonicalPath }
                .forEach { duplicate ->
                    movedPhotos += migrateFilePhotos(duplicate, canonicalPhotos)
                    if (duplicate.listFiles().orEmpty().isEmpty()) {
                        duplicate.deleteRecursively()
                    }
                }
        }
        cleanupFileRoot(root)
        return RootContractReport(movedPhotos = movedPhotos)
    }

    private fun enforceContentRoot(rootUri: Uri, ensureBackup: Boolean): RootContractReport {
        var movedPhotos = 0
        val canonicalPhotos = canonicalContentPhotoDirectory(rootUri, create = true)
        if (canonicalPhotos != null) {
            knownContentPhotoDirectories(rootUri)
                .filter { it.id != canonicalPhotos.id }
                .forEach { duplicate ->
                    movedPhotos += migrateContentPhotos(duplicate, canonicalPhotos)
                    if (listChildren(duplicate.uri).isEmpty()) {
                        deleteContentDocument(duplicate.uri)
                    }
                }
        }
        if (ensureBackup) {
            canonicalContentBackup(rootUri, create = true)
        }
        cleanupContentRoot(rootUri)
        return RootContractReport(movedPhotos = movedPhotos)
    }

    private fun writeBackupToFileRoot(root: File, snapshotFile: File) {
        root.mkdirs()
        enforceFileRoot(root)
        val backupFile = File(root, BackupFileName)
        FileInputStream(snapshotFile).use { input ->
            FileOutputStream(backupFile, false).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        cleanupFileRoot(root)
    }

    private fun writeBackupToContentRoot(rootUri: Uri, snapshotFile: File) {
        enforceContentRoot(rootUri, ensureBackup = true)
        val backup = canonicalContentBackup(rootUri, create = true)
            ?: throw IllegalStateException("Backup document could not be created.")
        FileInputStream(snapshotFile).use { input ->
            writeInputStream(input, backup.uri)
        }
        cleanupContentRoot(rootUri)
    }

    private fun canonicalFilePhotoDirectory(root: File, create: Boolean): File? {
        val exact = File(root, PhotoDirectory)
        if (exact.isDirectory || create) {
            exact.mkdirs()
            return exact.takeIf { it.isDirectory }
        }
        return null
    }

    private fun knownFilePhotoDirectories(root: File): List<File> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && photoDirectoryOrder(it.name) != null }
            .sortedWith(compareBy<File> { photoDirectoryOrder(it.name) ?: Int.MAX_VALUE }.thenBy { it.name })

    private fun resolveFilePhotoForRead(root: File, reference: String): File? {
        val fileName = fileNameFromReference(reference) ?: return null
        enforceFileRoot(root)
        return knownFilePhotoDirectories(root)
            .map { File(it, fileName) }
            .firstOrNull { it.isFile }
    }

    private fun resolveFilePhotoForWrite(root: File, reference: String): File? {
        resolveFilePhotoForRead(root, reference)?.let { return it }
        val fileName = fileNameFromReference(reference) ?: return null
        val photos = canonicalFilePhotoDirectory(root, create = true) ?: return null
        return File(photos, fileName)
    }

    private fun migrateFilePhotos(sourceDir: File, canonicalDir: File): Int {
        var moved = 0
        sourceDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.isNotBlank() }
            .forEach { source ->
                val target = uniqueTargetFile(canonicalDir, source.name, sourceDir.name)
                if (source.renameTo(target) || runCatching {
                        source.copyTo(target, overwrite = false)
                        true
                    }.getOrDefault(false)
                ) {
                    if (target.isFile) {
                        source.delete()
                        moved += 1
                    }
                }
            }
        return moved
    }

    private fun cleanupFileRoot(root: File) {
        root.listFiles().orEmpty().forEach { entry ->
            when {
                entry.isDirectory && photoDirectoryOrder(entry.name) != null && entry.name != PhotoDirectory &&
                    entry.listFiles().orEmpty().isEmpty() -> entry.deleteRecursively()
                entry.isFile && entry.name == BackupTempFileName -> entry.delete()
                entry.isFile && BackupTempDuplicateRegex.matchEntire(entry.name) != null -> entry.delete()
                entry.isFile && BackupDuplicateRegex.matchEntire(entry.name) != null -> entry.delete()
                entry.isFile && entry.name == HiddenTtxFolderName -> entry.delete()
            }
        }
    }

    private fun knownFileBackupDuplicates(root: File): List<File> =
        root.listFiles().orEmpty()
            .filter { it.isFile && BackupDuplicateRegex.matchEntire(it.name) != null }

    private fun uniqueTargetFile(canonicalDir: File, sourceName: String, sourceDirName: String): File {
        val exact = File(canonicalDir, sourceName)
        if (!exact.exists()) return exact
        val (base, extension) = splitName(sourceName)
        val suffix = "_from_${safeSuffix(sourceDirName)}"
        var candidate = File(canonicalDir, "$base$suffix$extension")
        var index = 2
        while (candidate.exists()) {
            candidate = File(canonicalDir, "$base$suffix-$index$extension")
            index += 1
        }
        return candidate
    }

    private fun canonicalContentPhotoDirectory(rootUri: Uri, create: Boolean): TreeDocument? {
        findRootChildByName(rootUri, PhotoDirectory)?.takeIf { it.isDirectory }?.let { return it }
        knownContentPhotoDirectories(rootUri).firstOrNull { it.name == PhotoDirectory }?.let { return it }
        primaryRootChild(rootUri, PhotoDirectory, DocumentsContract.Document.MIME_TYPE_DIR)
            ?.takeIf { create }
            ?.let { return it }
        if (!create) return null
        val created = createContentChild(treeRootDocumentUri(rootUri), DocumentsContract.Document.MIME_TYPE_DIR, PhotoDirectory)
            ?: return null
        if (created.name == PhotoDirectory && created.isDirectory) return created
        Log.w(LogTag, "SAF created non-canonical photo directory name=${created.name}; deleting empty directory.")
        deleteContentDocument(created.uri)
        return null
    }

    private fun knownContentPhotoDirectories(rootUri: Uri): List<TreeDocument> =
        knownContentRootDocuments(rootUri)
            .filter { it.isDirectory && photoDirectoryOrder(it.name) != null }
            .sortedWith(compareBy<TreeDocument> { photoDirectoryOrder(it.name) ?: Int.MAX_VALUE }.thenBy { it.name })

    private fun migrateContentPhotos(sourceDir: TreeDocument, canonicalDir: TreeDocument): Int {
        var moved = 0
        listChildren(sourceDir.uri)
            .filter { it.isFile && it.name.isNotBlank() }
            .forEach { source ->
                val targetName = uniqueTargetContentName(canonicalDir.uri, source.name, sourceDir.name)
                val target = createContentChild(canonicalDir.uri, source.mimeType ?: "image/jpeg", targetName)
                    ?: return@forEach
                if (target.name != targetName) {
                    Log.w(LogTag, "SAF created non-canonical photo file name=${target.name}; deleting empty file.")
                    deleteContentDocument(target.uri)
                    return@forEach
                }
                openInputStream(source.uri)?.use { input ->
                    writeInputStream(input, target.uri)
                } ?: return@forEach
                if (isReadableWithExpectedSize(target.uri, source.size) && deleteContentDocument(source.uri)) {
                    moved += 1
                }
            }
        return moved
    }

    private fun uniqueTargetContentName(parentUri: Uri, sourceName: String, sourceDirName: String): String {
        if (findChild(parentUri, sourceName) == null) return sourceName
        val (base, extension) = splitName(sourceName)
        val suffix = "_from_${safeSuffix(sourceDirName)}"
        var candidate = "$base$suffix$extension"
        var index = 2
        while (findChild(parentUri, candidate) != null) {
            candidate = "$base$suffix-$index$extension"
            index += 1
        }
        return candidate
    }

    private fun canonicalContentBackup(rootUri: Uri, create: Boolean): TreeDocument? {
        findRootChildByName(rootUri, BackupFileName)?.takeIf { it.isFile }?.let { return it }
        knownContentRootDocuments(rootUri)
            .firstOrNull { it.isFile && it.name == BackupFileName }
            ?.let { return it }
        if (!create) return null
        val created = createContentChild(treeRootDocumentUri(rootUri), "application/octet-stream", BackupFileName)
            ?: return null
        if (created.name == BackupFileName && created.isFile) return created
        Log.w(LogTag, "SAF created non-canonical backup file name=${created.name}; deleting empty file.")
        deleteContentDocument(created.uri)
        return null
    }

    private fun resolveContentBackup(rootUri: Uri, create: Boolean): TreeDocument? {
        canonicalContentBackup(rootUri, create = false)?.let { return it }
        if (create) return canonicalContentBackup(rootUri, create = true)
        return knownContentRootDocuments(rootUri)
            .filter { it.isFile && BackupDuplicateRegex.matchEntire(it.name) != null }
            .maxWithOrNull(compareBy<TreeDocument> { it.lastModified }.thenBy { it.name })
    }

    private fun cleanupContentRoot(rootUri: Uri) {
        knownContentRootDocuments(rootUri).forEach { entry ->
            when {
                entry.isDirectory && photoDirectoryOrder(entry.name) != null && entry.name != PhotoDirectory &&
                    listChildren(entry.uri).isEmpty() -> deleteContentDocument(entry.uri)
                entry.isDirectory && entry.name == HiddenTtxFolderName -> deleteContentDocument(entry.uri)
                entry.isFile && entry.name == BackupTempFileName -> deleteContentDocument(entry.uri)
                entry.isFile && BackupTempDuplicateRegex.matchEntire(entry.name) != null -> deleteContentDocument(entry.uri)
                entry.isFile && BackupDuplicateRegex.matchEntire(entry.name) != null -> deleteContentDocument(entry.uri)
                entry.isFile && entry.name == HiddenTtxFolderName -> deleteContentDocument(entry.uri)
            }
        }
    }

    private fun knownContentRootDocuments(rootUri: Uri): List<TreeDocument> {
        return buildList {
            findRootChildByName(rootUri, PhotoDirectory)?.let(::add)
            findRootChildByName(rootUri, BackupFileName)?.let(::add)
            findRootChildByName(rootUri, HiddenTtxFolderName)?.let(::add)
            findRootChildByName(rootUri, BackupTempFileName)?.let(::add)
            indexedRootChildren(rootUri, PhotoDuplicateProbeLimit) { "$PhotoDirectory ($it)" }.forEach(::add)
            indexedRootChildren(rootUri, BackupDuplicateProbeLimit) { "super_contacts_backup ($it).sqlite" }.forEach(::add)
            indexedRootChildren(rootUri, BackupDuplicateProbeLimit) { "super_contacts_backup.sqlite ($it).tmp" }.forEach(::add)
        }.distinctBy { it.id }
    }

    private fun indexedRootChildren(rootUri: Uri, limit: Int, nameAt: (Int) -> String): List<TreeDocument> {
        val documents = mutableListOf<TreeDocument>()
        var misses = 0
        var index = 1
        while (index <= limit && misses < ConsecutiveDuplicateMissLimit) {
            val document = findRootChildByName(rootUri, nameAt(index))
            if (document == null) {
                misses += 1
            } else {
                misses = 0
                documents += document
            }
            index += 1
        }
        return documents
    }

    private fun findRootChildByName(rootUri: Uri, name: String): TreeDocument? {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(rootUri)
        val childId = "$rootDocumentId/$name"
        val childUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, childId)
        return queryDocument(childUri, childId)?.takeIf { it.name == name }
    }

    private fun findPhotoByDocumentId(rootUri: Uri, directoryName: String, fileName: String): TreeDocument? {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(rootUri)
        val childId = "$rootDocumentId/$directoryName/$fileName"
        val childUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, childId)
        return queryDocument(childUri, childId)?.takeIf { it.name == fileName && it.isFile }
    }

    private fun primaryRootChild(rootUri: Uri, name: String, mimeType: String): TreeDocument? {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(rootUri)
        if (!rootDocumentId.startsWith("primary:")) return null
        val childId = "$rootDocumentId/$name"
        return TreeDocument(
            id = childId,
            name = name,
            mimeType = mimeType,
            lastModified = 0L,
            size = 0L,
            uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, childId),
        )
    }

    private fun treeRootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    private fun listChildren(parentUri: Uri): List<TreeDocument> {
        val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildList {
                    val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val modifiedColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idColumn) ?: continue
                        val name = cursor.getString(nameColumn) ?: continue
                        val mimeType = cursor.getString(mimeColumn)
                        add(
                            TreeDocument(
                                id = id,
                                name = name,
                                mimeType = mimeType,
                                lastModified = cursor.getLong(modifiedColumn),
                                size = cursor.getLong(sizeColumn),
                                uri = DocumentsContract.buildDocumentUriUsingTree(parentUri, id),
                            ),
                        )
                    }
                }
            } ?: emptyList()
        }.getOrElse { error ->
            Log.w(LogTag, "Could not list SAF children for $parentUri", error)
            emptyList()
        }
    }

    private fun findChild(parentUri: Uri, name: String): TreeDocument? =
        listChildren(parentUri).firstOrNull { it.name == name }

    private fun createContentChild(parentUri: Uri, mimeType: String, name: String): TreeDocument? {
        val createdUri = runCatching {
            DocumentsContract.createDocument(resolver, parentUri, mimeType, name)
        }.getOrElse { error ->
            Log.w(LogTag, "Could not create SAF child $name", error)
            null
        } ?: return null
        val createdId = DocumentsContract.getDocumentId(createdUri)
        return queryDocument(createdUri, createdId)
    }

    private fun queryDocument(uri: Uri, id: String): TreeDocument? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    ?: return@use null
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                TreeDocument(
                    id = id,
                    name = name,
                    mimeType = mimeType,
                    lastModified = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)),
                    size = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)),
                    uri = uri,
                )
            }
        }.getOrNull()
    }

    private fun deleteContentDocument(uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrElse { error ->
            Log.w(LogTag, "Could not delete SAF document $uri", error)
            false
        }

    private fun openInputStream(uri: Uri): InputStream? =
        when (uri.scheme) {
            "file" -> uri.path?.let(::FileInputStream)
            else -> resolver.openInputStream(uri)
        }

    private fun isReadableWithExpectedSize(uri: Uri, expectedSize: Long): Boolean {
        val copiedSize = queryDocument(uri, DocumentsContract.getDocumentId(uri))?.size ?: return false
        if (expectedSize > 0 && copiedSize != expectedSize) return false
        return runCatching {
            openInputStream(uri)?.use { input -> input.read() >= 0 } == true
        }.getOrDefault(false)
    }

    private fun writeInputStream(input: InputStream, uri: Uri) {
        when (uri.scheme) {
            "file" -> FileOutputStream(File(requireNotNull(uri.path)), false).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }

            else -> resolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            } ?: throw IllegalStateException("SAF document could not be opened for write.")
        }
    }

    data class TreeDocument(
        val id: String,
        val name: String,
        val mimeType: String?,
        val lastModified: Long,
        val size: Long,
        val uri: Uri,
    ) {
        val isDirectory: Boolean = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val isFile: Boolean = !isDirectory
    }

    data class RootContractReport(
        val movedPhotos: Int = 0,
    )

    companion object {
        const val PhotoDirectory = "photos"
        const val BackupFileName = "super_contacts_backup.sqlite"
        const val BackupTempFileName = "super_contacts_backup.sqlite.tmp"
        private const val HiddenTtxFolderName = ".ttxfolder"
        private const val LogTag = "SafRootContract"
        private val PhotoDuplicateRegex = Regex("""photos \((\d+)\)""")
        private val BackupDuplicateRegex = Regex("""super_contacts_backup \((\d+)\)\.sqlite""")
        private val BackupTempDuplicateRegex = Regex("""super_contacts_backup\.sqlite \((\d+)\)\.tmp""")
        private const val PhotoDuplicateProbeLimit = 50
        private const val BackupDuplicateProbeLimit = 100
        private const val ConsecutiveDuplicateMissLimit = 5

        fun relativePhotoReference(fileName: String): String = "$PhotoDirectory/$fileName"

        fun fileNameFromReference(reference: String): String? {
            if (reference != reference.trim()) return null
            if (!reference.startsWith("$PhotoDirectory/") || reference.contains("..")) return null
            val fileName = reference.substringAfter('/')
            return fileName.takeIf { it.isNotBlank() && !it.contains('/') }
        }

        fun photoDirectoryOrder(name: String): Int? =
            when {
                name == PhotoDirectory -> 0
                else -> PhotoDuplicateRegex.matchEntire(name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            }

        fun isPhotoDirectoryName(name: String): Boolean =
            photoDirectoryOrder(name) != null

        fun isAllowedRootEntry(name: String): Boolean =
            name == PhotoDirectory || name == BackupFileName

        private fun splitName(name: String): Pair<String, String> {
            val dot = name.lastIndexOf('.').takeIf { it > 0 && it < name.lastIndex - 1 } ?: return name to ""
            return name.substring(0, dot) to name.substring(dot)
        }

        private fun safeSuffix(value: String): String =
            value.lowercase(Locale.US)
                .replace(Regex("""[^a-z0-9]+"""), "_")
                .trim('_')
                .ifBlank { "legacy" }
    }
}
