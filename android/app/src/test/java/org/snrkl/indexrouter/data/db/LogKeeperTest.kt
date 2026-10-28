package org.snrkl.indexrouter.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keeper's rules, away from a database.
 *
 * The case that matters most is the second clearing: the first version spared
 * every marker, so the stale one stayed newest, the no-stacking guard skipped
 * the new one, and the log told you it had been cleared at a time that was no
 * longer true.
 */
class LogKeeperTest {

    /** Enough of the DAO to hold rows in a list and answer the queries about them. */
    private class FakeLogDao : RoutingLogDao {
        val rows = mutableListOf<RoutingLogEntity>()
        private var nextId = 1L

        private fun newestFirst() = rows.sortedByDescending { it.receivedAt }

        override fun observeRecent(limit: Int): Flow<List<RoutingLogEntity>> =
            flowOf(newestFirst().take(limit))

        override suspend fun insert(entry: RoutingLogEntity): Long {
            val id = nextId++
            rows += entry.copy(id = id)
            return id
        }

        override suspend fun markRerouted(id: Long, action: String, targetPath: String) = Unit

        override suspend fun byId(id: Long): RoutingLogEntity? = rows.firstOrNull { it.id == id }

        override suspend fun recentTranscripts(action: String, limit: Int) =
            newestFirst().filter { it.transcript.isNotBlank() && it.action == action }.take(limit)

        override suspend fun clear() = rows.clear()

        override suspend fun clearContent(): Int = removeWhere { !it.isMarker }

        override suspend fun deleteOlderThan(cutoff: Long): Int =
            removeWhere { !it.isMarker && it.receivedAt < cutoff }

        override suspend fun newestIsMarker(): Boolean? = newestFirst().firstOrNull()?.isMarker

        override suspend fun clearMarkers(): Int = removeWhere { it.isMarker }

        override suspend fun deleteMarkersOlderThan(cutoff: Long): Int =
            removeWhere { it.isMarker && it.receivedAt < cutoff }

        private fun removeWhere(predicate: (RoutingLogEntity) -> Boolean): Int {
            val doomed = rows.filter(predicate)
            rows.removeAll(doomed)
            return doomed.size
        }

        fun markers() = rows.filter { it.isMarker }
        fun content() = rows.filter { !it.isMarker }
    }

    private fun entry(at: Long, transcript: String = "a spoken note") =
        RoutingLogEntity(receivedAt = at, transcript = transcript, outcome = "SUCCESS")

    private fun keeper(dao: FakeLogDao, hours: Int = 2) = LogKeeper(dao) { hours }

    @Test
    fun `clearing takes the content and leaves one dated marker`() = runBlocking {
        val dao = FakeLogDao()
        repeat(3) { dao.insert(entry(1_000L + it)) }

        val removed = keeper(dao).clearNow()

        assertEquals(3, removed)
        assertEquals(emptyList<RoutingLogEntity>(), dao.content())
        assertEquals(1, dao.markers().size)
        assertTrue(LogMarker.looksLikeMarker(dao.markers().single().transcript))
    }

    @Test
    fun `clearing twice restamps rather than keeping the older marker`() = runBlocking {
        // The bug: the surviving marker was the newest row, so the guard below
        // skipped the new one and the log dated this clearing to the last one.
        val dao = FakeLogDao()
        dao.insert(entry(1_000L))
        keeper(dao).clearNow()
        val first = dao.markers().single()

        dao.insert(entry(9_000L, "something said after the first clearing"))
        keeper(dao).clearNow()

        assertEquals(1, dao.markers().size)
        assertTrue(dao.markers().single().receivedAt >= first.receivedAt)
        assertEquals(emptyList<RoutingLogEntity>(), dao.content())
    }

    @Test
    fun `clearing an already-empty log adds nothing on top of the marker`() = runBlocking {
        val dao = FakeLogDao()
        dao.insert(entry(1_000L))
        keeper(dao).clearNow()

        keeper(dao).clearNow()

        assertEquals(1, dao.markers().size)
    }

    @Test
    fun `pruning drops what is past its keep-for time and marks that it did`() = runBlocking {
        val dao = FakeLogDao()
        val now = 10 * 60 * 60 * 1000L
        dao.insert(entry(now - 3 * 60 * 60 * 1000L, "said three hours ago"))
        dao.insert(entry(now - 60 * 60 * 1000L, "said an hour ago"))

        val removed = keeper(dao, hours = 2).prune(now)

        assertEquals(1, removed)
        assertEquals(listOf("said an hour ago"), dao.content().map { it.transcript })
        assertEquals(1, dao.markers().size)
    }

    @Test
    fun `pruning with nothing old enough leaves the log exactly as it was`() = runBlocking {
        // Otherwise every app open would stamp a marker onto an untouched log.
        val dao = FakeLogDao()
        val now = 10 * 60 * 60 * 1000L
        dao.insert(entry(now - 60 * 1000L))

        assertEquals(0, keeper(dao, hours = 2).prune(now))
        assertEquals(emptyList<RoutingLogEntity>(), dao.markers())
    }

    @Test
    fun `keep until cleared prunes nothing, however old the log is`() = runBlocking {
        val dao = FakeLogDao()
        dao.insert(entry(1L, "said a very long time ago"))

        assertEquals(0, keeper(dao, hours = 0).prune(now = 10_000_000_000L))
        assertEquals(1, dao.content().size)
    }

    @Test
    fun `a marker older than the window goes with the content it described`() = runBlocking {
        val dao = FakeLogDao()
        val now = 10 * 60 * 60 * 1000L
        dao.insert(
            RoutingLogEntity(
                receivedAt = now - 5 * 60 * 60 * 1000L,
                transcript = "----- cleared on 2026-09-23-12:00:00 -----",
                outcome = "CLEARED",
                isMarker = true,
            ),
        )
        dao.insert(entry(now - 4 * 60 * 60 * 1000L, "said four hours ago"))

        keeper(dao, hours = 2).prune(now)

        // One marker, dated now — not the five-hour-old one describing a
        // clearing that is itself outside the window being kept.
        val marker = dao.markers().single()
        assertTrue(marker.receivedAt > now - 60 * 60 * 1000L)
    }
}
