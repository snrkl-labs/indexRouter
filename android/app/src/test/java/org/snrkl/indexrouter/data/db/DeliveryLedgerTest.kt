package org.snrkl.indexrouter.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryLedgerTest {

    private class FakeDao : DeliveryDao {
        val rows = mutableMapOf<String, DeliveryEntity>()
        override suspend fun byId(id: String) = rows[id]
        override suspend fun upsert(delivery: DeliveryEntity) { rows[delivery.deliveryId] = delivery }
        override suspend fun deleteOlderThan(cutoff: Long): Int {
            val old = rows.values.filter { it.firstSeenAt < cutoff }.map { it.deliveryId }
            old.forEach { rows.remove(it) }
            return old.size
        }
        override suspend fun count() = rows.size
    }

    private var now = 1_000_000L
    private val dao = FakeDao()
    private val ledger = DeliveryLedger(dao) { now }

    @Test
    fun `a finished delivery is not processed again`() = runBlocking {
        assertTrue(ledger.begin("d1") is DeliveryLedger.Decision.Process)
        ledger.finish("d1", retry = false, logRowId = 7, notified = false)
        assertEquals(DeliveryLedger.Decision.AlreadyDone, ledger.begin("d1"))
    }

    @Test
    fun `a retried delivery is processed again and knows its row and notification`() = runBlocking {
        ledger.begin("d2")
        ledger.finish("d2", retry = true, logRowId = 9, notified = true)
        val again = ledger.begin("d2") as DeliveryLedger.Decision.Process
        assertEquals(2, again.attempt)
        assertEquals(9L, again.previous?.logRowId)
        assertTrue(again.previous!!.notified)
    }

    @Test
    fun `a concurrent repeat is told to come back`() = runBlocking {
        ledger.begin("d3")
        assertEquals(DeliveryLedger.Decision.InProgress, ledger.begin("d3"))
        // Abandoned attempts do not block the id forever.
        now += DeliveryLedger.STALE_MS + 1
        assertTrue(ledger.begin("d3") is DeliveryLedger.Decision.Process)
    }

    @Test
    fun `no id means no dedupe, never a refusal`() = runBlocking {
        assertTrue(ledger.begin(null) is DeliveryLedger.Decision.Process)
        assertTrue(ledger.begin("") is DeliveryLedger.Decision.Process)
        assertEquals(0, dao.count())
    }

    @Test
    fun `ids are kept a day`() = runBlocking {
        ledger.begin("old")
        now += DeliveryLedger.RETENTION_MS + 1
        ledger.begin("new")
        assertEquals(1, ledger.prune())
        assertEquals(setOf("new"), dao.rows.keys)
    }
}
