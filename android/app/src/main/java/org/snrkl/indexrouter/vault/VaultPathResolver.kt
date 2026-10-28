package org.snrkl.indexrouter.vault

/**
 * Turns the folder picked in the system chooser into a plain filesystem path.
 *
 * The picker is kept purely as a way to point at a folder visually — the path it
 * yields is what gets stored, so nothing afterwards resolves relative to an
 * opaque tree URI. That is what makes granting `_RingRing` instead of the vault
 * root a visible mistake rather than a silent `_RingRing/_RingRing/` nesting.
 *
 * Only primary storage can be mapped this way; anything else (SD card, USB, a
 * provider that is not the device's own files) has no stable path and must stay
 * on the SAF fallback.
 *
 * No Android types: covered by JVM unit tests.
 */
object VaultPathResolver {

    /**
     * @param treeDocumentId e.g. "primary:Documents/snrkl_vault".
     * @param primaryStorageRoot e.g. "/storage/emulated/0".
     * @return absolute path, or null when the volume is not primary storage.
     */
    fun toFilesystemPath(treeDocumentId: String?, primaryStorageRoot: String): String? {
        val id = treeDocumentId?.trim().orEmpty()
        if (id.isBlank()) return null

        val parts = id.split(':', limit = 2)
        val volume = parts.getOrNull(0).orEmpty()
        if (!volume.equals(PRIMARY_VOLUME, ignoreCase = true)) return null

        val relative = parts.getOrNull(1).orEmpty().trim('/')
        val root = primaryStorageRoot.trimEnd('/')
        return if (relative.isBlank()) root else "$root/$relative"
    }

    /**
     * Expresses an absolute path as vault-relative, for storing in a rule.
     *
     * Returns null when the folder is outside the vault — a rule pointing there
     * could never be written to, and silently accepting it would produce exactly
     * the class of bug this replaced.
     */
    fun toVaultRelative(absolutePath: String, vaultRootPath: String): String? {
        val path = absolutePath.trimEnd('/')
        val root = vaultRootPath.trimEnd('/')
        if (path == root) return ""
        val prefix = "$root/"
        return if (path.startsWith(prefix)) path.removePrefix(prefix) else null
    }

    private const val PRIMARY_VOLUME = "primary"
}
