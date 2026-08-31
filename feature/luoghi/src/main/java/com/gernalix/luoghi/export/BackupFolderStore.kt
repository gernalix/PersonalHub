package com.gernalix.luoghi.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.gernalix.luoghi.R
import java.util.UUID

object BackupFolderStore {
    private const val PREFS = "luoghi_backup"
    private const val KEY_TREE_URI = "tree_uri"

    enum class ValidationStatus {
        READY,
        MISSING,
        PERMISSION_REVOKED,
        NOT_WRITABLE,
    }

    fun getTreeUri(context: Context): Uri? {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
            ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    fun saveTreeUri(context: Context, treeUri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, treeUri.toString())
            .apply()
    }

    fun clearTreeUri(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TREE_URI)
            .apply()
    }

    fun isConfigured(context: Context): Boolean = validateSavedTree(context) == ValidationStatus.READY

    fun validateSavedTree(context: Context): ValidationStatus {
        val treeUri = getTreeUri(context) ?: return ValidationStatus.MISSING
        val root = safeRoot(context, treeUri)
        if (root == null) {
            clearTreeUri(context)
            return ValidationStatus.PERMISSION_REVOKED
        }
        if (!hasPersistedPermission(context, treeUri, requireWrite = true)) {
            clearTreeUri(context)
            return ValidationStatus.PERMISSION_REVOKED
        }
        val canReadWrite = runCatching { root.canRead() && root.canWrite() }.getOrDefault(false)
        if (!canReadWrite || !probeWritable(context, root)) {
            clearTreeUri(context)
            return ValidationStatus.NOT_WRITABLE
        }
        return ValidationStatus.READY
    }

    fun ensureSavedTreeWritable(context: Context): Boolean {
        return validateSavedTree(context) == ValidationStatus.READY
    }

    fun getOrCreateDataDir(context: Context): DocumentFile {
        val treeUri = getTreeUri(context)
            ?: throw IllegalStateException(context.getString(R.string.backup_folder_not_configured))
        val root = safeRoot(context, treeUri)
            ?: throw IllegalStateException(context.getString(R.string.backup_folder_access_failed))
        if (!root.canRead()) {
            clearTreeUri(context)
            throw IllegalStateException(context.getString(R.string.backup_folder_missing_read))
        }
        if (!root.canWrite() || !hasPersistedPermission(context, treeUri, requireWrite = true)) {
            clearTreeUri(context)
            throw IllegalStateException(context.getString(R.string.backup_folder_missing_write))
        }
        root.findFile("Luoghi data")?.takeIf { it.isDirectory }?.let { return it }
        if (root.name == "Luoghi data" || root.findFile(LuoghiExporter.DB_FILE)?.isFile == true) return root
        return root.createDirectory("Luoghi data")
            ?: throw IllegalStateException(context.getString(R.string.backup_folder_access_failed))
    }

    fun getExistingDataDir(context: Context): DocumentFile? {
        val treeUri = getTreeUri(context) ?: return null
        val root = safeRoot(context, treeUri) ?: return null
        if (!root.canRead() || !hasPersistedPermission(context, treeUri, requireWrite = false)) return null
        return root.findFile("Luoghi data")?.takeIf { it.isDirectory } ?: root
    }

    fun describeTree(context: Context): String? {
        val treeUri = getTreeUri(context) ?: return null
        return treeUri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
            ?: treeUri.toString()
    }

    private fun safeRoot(context: Context, treeUri: Uri): DocumentFile? =
        runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()

    private fun hasPersistedPermission(context: Context, treeUri: Uri, requireWrite: Boolean): Boolean {
        val p = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == treeUri } ?: return false
        return if (requireWrite) p.isReadPermission && p.isWritePermission else p.isReadPermission
    }

    private fun probeWritable(context: Context, root: DocumentFile): Boolean {
        return runCatching {
            val probe = root.createFile(
                "application/octet-stream",
                ".luoghi_write_probe_${UUID.randomUUID()}.tmp",
            )
                ?: return false
            context.contentResolver.openOutputStream(probe.uri, "wt")?.use { stream ->
                stream.write(byteArrayOf(1))
                stream.flush()
            } ?: return false
            probe.delete()
        }.getOrDefault(false)
    }
}
