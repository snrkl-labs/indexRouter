package org.snrkl.indexrouter.vault

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remembers which folders are writable, so the status screen does not re-probe
 * on every render.
 *
 * Probing writes and deletes a file in each folder — the only honest way to know
 * a write will work, since Android answers `canWrite()` from metadata that says
 * yes while `open()` returns EPERM. Honest, but measured at ~453ms, and it ran
 * on the same IO dispatcher a burst of recordings was using, so the screen sat
 * there until they finished.
 *
 * Cached with a short life rather than indefinitely: a Storage Scope can be
 * revoked while the app is open, and a stale "all fine" is exactly the failure
 * the probe exists to prevent.
 */
class VaultAccessCache(private val vaultStore: () -> VaultStore?) {

    data class Snapshot(
        val requirements: List<VaultAccess.Requirement>,
        val takenAtMs: Long,
        val key: Int,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    /** The last answer, if it is recent enough and still describes these folders. */
    fun peek(coreFolders: List<Pair<String, String>>, ruleFolders: List<Pair<String, String>>): List<VaultAccess.Requirement>? {
        val current = snapshot ?: return null
        if (current.key != keyOf(coreFolders, ruleFolders)) return null
        if (SystemClock.elapsedRealtime() - current.takenAtMs > LIFETIME_MS) return null
        return current.requirements
    }

    /** Probes for real. Call off the render path. */
    suspend fun refresh(
        coreFolders: List<Pair<String, String>>,
        ruleFolders: List<Pair<String, String>>,
    ): List<VaultAccess.Requirement> = withContext(Dispatchers.IO) {
        val store = vaultStore() ?: return@withContext emptyList()
        val fresh = VaultAccess.requirementsFor(store, coreFolders, ruleFolders)
        snapshot = Snapshot(fresh, SystemClock.elapsedRealtime(), keyOf(coreFolders, ruleFolders))
        fresh
    }

    /** After anything that could change access: a new grant, a new base folder. */
    fun invalidate() {
        snapshot = null
    }

    private fun keyOf(core: List<Pair<String, String>>, rules: List<Pair<String, String>>): Int =
        (core.map { it.first } + rules.map { it.first }).hashCode()

    private companion object {
        // Long enough to cover a burst of renders, short enough that a revoked
        // scope surfaces while the app is still open.
        const val LIFETIME_MS = 30_000L
    }
}
