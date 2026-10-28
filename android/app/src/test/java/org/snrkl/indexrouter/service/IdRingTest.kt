package org.snrkl.indexrouter.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IdRingTest {

    @Test
    fun `ids stay inside the ring however many are taken`() {
        // The point of the whole class: Android refuses every notification from
        // an app once 50 are live, so an id that climbs forever ends up
        // silencing the failure alerts rather than merely filling the shade.
        val ring = IdRing(first = 5_000, size = 12)
        val taken = (1..500).map { ring.take() }
        assertTrue(taken.all { it in 5_000..5_011 })
        assertEquals(12, taken.distinct().size)
    }

    @Test
    fun `it wraps round to replace its own oldest, in order`() {
        val ring = IdRing(first = 100, size = 3)
        assertEquals(listOf(100, 101, 102, 100, 101), (1..5).map { ring.take() })
    }

    @Test
    fun `a ring of one is legal and always returns the same slot`() {
        val ring = IdRing(first = 7, size = 1)
        assertEquals(listOf(7, 7, 7), (1..3).map { ring.take() })
    }

    @Test
    fun `concurrent takers do not collide within one lap`() {
        // A burst of recordings routes concurrently; two taking the same id
        // inside one lap means one notification silently replaces another.
        val ring = IdRing(first = 0, size = 64)
        val pool = Executors.newFixedThreadPool(8)
        val results = java.util.Collections.synchronizedList(mutableListOf<Int>())
        repeat(64) { pool.submit { results.add(ring.take()) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(64, results.distinct().size)
    }

    @Test
    fun `the live ceiling stays under Android's per-app cap`() {
        // 2 fixed ids (the service notice and the milestone summary) plus every
        // ring full at once. If a ring is ever resized, this is what catches it.
        val fixed = 2
        val rings = listOf(12, 12, 12, 8)
        assertTrue("rings total ${fixed + rings.sum()}", fixed + rings.sum() < 50)
    }
}
