// v435
package com.example.multitimetracker.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.example.multitimetracker.R

/**
 * v395 – SAF folder organizer (anti-chaos).
 *
 * The user picks a SAF root folder once. Inside it, the app uses ONE stable subfolder
 * and a fixed structure, so we don't create endless timestamp subfolders.
 *
 * Layout:
 *   <picked root>/                       (v434 canonical; no wrapper)
 *     vaults/<vaultName>/
 *       multitimer.db
 *       multitimer.db.bak
 *     exports/                           (user-facing ZIP exports)
 *     logs/                              (build + ops logs)
 *     tmp/                               (temporary staging)
 *     index.json                         (human/UX-friendly registry)
 */
object VaultFolders {

    private const val VAULTS_DIR = "vaults"
    private const val EXPORTS_DIR = "exports"
    private const val LOGS_DIR = "logs"
    private const val TMP_DIR = "tmp"
    private const val INDEX_FILE = "index.json"

    data class Root(
        val appRoot: DocumentFile,
        val vaults: DocumentFile,
        val exports: DocumentFile,
        val logs: DocumentFile,
        val tmp: DocumentFile,
        val index: DocumentFile?
    )

    fun ensureRoot(context: Context): Root {
        val appRoot = BackupFolderStore.getOrCreateDataDir(context)

        fun dir(name: String): DocumentFile {
            val existing = appRoot.findFile(name)
            if (existing != null && existing.isDirectory) return existing
            return appRoot.createDirectory(name)
                ?: throw IllegalStateException(context.getString(R.string.export_tmp_folder_create_failed))
        }

        val vaults = dir(VAULTS_DIR)
        val exports = dir(EXPORTS_DIR)
        val logs = dir(LOGS_DIR)
        val tmp = dir(TMP_DIR)

        val index = appRoot.findFile(INDEX_FILE)?.takeIf { it.isFile }
            ?: appRoot.createFile("application/json", INDEX_FILE)

        return Root(
            appRoot = appRoot,
            vaults = vaults,
            exports = exports,
            logs = logs,
            tmp = tmp,
            index = index
        )
    }

    /** v434: Multi-DB vault directory: vaults/<name> */
    fun ensureNamedVaultDir(context: Context, vaultName: String): DocumentFile {
        val root = ensureRoot(context)
        val vaultsDir = root.vaults

        val existing = vaultsDir.findFile(vaultName)
        if (existing != null && existing.isDirectory) return existing

        return vaultsDir.createDirectory(vaultName)
            ?: throw IllegalStateException(context.getString(R.string.backup_folder_access_failed))
    }

    fun ensureAutoexportsDir(context: Context, vaultName: String): DocumentFile {
        val vaultDir = ensureNamedVaultDir(context, vaultName)
        val existing = vaultDir.findFile("autoexports")
        if (existing != null && existing.isDirectory) return existing
        return vaultDir.createDirectory("autoexports")
            ?: throw IllegalStateException(context.getString(R.string.backup_folder_access_failed))
    }
}
