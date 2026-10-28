package org.snrkl.indexrouter.vault

/**
 * Sanity-checks which folder was granted.
 *
 * Every configured path is vault-relative — `_Daily Notes/…`, `Blog Ideas/…`,
 * `_RingRing/Audio` — so the grant has to be the **vault root**. Granting a
 * subfolder fails in two ways at once, and both are quiet: paths that repeat the
 * subfolder's name nest inside themselves (`_RingRing/_RingRing/Inbox`), and
 * paths pointing anywhere else in the vault become unreachable, because a SAF
 * tree grant cannot see above itself.
 *
 * Neither shows up as an error at write time — the folders are simply created in
 * the wrong place — so it is checked up front instead.
 *
 * No Android types: covered by JVM unit tests.
 */
object GrantCheck {

    sealed interface Result {
        /** The grant looks like the vault root. */
        data object Ok : Result

        /**
         * The granted folder's own name is the first segment of configured paths,
         * so writes would nest inside it twice.
         */
        data class NestedInsideItself(val folderName: String) : Result

    }

    /**
     * @param grantedFolderName display name of the granted tree, e.g. "_RingRing".
     * @param configuredPaths every vault-relative path the app writes to.
     *
     * Existence is deliberately not checked. Since every path now nests under a
     * base folder the app creates on first write, an empty vault is a perfectly
     * normal first run — demanding the folder already exist would refuse every
     * clean install.
     */
    fun diagnose(
        grantedFolderName: String?,
        configuredPaths: List<String>,
    ): Result {
        val name = grantedFolderName?.trim().orEmpty()
        val firstSegments = configuredPaths
            .mapNotNull { path -> path.split('/').firstOrNull { it.isNotBlank() } }
            .distinct()

        return if (name.isNotBlank() && firstSegments.any { it.equals(name, ignoreCase = true) }) {
            Result.NestedInsideItself(name)
        } else {
            Result.Ok
        }
    }

    /** Removes a duplicated leading segment: "_RingRing/Audio" -> "Audio". */
    fun stripPrefix(path: String, prefix: String): String {
        val segments = path.split('/').filter { it.isNotBlank() }
        return if (segments.firstOrNull()?.equals(prefix, ignoreCase = true) == true) {
            segments.drop(1).joinToString("/")
        } else {
            path
        }
    }
}
