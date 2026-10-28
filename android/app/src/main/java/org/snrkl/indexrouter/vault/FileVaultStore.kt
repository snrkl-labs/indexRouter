package org.snrkl.indexrouter.vault

import java.io.File
import java.io.IOException

/**
 * Vault access over plain filesystem paths.
 *
 * This is the GrapheneOS Storage Scopes path: the app holds
 * `MANAGE_EXTERNAL_STORAGE`, and the OS substitutes scoped access to exactly the
 * folders the user has granted — `_RingRing` for the app's own files, plus whichever
 * folders the routing rules target. To the app that looks like an ordinary
 * filesystem where some folders happen to be invisible, which is why
 * [access] reports on folders individually rather than assuming a working vault.
 *
 * Preferred over the SAF path because the vault root is a stated path rather than
 * an opaque tree URI: nothing resolves relative to whichever folder happened to be
 * picked, and access survives an uninstall as a scope rather than a revoked grant.
 *
 * Plain java.io, so this is covered by JVM unit tests against a real temp folder.
 */
class FileVaultStore(private val root: File) : VaultStore {

    // A real write, not a metadata question. Android's storage layer answers
    // isDirectory() and canWrite() from stat, which succeed even when open()
    // returns EPERM because the app holds no storage permission — so those
    // checks report a healthy vault while every note fails to save. The same
    // trap applies to a folder outside the app's Storage Scopes.
    override val isReady: Boolean get() = canActuallyWrite(root)

    override fun describe(): String = root.absolutePath

    override fun rootName(): String? = root.name

    override fun topLevelFolderNames(): Set<String> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { it.name }?.toSet() ?: emptySet()

    override fun subfolderNames(folderPath: String): Set<String> {
        val folder = if (folderPath.isBlank()) root else File(root, folderPath)
        return folder.listFiles()
            ?.filter { it.isDirectory }
            // Dot-folders are the app's own bookkeeping (.backups), not somewhere
            // a rule should be able to file notes.
            ?.mapNotNull { it.name.takeUnless { name -> name.startsWith(".") } }
            ?.toSet()
            .orEmpty()
    }

    override fun readText(path: String): String? {
        val file = File(root, path)
        return if (file.isFile && file.canRead()) file.readText() else null
    }

    override fun writeText(path: String, contents: String): String {
        val file = File(root, path)
        file.parentFile?.let { ensureFolder(it) }
        file.writeText(contents)
        return path
    }

    override fun writeTextUnique(folderPath: String, baseName: String, contents: String): String =
        writeText(uniquePath(folderPath, baseName, "md"), contents)

    override fun writeBytesUnique(
        folderPath: String,
        baseName: String,
        extension: String,
        bytes: ByteArray,
        mime: String,
    ): String {
        val path = uniquePath(folderPath, baseName, extension)
        val file = File(root, path)
        file.parentFile?.let { ensureFolder(it) }
        file.writeBytes(bytes)
        return path
    }

    override fun backup(path: String) {
        runCatching {
            val existing = readText(path) ?: return
            val folder = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val backupFolder = if (folder.isBlank()) BACKUP_FOLDER else "$folder/$BACKUP_FOLDER"
            writeText("$backupFolder/$name", existing)
        }
    }

    override fun delete(path: String): Boolean = File(root, path).let { it.isFile && it.delete() }

    override fun listFiles(folderPath: String): List<String> =
        File(root, folderPath).listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    override fun listFilesRecursive(folderPath: String): List<String> {
        val root = File(this.root, folderPath)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .filterNot { it.path.contains("/.") }
            .map { it.relativeTo(root).path }
            .sorted()
            .toList()
    }

    override fun access(folderPath: String): FolderAccess {
        val folder = File(root, folderPath)
        return when {
            folder.isDirectory -> if (canActuallyWrite(folder)) {
                FolderAccess.Writable
            } else {
                FolderAccess.NoScope(folderPath)
            }
            // Not there yet. Whether that is fine depends on the nearest parent
            // that does exist: with access, the app creates it on first write.
            else -> when (val parent = nearestExistingParent(folder)) {
                null -> FolderAccess.NoScope(folderPath)
                else -> if (canActuallyWrite(parent)) FolderAccess.Creatable else FolderAccess.NoScope(folderPath)
            }
        }
    }

    /** Creates and removes a probe file, because only a write proves writability. */
    private fun canActuallyWrite(folder: File): Boolean {
        if (!folder.isDirectory) return false
        val probe = File(folder, PROBE_NAME)
        return try {
            probe.outputStream().use { it.write(0) }
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { probe.delete() }
        }
    }

    private fun nearestExistingParent(file: File): File? {
        var current: File? = file.parentFile
        while (current != null && !current.isDirectory) current = current.parentFile
        return current?.takeIf { it.absolutePath.startsWith(root.absolutePath) || it == root }
    }

    private fun ensureFolder(folder: File) {
        if (folder.isDirectory) return
        if (!folder.mkdirs() && !folder.isDirectory) {
            throw IOException("Cannot create ${relative(folder)} — add it to this app's Storage Scopes.")
        }
    }

    private fun uniquePath(folderPath: String, baseName: String, extension: String): String {
        val folder = File(root, folderPath)
        ensureFolder(folder)
        var candidate = "$baseName.$extension"
        var suffix = 2
        while (File(folder, candidate).exists()) {
            candidate = "$baseName $suffix.$extension"
            suffix++
        }
        return if (folderPath.isBlank()) candidate else "$folderPath/$candidate"
    }

    private fun relative(file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart('/')

    private companion object {
        const val BACKUP_FOLDER = ".backups"
        // Dot-prefixed so Obsidian ignores it even if a crash leaves one behind.
        const val PROBE_NAME = ".indexrouter-write-probe"
    }
}
