// v434
package com.example.multitimetracker.export

import android.content.Context
import android.net.Uri
import com.example.multitimetracker.R
import com.example.multitimetracker.BuildConfig
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Persists the user-selected SAF tree URI and exposes a stable data root.
 *
 * v434 canonical layout: use the picked root directly (no wrapper folder).
 * Legacy (<=v433): <picked root>/MultiTimer data/
 *
 * This folder survives uninstall/reinstall (as long as the user keeps the files),
 * and import/export can run without asking the user to pick files every time.
 */
object BackupFolderStore {

    private const val PREFS = "multitimetracker_backup"
    private const val KEY_TREE_URI = "tree_uri"
    private const val DATA_DIR_NAME = "MultiTimer data"

    private fun safeRoot(context: Context, treeUri: Uri): DocumentFile? {
        return when {
            isLocalCloneBenchmarkUri(treeUri) -> treeUri.path
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.also { it.mkdirs() }
                ?.let(DocumentFile::fromFile)
            else -> runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        }
    }

    private fun safeCanRead(root: DocumentFile): Boolean =
        runCatching { root.canRead() }.getOrDefault(false)

    private fun safeCanWrite(root: DocumentFile): Boolean =
        runCatching { root.canWrite() }.getOrDefault(false)

    private fun safeIsDirectory(root: DocumentFile): Boolean =
        runCatching { root.isDirectory }.getOrDefault(false)

    data class DataDirDescriptor(
        val displayName: String,
        val entryNames: List<String>,
    )


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

    private fun hasPersistedPermission(context: Context, treeUri: Uri, requireWrite: Boolean): Boolean {
        if (isLocalCloneBenchmarkUri(treeUri)) {
            val path = treeUri.path?.takeIf { it.isNotBlank() } ?: return false
            val root = File(path).also { it.mkdirs() }
            return if (requireWrite) root.canWrite() else root.canRead()
        }
        val perms = context.contentResolver.persistedUriPermissions
        val p = perms.firstOrNull { it.uri == treeUri } ?: return false
        return if (requireWrite) p.isWritePermission else p.isReadPermission
    }

    /**
     * Returns true if the saved tree is accessible and (read/write) permissions are still valid.
     * If invalid, this will also clear the saved URI to avoid repeated failures.
     */
    fun ensureSavedTreeReadable(context: Context): Boolean {
        val treeUri = getTreeUri(context) ?: return false
        val root = safeRoot(context, treeUri)
        val ok = root != null && safeIsDirectory(root) && safeCanRead(root) && hasPersistedPermission(context, treeUri, requireWrite = false)
        if (!ok) clearTreeUri(context)
        return ok
    }

    /**
     * Returns true if the saved tree is accessible and writable. Clears the saved URI if not.
     */
    fun ensureSavedTreeWritable(context: Context): Boolean {
        val treeUri = getTreeUri(context) ?: return false
        val root = safeRoot(context, treeUri)
        val ok = root != null && safeIsDirectory(root) && safeCanWrite(root) && hasPersistedPermission(context, treeUri, requireWrite = true)
        if (!ok) clearTreeUri(context)
        return ok
    }

    /**
     * Returns the persistent data directory (creating it if needed).
     */
    fun getOrCreateDataDir(context: Context): DocumentFile {
        val treeUri = getTreeUri(context)
            ?: throw IllegalStateException(context.getString(R.string.backup_folder_not_configured))

        val root = safeRoot(context, treeUri)
            ?: throw IllegalStateException(context.getString(R.string.backup_folder_access_failed))

        if (!safeIsDirectory(root) || !safeCanRead(root)) {
            clearTreeUri(context)
            throw IllegalStateException(context.getString(R.string.backup_folder_missing_read))
        }

        if (!safeCanWrite(root) || !hasPersistedPermission(context, treeUri, requireWrite = true)) {
            clearTreeUri(context)
            throw IllegalStateException(context.getString(R.string.backup_folder_missing_write))
        }

        // v434 canonical: use the picked root directly IF it already contains the canonical structure.
        // Canonical markers: "vaults/" (Multi-DB) or "exports/" etc.
        val hasCanonical =
            (root.findFile("vaults")?.isDirectory == true) ||
            (root.findFile("exports")?.isDirectory == true) ||
            (root.findFile("logs")?.isDirectory == true) ||
            (root.findFile("tmp")?.isDirectory == true)

        if (hasCanonical) return root

        // Legacy support: if the wrapper folder already exists, keep using it.
        val legacy = root.findFile(DATA_DIR_NAME)
        if (legacy != null && legacy.isDirectory) return legacy

        // Otherwise: do NOT create a wrapper folder anymore. The app will create canonical subfolders in root.
        return root
    }

    fun describeDataDir(context: Context): DataDirDescriptor {
        val dir = getOrCreateDataDir(context)
        val label = dir.name?.takeIf { it.isNotBlank() }
            ?: getTreeUri(context)?.lastPathSegment?.substringAfterLast(':')
            ?: context.getString(R.string.first_run_selected_folder_unknown)
        val entryNames = runCatching {
            dir.listFiles()
                .mapNotNull { it.name }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        return DataDirDescriptor(displayName = label, entryNames = entryNames)
    }

    private fun isLocalCloneBenchmarkUri(treeUri: Uri): Boolean {
        return BuildConfig.APPLICATION_ID.endsWith(".devicetest") && treeUri.scheme == "file"
    }
}
