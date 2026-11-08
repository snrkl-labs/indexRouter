package org.snrkl.indexrouter.data.db

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Remembers Pebble's delivery ids, so a resent delivery is not filed twice.
 *
 * Pebble resends any delivery that did not get a 2xx, every minute, up to ten
 * attempts, and owns that queue. This app never stores a pending transcript;
 * it only has to recognise a delivery it has already finished.
 *
 * Holds no content and is separate from the routing log: clearing the log
 * must not empty this, or a retry arriving just after a clear would be filed a
 * second time. Rows are kept 24 hours.
 */
class DeliveryLedger(
    private val dao: DeliveryDao,
    private val now: () -> Long = System::currentTimeMillis,
) {

    sealed interface Decision {
        /** Handle it. [previous] is the earlier attempt's record, if this is a retry. */
        data class Process(val attempt: Int, val previous: DeliveryEntity?) : Decision
        /** Finished already: answer 2xx and do nothing. */
        data object AlreadyDone : Decision
        /** Another request with this id is being handled right now. */
        data object InProgress : Decision
    }

    // Two requests with one id must not both decide "new" before either writes.
    private val lock = Mutex()

    suspend fun begin(deliveryId: String?): Decision {
        val id = deliveryId?.trim()?.takeIf { it.isNotEmpty() } ?: return Decision.Process(1, null)
        return lock.withLock {
            val existing = dao.byId(id)
            val at = now()
            when {
                existing == null -> {
                    dao.upsert(DeliveryEntity(id, firstSeenAt = at, updatedAt = at, state = DeliveryEntity.IN_PROGRESS))
                    Decision.Process(1, null)
                }
                existing.state == DeliveryEntity.DONE -> Decision.AlreadyDone
                // An attempt that never finished — the process died part way —
                // is treated as a retry rather than blocking the id for a day.
                existing.state == DeliveryEntity.IN_PROGRESS && at - existing.updatedAt < STALE_MS ->
                    Decision.InProgress
                else -> {
                    val attempt = existing.attempts + 1
                    dao.upsert(existing.copy(state = DeliveryEntity.IN_PROGRESS, attempts = attempt, updatedAt = at))
                    Decision.Process(attempt, existing)
                }
            }
        }
    }

    /**
     * Records how the attempt ended. [retry] leaves the id open for Pebble's
     * next attempt; anything else is final, and a repeat is then a no-op.
     */
    suspend fun finish(deliveryId: String?, retry: Boolean, logRowId: Long?, notified: Boolean) {
        val id = deliveryId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        lock.withLock {
            val existing = dao.byId(id) ?: return
            dao.upsert(
                existing.copy(
                    state = if (retry) DeliveryEntity.RETRY else DeliveryEntity.DONE,
                    updatedAt = now(),
                    logRowId = logRowId ?: existing.logRowId,
                    notified = existing.notified || notified,
                ),
            )
        }
    }

    suspend fun prune(): Int = dao.deleteOlderThan(now() - RETENTION_MS)

    companion object {
        const val RETENTION_MS = 24L * 60 * 60 * 1000

        /** Pebble's own limit. The last attempt is answered 2xx and recorded as final. */
        const val MAX_ATTEMPTS = 10

        /** An IN_PROGRESS row older than this was abandoned, not still running. */
        const val STALE_MS = 2L * 60 * 1000
    }
}
