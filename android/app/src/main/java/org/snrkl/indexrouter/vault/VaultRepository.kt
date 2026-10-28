package org.snrkl.indexrouter.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * All vault I/O, through the Storage Access Framework.
 *
 * The vault sits in shared storage, so a single persistable tree grant covers it
 * — no all-files access, and GrapheneOS Storage Scopes stay happy. The grant is
 * revoked on uninstall, which is why the config file doubles as the backup: the
 * file survives even though the permission does not.
 */
class VaultRepository(private val context: Context) {

    class VaultException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Persists the folder grant so it survives reboots. */
    fun persistGrant(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun hasGrant(treeUri: Uri?): Boolean {
        if (treeUri == null) return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
    }

    fun displayPath(treeUri: Uri?): String? =
        treeUri?.let { DocumentsContract.getTreeDocumentId(it) }

    private fun root(treeUri: Uri): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw VaultException("Vault folder is not reachable — re-grant access.")

    /** Walks (creating as needed) the folders of a vault-relative path. */
    private fun folderFor(treeUri: Uri, folderPath: String, create: Boolean): DocumentFile? {
        var current = root(treeUri)
        folderPath.split('/').filter { it.isNotBlank() }.forEach { segment ->
            val existing = current.findFile(segment)
            current = when {
                existing != null && existing.isDirectory -> existing
                existing != null -> throw VaultException("'$segment' exists but is a file, not a folder.")
                create -> current.createDirectory(segment)
                    ?: throw VaultException("Could not create folder '$segment'.")
                else -> return null
            }
        }
        return current
    }

    /** Display name of the granted folder, e.g. "_RingRing" — used to sanity-check the grant. */
    fun grantedFolderName(treeUri: Uri): String? =
        runCatching { root(treeUri).name }.getOrNull()

    /** Names of the folders directly inside the grant. */
    fun topLevelFolderNames(treeUri: Uri): Set<String> =
        runCatching {
            root(treeUri).listFiles().filter { it.isDirectory }.mapNotNull { it.name }.toSet()
        }.getOrDefault(emptySet())

    fun subfolderNames(treeUri: Uri, folderPath: String): Set<String> {
        val folder = if (folderPath.isBlank()) root(treeUri) else folderFor(treeUri, folderPath, create = false)
        return folder?.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { it.name?.takeUnless { name -> name.startsWith(".") } }
            ?.toSet()
            .orEmpty()
    }

    fun findFile(treeUri: Uri, path: String): DocumentFile? {
        val folder = folderFor(treeUri, path.substringBeforeLast('/', ""), create = false) ?: return null
        return folder.findFile(path.substringAfterLast('/'))
    }

    fun listFileNames(treeUri: Uri, folderPath: String): List<String> =
        runCatching {
            folderFor(treeUri, folderPath, create = false)
                ?.listFiles()
                ?.filter { it.isFile }
                ?.mapNotNull { it.name }
                ?.filterNot { it.startsWith(".") }
                ?.sorted()
                .orEmpty()
        }.getOrDefault(emptyList())

    fun readText(treeUri: Uri, path: String): String? {
        val file = findFile(treeUri, path)?.takeIf { it.isFile } ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Creates or replaces a text file, returning its vault-relative path. */
    fun writeText(treeUri: Uri, path: String, contents: String, mime: String = "text/markdown"): String {
        writeBytes(treeUri, path, contents.toByteArray(Charsets.UTF_8), mime)
        return path
    }

    fun writeBytes(treeUri: Uri, path: String, bytes: ByteArray, mime: String): String {
        val folderPath = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val folder = folderFor(treeUri, folderPath, create = true)
            ?: throw VaultException("Could not open folder '$folderPath'.")

        val file = folder.findFile(name)?.takeIf { it.isFile }
            ?: folder.createFile(mime, name)
            ?: throw VaultException("Could not create '$name' in '$folderPath'.")

        // "wt" truncates; without it a shorter rewrite leaves trailing garbage.
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
            ?: throw VaultException("Could not open '$name' for writing.")
        return path
    }

    /**
     * Writes to a filename that does not exist yet, adding " 2", " 3"… on
     * collision. Two notes dictated in the same minute must not overwrite each
     * other — filenames are minute-resolution, so this is not a rare case.
     */
    fun writeTextUnique(treeUri: Uri, folderPath: String, baseName: String, contents: String): String =
        writeText(treeUri, uniquePath(treeUri, folderPath, baseName, "md"), contents)

    /** As [writeTextUnique], for the recording itself. */
    fun writeBytesUnique(
        treeUri: Uri,
        folderPath: String,
        baseName: String,
        extension: String,
        bytes: ByteArray,
        mime: String,
    ): String = writeBytes(treeUri, uniquePath(treeUri, folderPath, baseName, extension), bytes, mime)

    private fun uniquePath(treeUri: Uri, folderPath: String, baseName: String, extension: String): String {
        val folder = folderFor(treeUri, folderPath, create = true)
            ?: throw VaultException("Could not open folder '$folderPath'.")
        var candidate = "$baseName.$extension"
        var suffix = 2
        while (folder.findFile(candidate) != null) {
            candidate = "$baseName $suffix.$extension"
            suffix++
        }
        return if (folderPath.isBlank()) candidate else "$folderPath/$candidate"
    }

    /**
     * Keeps the previous copy of a file, best-effort — never blocks the write it
     * protects.
     *
     * Backups go into a dot-folder, and keep the original filename rather than
     * gaining a `.bak` suffix, for two reasons: Obsidian ignores dot-folders, so
     * the backup never shows up as a note in search, graph or Dataview; and SAF
     * appends an extension to match the MIME type, so a "…md.bak" name comes back
     * as "…md.bak.md" anyway.
     */
    fun backup(treeUri: Uri, path: String) {
        runCatching {
            val existing = readText(treeUri, path) ?: return
            val folder = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val backupFolder = if (folder.isBlank()) BACKUP_FOLDER else "$folder/$BACKUP_FOLDER"
            writeText(treeUri, "$backupFolder/$name", existing)
        }
    }

    private companion object {
        const val BACKUP_FOLDER = ".backups"
    }
}
