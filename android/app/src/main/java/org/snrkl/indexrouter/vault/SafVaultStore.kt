package org.snrkl.indexrouter.vault

import android.net.Uri

/**
 * Vault access over a Storage Access Framework tree grant.
 *
 * Kept as the fallback for cases the filesystem path cannot serve — a vault on a
 * volume other than primary storage, or a device without Storage Scopes. On
 * GrapheneOS prefer [FileVaultStore]: a SAF grant resolves everything relative to
 * whichever folder was picked (grant `_RingRing` and `_RingRing/Audio` becomes
 * `_RingRing/_RingRing/Audio`), and the grant is revoked outright on uninstall.
 */
class SafVaultStore(
    private val repository: VaultRepository,
    private val treeUri: Uri,
) : VaultStore {

    override val isReady: Boolean get() = repository.hasGrant(treeUri)

    override fun describe(): String = repository.displayPath(treeUri) ?: treeUri.toString()

    override fun rootName(): String? = repository.grantedFolderName(treeUri)

    override fun topLevelFolderNames(): Set<String> = repository.topLevelFolderNames(treeUri)

    override fun subfolderNames(folderPath: String): Set<String> =
        repository.subfolderNames(treeUri, folderPath)

    override fun readText(path: String): String? = repository.readText(treeUri, path)

    override fun writeText(path: String, contents: String): String =
        repository.writeText(treeUri, path, contents)

    override fun writeTextUnique(folderPath: String, baseName: String, contents: String): String =
        repository.writeTextUnique(treeUri, folderPath, baseName, contents)

    override fun writeBytesUnique(
        folderPath: String,
        baseName: String,
        extension: String,
        bytes: ByteArray,
        mime: String,
    ): String = repository.writeBytesUnique(treeUri, folderPath, baseName, extension, bytes, mime)

    override fun backup(path: String) = repository.backup(treeUri, path)

    override fun delete(path: String): Boolean =
        repository.findFile(treeUri, path)?.delete() ?: false

    override fun listFiles(folderPath: String): List<String> =
        repository.listFileNames(treeUri, folderPath)

    // SAF has no cheap recursive walk and this path is the fallback, so it
    // reports what one level holds rather than pretending to be exhaustive.
    override fun listFilesRecursive(folderPath: String): List<String> = listFiles(folderPath)

    /**
     * A tree grant covers everything beneath it, so the only question is whether
     * the grant itself still stands.
     */
    override fun access(folderPath: String): FolderAccess = when {
        !isReady -> FolderAccess.NoScope(folderPath)
        repository.findFile(treeUri, folderPath) != null -> FolderAccess.Writable
        else -> FolderAccess.Creatable
    }
}
