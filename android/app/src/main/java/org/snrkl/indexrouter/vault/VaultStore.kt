package org.snrkl.indexrouter.vault

/**
 * Vault I/O, independent of how access was granted.
 *
 * Two implementations: [FileVaultStore] uses plain filesystem paths, which is what
 * GrapheneOS Storage Scopes confines; [SafVaultStore] uses a Storage Access
 * Framework tree grant. Paths are always vault-relative either way, so nothing
 * above this interface knows or cares which is in use.
 */
interface VaultStore {

    /** True when the vault is reachable right now. */
    val isReady: Boolean

    /** Human-readable location, for the status screen. */
    fun describe(): String

    fun readText(path: String): String?

    fun writeText(path: String, contents: String): String

    /** Writes to a name that does not exist yet, adding " 2", " 3"… on collision. */
    fun writeTextUnique(folderPath: String, baseName: String, contents: String): String

    fun writeBytesUnique(
        folderPath: String,
        baseName: String,
        extension: String,
        bytes: ByteArray,
        mime: String,
    ): String

    /** Keeps the previous copy of a file. Best-effort; never blocks the write it protects. */
    fun backup(path: String)

    /** Removes a file. Used when re-routing moves an item to another folder. */
    fun delete(path: String): Boolean

    /** Files directly inside a folder, newest name last. Used by MoC generation. */
    fun listFiles(folderPath: String): List<String>

    /**
     * Every file beneath a folder, as paths relative to it. Needed because the
     * inbox and audio folders are filed into dated subfolders, where a
     * single-level listing reports them empty.
     */
    fun listFilesRecursive(folderPath: String): List<String>

    /** Whether a folder can actually be written to — the Storage Scopes question. */
    fun access(folderPath: String): FolderAccess

    fun topLevelFolderNames(): Set<String>

    /**
     * Folder names directly inside [folderPath], for offering the folders a
     * rule could target.
     *
     * Names, not paths: a rule's folder is a single segment under the base
     * folder, and handing the UI paths is how the old vault-root semantics
     * leaked back in once already.
     */
    fun subfolderNames(folderPath: String): Set<String>

    fun rootName(): String?
}

/**
 * Why a folder is or is not usable.
 *
 * [NoScope] is the one that matters on GrapheneOS: the folder exists in the vault
 * but has not been added to this app's Storage Scopes, so the app cannot see it.
 * That must be reported by name — "could not write the note" without saying which
 * folder is unfixable.
 */
sealed interface FolderAccess {
    data object Writable : FolderAccess
    /** Not yet created, but the parent is writable, so the app can create it. */
    data object Creatable : FolderAccess
    data class NoScope(val folderPath: String) : FolderAccess
    data class Error(val reason: String) : FolderAccess

    val usable: Boolean get() = this is Writable || this is Creatable
}
