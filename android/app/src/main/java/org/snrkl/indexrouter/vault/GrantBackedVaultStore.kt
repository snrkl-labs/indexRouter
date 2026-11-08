package org.snrkl.indexrouter.vault

/**
 * A filesystem vault that falls back to the folder grant for files it may not
 * open by path.
 *
 * Without all-files access, Android lets an app open by path only the files it
 * created. Everything this app files is its own, so paths serve almost all of
 * the work. A file another app wrote is the exception: a config file edited on
 * another device and brought back by sync, or a map Obsidian rewrote. The
 * picker grant the user gave when choosing the vault covers every file in it,
 * so those go through the grant instead.
 *
 * Paths stay primary because they keep what the grant cannot: a vault that is
 * not mounted is never recreated, folder access is proven by writing, and the
 * recursive walk is real. The grant is only asked when a path is refused.
 */
class GrantBackedVaultStore(
    private val files: FileVaultStore,
    private val grant: VaultStore,
) : VaultStore by files {

    /**
     * Whether the grant may stand in for a path. Only for a vault that is
     * there: a grant outlives an unmounted or moved folder, and must not be
     * how an absent vault gets written to.
     */
    private val grantUsable: Boolean get() = files.rootPresent && grant.isReady

    override val isReady: Boolean get() = files.isReady || grantUsable

    // A folder another app made is not writable by path, but is through the grant.
    override fun access(folderPath: String): FolderAccess {
        val byPath = files.access(folderPath)
        if (byPath.usable || !grantUsable) return byPath
        return grant.access(folderPath)
    }

    override fun writeTextUnique(folderPath: String, baseName: String, contents: String): String = try {
        files.writeTextUnique(folderPath, baseName, contents)
    } catch (e: Exception) {
        if (!grantUsable) throw e
        grant.writeTextUnique(folderPath, baseName, contents)
    }

    override fun writeBytesUnique(
        folderPath: String,
        baseName: String,
        extension: String,
        bytes: ByteArray,
        mime: String,
    ): String = try {
        files.writeBytesUnique(folderPath, baseName, extension, bytes, mime)
    } catch (e: Exception) {
        if (!grantUsable) throw e
        grant.writeBytesUnique(folderPath, baseName, extension, bytes, mime)
    }

    override fun readText(path: String): String? = try {
        files.readText(path)
    } catch (e: Exception) {
        if (!grantUsable) throw e
        // The path said the file is there. A grant that cannot find it is not
        // evidence that it is gone, and "gone" is what invites a fresh write.
        grant.readText(path) ?: throw e
    }

    override fun writeText(path: String, contents: String): String = try {
        files.writeText(path, contents)
    } catch (e: Exception) {
        if (!grantUsable) throw e
        grant.writeText(path, contents)
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

    override fun delete(path: String): Boolean =
        files.delete(path) || (grantUsable && grant.delete(path))

    // A path listing leaves out other apps' files; the grant's does not.
    override fun listFiles(folderPath: String): List<String> {
        val own = files.listFiles(folderPath)
        if (!grantUsable) return own
        return (own + runCatching { grant.listFiles(folderPath) }.getOrDefault(emptyList())).distinct().sorted()
    }

    private companion object {
        const val BACKUP_FOLDER = ".backups"
    }
}
