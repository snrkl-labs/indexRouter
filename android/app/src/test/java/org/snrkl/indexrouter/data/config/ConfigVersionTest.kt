package org.snrkl.indexrouter.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigVersionTest {

    /** The shape v0.1.2 wrote: no version key, folder holding a vault path. */
    private val v1File = """
        ---
        settings:
          daily-note-path: "_Daily Notes/{YYYY}/{MM}/{YYYY-MM-DD}.md"
          audio-folder: "_RingRing/Audio"
          catch-all-folder: "_RingRing/Inbox"
        routing:
          - keyword: blogidea
            action: file
            folder: "Blog Ideas"
        ---
    """.trimIndent()

    @Test
    fun `a v1 file is refused rather than reinterpreted`() {
        // Its folder values are vault-root paths. Reading them as relative to
        // base-dir would silently write to _RingRing/Blog Ideas, or worse
        // _RingRing/_RingRing/Shopping for picker-set values.
        val e = assertThrows(ConfigCodec.UnsupportedVersionException::class.java) {
            ConfigCodec.decode(v1File)
        }
        // No version key at all, which is itself the tell.
        assertEquals("unversioned", e.found)
    }

    @Test
    fun `a v2 file is read, not refused — its single keyword widens to a set`() {
        // Unlike v1's folder change, this one has no ambiguity to guess at, so
        // upgrading costs the user nothing.
        val v2 = """
            ---
            version: 2
            settings:
              base-dir: "_RingRing"
            routing:
              - keyword: blogidea
                action: file
                folder: blogPostIdeas
            ---
        """.trimIndent()
        val document = ConfigCodec.decode(v2)
        assertEquals(listOf("blogidea"), document.routing[0].allKeywords())
        // It is re-stamped as current, so writing it back does not leave a v2 file.
        assertEquals("00.04.00", document.version)
    }

    @Test
    fun `an integer-versioned file from v0_6_0 still reads`() {
        // The whole point of the bump: nobody re-enters their rules because the
        // settings moved out of the frontmatter.
        val v3 = """
            ---
            version: 3
            settings:
              base-dir: "_RingRing"
              task-dir: "_Tasks"
            routing:
              - keywords: ["blog idea", "content idea"]
                action: file
                folder: blogPostIdeas
            ---
        """.trimIndent()
        val document = ConfigCodec.decode(v3)
        assertEquals(listOf("blog idea", "content idea"), document.routing[0].allKeywords())
        assertEquals("_Tasks", document.settings.taskDir)
        assertEquals("00.04.00", document.version)
    }

    @Test
    fun `a config round-trips through the new layout`() {
        val document = ConfigDocument(
            settings = SettingsBlock(baseDir = "_RingRing"),
            routing = listOf(RuleBlock(keywords = listOf("blogidea"), action = "file", folder = "blogPostIdeas")),
        )
        val reparsed = ConfigCodec.decode(ConfigCodec.encode(document))
        assertEquals(document, reparsed)
        assertEquals("00.04.00", reparsed.version)
    }

    @Test
    fun `encoded config declares its version so the next change can be detected`() {
        // Quoted: bare 00.04.00 is not a YAML number, and leading zeroes in an
        // unquoted scalar are the sort of thing a parser is entitled to have
        // opinions about.
        assertTrue(ConfigCodec.encode(ConfigDocument()).contains("version: \"00.04.00\""))
    }

    @Test
    fun `version parsing accepts both shapes, and refuses what is not a version`() {
        assertEquals(ConfigVersion(0, 4, 0), ConfigVersion.parse("00.04.00"))
        assertEquals(ConfigVersion(0, 4, 0), ConfigVersion.parse("\"00.04.00\""))
        // A hand-edit is allowed to be sloppy about trailing zeroes.
        assertEquals(ConfigVersion(0, 4, 0), ConfigVersion.parse("0.4"))
        // A bare integer is a file written before this scheme existed.
        assertEquals(ConfigVersion(0, 3, 0), ConfigVersion.parse("3"))
        assertEquals(ConfigVersion(1, 12, 3), ConfigVersion.parse("01.12.03"))
        assertNull(ConfigVersion.parse(null))
        assertNull(ConfigVersion.parse(""))
        assertNull(ConfigVersion.parse("draft"))
        assertNull(ConfigVersion.parse("0.4.x"))
    }

    @Test
    fun `versions order by component, not by string`() {
        // "00.10.00" sorts before "00.09.00" as text, which is exactly the trap
        // a string comparison would fall into.
        assertTrue(ConfigVersion(0, 9, 0) < ConfigVersion(0, 10, 0))
        assertTrue(ConfigVersion(0, 2, 0) < ConfigVersion.CURRENT)
        assertTrue(ConfigVersion.OLDEST_READABLE <= ConfigVersion.CURRENT)
    }

    @Test
    fun `the version text is the XX_YY_ZZ form used elsewhere in the vault`() {
        assertEquals("00.04.00", ConfigVersion(0, 4, 0).text)
        assertEquals("01.00.12", ConfigVersion(1, 0, 12).text)
    }

    @Test
    fun `defaults describe the reserved folder schema`() {
        val settings = SettingsBlock()
        assertEquals("_RingRing", settings.baseDir)
        // Underscore-prefixed so the reserved folders sort above the user's own.
        assertEquals("_Tasks", settings.taskDir)
        assertEquals("_Inbox", settings.fallbackDir)
        assertEquals("_Audio", settings.audioDir)
        assertEquals(5, settings.mocBackupRetention)
    }

    @Test
    fun `routes are quiet by default, apart from the ones that want attention`() {
        val notify = NotifyBlock()
        // A notification per voice note undoes the point of the app, which is
        // that a note files itself without the phone being touched.
        assertTrue(!notify.file && !notify.task && !notify.timer && !notify.calendar)
        // A recording that matched nothing is waiting for a decision.
        assertTrue(notify.fallback)
        assertTrue(!notify.debugStream)
    }
}
