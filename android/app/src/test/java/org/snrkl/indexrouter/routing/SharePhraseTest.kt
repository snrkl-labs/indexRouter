package org.snrkl.indexrouter.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class SharePhraseTest {

    @Test
    fun `a trailing full stop is removed, because it costs the parsed time`() {
        // Measured against KashCal: with the stop it reads Tomorrow/All day and
        // leaves "noon." as text; without it, Tomorrow 12:00. Every spoken
        // appointment would have quietly become an all-day event.
        assertEquals(
            "lunch with Sinclair tomorrow at noon",
            SharePhrase.forParsing("lunch with Sinclair tomorrow at noon."),
        )
    }

    @Test
    fun `other sentence-final marks go too`() {
        assertEquals("call Bob back", SharePhrase.forParsing("call Bob back!"))
        assertEquals("dentist on Friday", SharePhrase.forParsing("dentist on Friday?"))
        assertEquals("standup at nine", SharePhrase.forParsing("standup at nine…"))
        assertEquals("pay the rent", SharePhrase.forParsing("pay the rent,"))
    }

    @Test
    fun `punctuation inside the phrase is left alone`() {
        // Removing it throughout would damage the phrase; it is the end of the
        // string the parser trips on.
        assertEquals(
            "meet Dr. Ellis at 3 p.m",
            SharePhrase.forParsing("meet Dr. Ellis at 3 p.m."),
        )
    }

    @Test
    fun `it becomes one line, since several read as a description not a phrase`() {
        assertEquals("first part second part", SharePhrase.forParsing("first part\n  second part"))
        assertEquals("a b", SharePhrase.forParsing("a\r\n\r\nb"))
    }

    @Test
    fun `runs of spaces collapse`() {
        assertEquals("lunch with Sinclair", SharePhrase.forParsing("lunch    with  Sinclair"))
    }

    @Test
    fun `an empty or punctuation-only phrase yields an empty string, not a stray mark`() {
        assertEquals("", SharePhrase.forParsing(""))
        assertEquals("", SharePhrase.forParsing("   "))
        assertEquals("", SharePhrase.forParsing("..."))
    }
}
