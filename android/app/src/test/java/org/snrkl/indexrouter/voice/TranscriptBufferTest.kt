package org.snrkl.indexrouter.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TranscriptBufferTest {

    private val now = 1_800_000_000_000L
    private fun minutesAgo(n: Long) = now - TimeUnit.MINUTES.toMillis(n)
    private val tenMinutes = TimeUnit.MINUTES.toMillis(10)

    @Test
    fun `the newest transcript is the one committed`() {
        val result = TranscriptBuffer.pick(
            entries = listOf(
                TranscriptBuffer.Entry("buy some milk", minutesAgo(1)),
                TranscriptBuffer.Entry("older thing", minutesAgo(5)),
            ),
            now = now,
            cutoffMillis = tenMinutes,
        )
        assertEquals(TranscriptBuffer.Result.Commit("buy some milk", TimeUnit.MINUTES.toMillis(1)), result)
    }

    @Test
    fun `a stale transcript is refused rather than committed`() {
        // The case the cutoff exists for: tapping the mic key hours later must
        // not put that morning's words into whatever field is focused.
        val result = TranscriptBuffer.pick(
            entries = listOf(TranscriptBuffer.Entry("this morning's shopping list", minutesAgo(240))),
            now = now,
            cutoffMillis = tenMinutes,
        )
        assertTrue(result is TranscriptBuffer.Result.TooOld)
        assertEquals(tenMinutes, (result as TranscriptBuffer.Result.TooOld).cutoffMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(240), result.ageMillis)
    }

    @Test
    fun `a transcript exactly at the cutoff is still committed`() {
        // An inclusive boundary, so "ten minutes" does not mean "sometimes nine".
        val result = TranscriptBuffer.pick(
            entries = listOf(TranscriptBuffer.Entry("right on the line", minutesAgo(10))),
            now = now,
            cutoffMillis = tenMinutes,
        )
        assertTrue(result is TranscriptBuffer.Result.Commit)
    }

    @Test
    fun `a cutoff of zero means no cutoff`() {
        val result = TranscriptBuffer.pick(
            entries = listOf(TranscriptBuffer.Entry("from last week", minutesAgo(20_000))),
            now = now,
            cutoffMillis = 0L,
        )
        assertEquals("from last week", (result as TranscriptBuffer.Result.Commit).text)
    }

    @Test
    fun `an empty buffer says so rather than committing an empty string`() {
        assertEquals(TranscriptBuffer.Result.Empty, TranscriptBuffer.pick(emptyList(), now, tenMinutes))
    }

    @Test
    fun `blank transcripts are skipped, not committed`() {
        // A recording that transcribed to nothing would otherwise wipe out the
        // useful one behind it by being newer.
        val result = TranscriptBuffer.pick(
            entries = listOf(
                TranscriptBuffer.Entry("   ", minutesAgo(1)),
                TranscriptBuffer.Entry("the real one", minutesAgo(2)),
            ),
            now = now,
            cutoffMillis = tenMinutes,
        )
        assertEquals("the real one", (result as TranscriptBuffer.Result.Commit).text)
    }

    @Test
    fun `surrounding whitespace is trimmed before committing`() {
        val result = TranscriptBuffer.pick(
            entries = listOf(TranscriptBuffer.Entry("  padded  ", minutesAgo(1))),
            now = now,
            cutoffMillis = tenMinutes,
        )
        assertEquals("padded", (result as TranscriptBuffer.Result.Commit).text)
    }

    @Test
    fun `a timestamp in the future does not sail past the cutoff`() {
        // A clock adjustment between the webhook arriving and the mic key being
        // tapped would otherwise produce a negative age, which compares as well
        // within any cutoff — accidentally right, but for no reason.
        val result = TranscriptBuffer.pick(
            entries = listOf(TranscriptBuffer.Entry("from the future", now + TimeUnit.HOURS.toMillis(2))),
            now = now,
            cutoffMillis = tenMinutes,
        )
        assertEquals(0L, (result as TranscriptBuffer.Result.Commit).ageMillis)
    }
}
