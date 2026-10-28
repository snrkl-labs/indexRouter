package org.snrkl.indexrouter.vault

/**
 * Which vault folders a given routing setup needs, and whether they are reachable.
 *
 * Under Storage Scopes each folder is granted individually, so "the vault works"
 * is not a single yes/no — a `task` rule needs `_Daily Notes`, a `file` rule needs
 * its own target folder, and the app's own files need `_RingRing`. Checking these
 * when a rule is saved turns a 3am silent failure into a question asked at the
 * moment the rule is written.
 */
object VaultAccess {

    /** A folder the app needs, and why it needs it. */
    data class Requirement(
        val folderPath: String,
        val reason: String,
        val access: FolderAccess,
    ) {
        val satisfied: Boolean get() = access.usable
    }

    fun requirementsFor(
        store: VaultStore,
        coreFolders: List<Pair<String, String>>,
        ruleFolders: List<Pair<String, String>>,
    ): List<Requirement> =
        (coreFolders + ruleFolders)
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first.trimEnd('/').lowercase() }
            .map { (folder, reason) -> Requirement(folder, reason, store.access(folder)) }
}
