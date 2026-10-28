package org.snrkl.indexrouter.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Keeps the routing log within its retention, and clears it on request.
 *
 * Both paths end the same way — content gone, a dated marker left behind — and
 * the marker does not say which it was. The log should record that content went
 * and when, not the user's habits.
 */
class LogKeeper(
    private val logDao: RoutingLogDao,
    /**
     * Read through a lambda rather than held as a [SettingsStore], so the
     * keeper carries no Android types and its rules are covered by plain JVM
     * tests. It is also read per call, which is what makes a retention change
     * in settings apply to the very next prune.
     */
    private val retentionHours: () -> Int,
) {

    /**
     * Drops anything past its keep-for time. Called where recordings arrive and
     * where the app is opened, rather than on a timer: those are the two moments
     * the log grows or is looked at, and a background job to delete things is
     * more machinery than this needs.
     */
    suspend fun prune(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val cutoff = LogRetention.cutoff(now, retentionHours()) ?: return@withContext 0
        val removed = logDao.deleteOlderThan(cutoff)
        if (removed > 0) {
            // The markers from inside the window that just aged out go with it,
            // so what is left is one marker dated now rather than a trail of
            // older ones describing removals that are themselves out of scope.
            logDao.deleteMarkersOlderThan(cutoff)
            markCleared()
        }
        removed
    }

    /**
     * The button. Everything goes and one dated marker is left in its place.
     *
     * The old markers go too. Clearing removes *all* the content, so any marker
     * still sitting there has nothing beneath it to describe, and leaving one
     * behind is worse than untidy: it is the newest row, which made the
     * no-stacking guard below skip the new marker and left the log claiming it
     * was cleared at whatever time the last clearing happened.
     */
    suspend fun clearNow(): Int = withContext(Dispatchers.IO) {
        val removed = logDao.clearContent()
        if (removed > 0) logDao.clearMarkers()
        markCleared()
        removed
    }

    /**
     * Adds the marker, unless the newest row already is one.
     *
     * Without that check a pruning run every time the app opens would stack
     * markers on an otherwise empty log until the log was nothing but markers.
     */
    private suspend fun markCleared(at: LocalDateTime = LocalDateTime.now()) {
        if (logDao.newestIsMarker() == true) return
        logDao.insert(
            RoutingLogEntity(
                receivedAt = System.currentTimeMillis(),
                transcript = LogMarker.text(at),
                outcome = "CLEARED",
                isMarker = true,
            ),
        )
    }
}
