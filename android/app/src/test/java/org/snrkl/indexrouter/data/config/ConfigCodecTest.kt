package org.snrkl.indexrouter.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.CalendarMode

class ConfigCodecTest {

    /** The layout written up to 00.03.00: everything in the frontmatter. */
    private val legacyFrontmatter = """
        ---
        version: 2
        settings:
          base-dir: "_RingRing"
          task-tags: ["#task", "#index"]
        routing:
          - keyword: blogidea
            action: file
            folder: "blogPostIdeas"
            tags: [blogPostIdeas]
            priority: 10
          - keyword: timer
            action: timer
        ---

        # Routing config

        Free-form notes down here are ignored by the parser.
    """.trimIndent()

    /** The current layout: version in the frontmatter, config in the body. */
    private val bodyBlock = """
        ---
        version: "00.04.00"
        ---

        # Routing config

        Some prose about editing it.

        ```yaml
        settings:
          base-dir: "_RingRing"
          task-tags: ["#task", "#index"]
        routing:
          - keywords: ["blog idea", "content idea"]
            action: file
            folder: "blogPostIdeas"
            tags: [blogPostIdeas]
            priority: 10
          - keywords: ["timer"]
            action: timer
        ```

        More prose after it.
    """.trimIndent()

    @Test
    fun `reads the body block, defaulting what was left out`() {
        val document = ConfigCodec.decode(bodyBlock)
        assertEquals(2, document.routing.size)
        assertEquals(listOf("blog idea", "content idea"), document.routing[0].allKeywords())
        assertEquals("blogPostIdeas", document.routing[0].folder)
        assertEquals(listOf("blogPostIdeas"), document.routing[0].tags)
        assertEquals("_RingRing", document.settings.baseDir)
        // Reserved names fall back to defaults when the file omits them.
        assertEquals("_Tasks", document.settings.taskDir)
        // Omitted keys fall back rather than failing the whole file.
        assertEquals(8787, document.settings.serverPort)
        assertEquals(100, document.routing[1].priority)
        assertTrue(document.routing[1].enabled)
    }

    @Test
    fun `still reads a file that keeps its config in the frontmatter`() {
        // Installs from before 00.04.00 have this shape. Refusing it would mean
        // re-entering every rule, which is what the last version bump avoided.
        val document = ConfigCodec.decode(legacyFrontmatter)
        assertEquals(2, document.routing.size)
        assertEquals(listOf("blogidea"), document.routing[0].allKeywords())
        assertEquals("blogPostIdeas", document.routing[0].folder)
        assertEquals("_RingRing", document.settings.baseDir)
    }

    @Test
    fun `a frontmatter config is moved into the body when written back`() {
        val moved = ConfigCodec.encode(ConfigCodec.decode(legacyFrontmatter))
        assertTrue(moved.contains("```yaml"))
        // The settings are no longer between the --- fences.
        val frontmatter = moved.substringAfter("---\n").substringBefore("\n---")
        assertFalse(frontmatter.contains("base-dir"))
        assertTrue(frontmatter.contains("version:"))
        // And it still reads as the same configuration.
        assertEquals(ConfigCodec.decode(legacyFrontmatter), ConfigCodec.decode(moved))
    }

    @Test
    fun `round-trips without losing a rule`() {
        val original = ConfigCodec.decode(bodyBlock)
        val reparsed = ConfigCodec.decode(ConfigCodec.encode(original))
        assertEquals(original, reparsed)
    }

    @Test
    fun `notification settings survive the round trip`() {
        // They are mirrored to the vault so a reinstall restores them; if they
        // did not round-trip, a reinstall would quietly revert them.
        val document = ConfigDocument(
            settings = SettingsBlock(
                notifications = NotifyBlock(task = true, fallback = false, debugStream = true),
            ),
        )
        val reparsed = ConfigCodec.decode(ConfigCodec.encode(document))
        assertTrue(reparsed.settings.notifications.task)
        assertTrue(reparsed.settings.notifications.enabledFor(ActionType.TASK))
        assertFalse(reparsed.settings.notifications.enabledFor(ActionType.FILE))
        assertFalse(reparsed.settings.notifications.fallback)
        assertTrue(reparsed.settings.notifications.debugStream)
    }

    @Test
    fun `the calendar mode and its target survive the round trip`() {
        // Mirrored to the vault like every setting, so a reinstall restores it
        // rather than quietly reverting to a different mechanism.
        val document = ConfigDocument(
            settings = SettingsBlock(
                calendar = CalendarBlock(mode = "insert", sharePackage = "com.example.cal"),
            ),
        )
        val reparsed = ConfigCodec.decode(ConfigCodec.encode(document))
        assertEquals("insert", reparsed.settings.calendar.mode)
        assertEquals("com.example.cal", reparsed.settings.calendar.sharePackage)
    }

    @Test
    fun `a config written before the calendar mode existed reads with the default`() {
        // An older installed file has no calendar block at all.
        val document = ConfigCodec.decode(legacyFrontmatter)
        assertEquals(CalendarMode.DEFAULT.wireName, document.settings.calendar.mode)
        assertEquals("org.onekash.kashcal", document.settings.calendar.sharePackage)
    }

    @Test
    fun `the strip switch and the privacy settings survive the round trip`() {
        val document = ConfigDocument(
            settings = SettingsBlock(
                privacy = PrivacyBlock(logRetentionHours = 6, detailedContentLogging = true),
            ),
            routing = listOf(RuleBlock(keywords = listOf("note"), action = "file", stripKeyword = false)),
        )
        val reparsed = ConfigCodec.decode(ConfigCodec.encode(document))
        assertEquals(6, reparsed.settings.privacy.logRetentionHours)
        assertTrue(reparsed.settings.privacy.detailedContentLogging)
        assertFalse(reparsed.routing[0].stripKeyword)
    }

    @Test
    fun `a config written before the strip switch existed defaults it on`() {
        // On is what every example of the wanted behaviour asked for, and it is
        // only ever applied at the start of a phrase, so it cannot eat content.
        val document = ConfigCodec.decode(legacyFrontmatter)
        assertTrue(document.routing[0].stripKeyword)
        assertEquals(2, document.settings.privacy.logRetentionHours)
        assertFalse(document.settings.privacy.detailedContentLogging)
    }

    @Test
    fun `emits a body block Obsidian will show as plain text`() {
        val encoded = ConfigCodec.encode(ConfigDocument(routing = listOf(RuleBlock(keywords = listOf("task"), action = "task"))))
        assertTrue(encoded.startsWith("---\n"))
        assertTrue(encoded.contains("\n---\n"))
        // The whole reason for the move: a fenced block edits as text, where
        // frontmatter goes through Obsidian's properties editor.
        assertTrue(encoded.contains("```yaml"))
        assertTrue(encoded.contains("task"))
        // The banner warns that in-app edits overwrite hand-edits.
        assertTrue(encoded.contains("overwrite this file"))
    }

    @Test
    fun `the first block is the configuration, not a later one`() {
        // The footer prose could grow an example block, and a pasted-in block
        // below must not quietly become the live configuration.
        val withSecondBlock = bodyBlock + "\n\n```yaml\nsettings:\n  base-dir: \"NotThisOne\"\n```\n"
        assertEquals("_RingRing", ConfigCodec.decode(withSecondBlock).settings.baseDir)
    }

    @Test
    fun `the shared secret is never written to the vault`() {
        // The vault is synced; the secret must stay on the handset. The
        // serialized document has no field for it at all, and this pins that.
        val encoded = ConfigCodec.encode(ConfigDocument())
        assertFalse(encoded.contains("secret:"))
    }

    @Test
    fun `a file with neither frontmatter nor a block is reported, not silently defaulted`() {
        val e = assertThrows(ConfigCodec.ParseException::class.java) {
            ConfigCodec.decode("# Just a note\n\nNothing to parse here.")
        }
        assertTrue(e.message!!.contains("yaml"))
    }

    @Test
    fun `a block with no frontmatter still works if it carries its own version`() {
        // A file someone rewrote by hand without the frontmatter should not be
        // refused when it says plainly what version it is.
        val noFrontmatter = """
            # Routing config

            ```yaml
            version: "00.04.00"
            settings:
              base-dir: "Vault"
            routing:
              - keywords: ["task"]
                action: task
            ```
        """.trimIndent()
        val document = ConfigCodec.decode(noFrontmatter)
        assertEquals("Vault", document.settings.baseDir)
        assertEquals(1, document.routing.size)
    }

    @Test
    fun `a broken block is reported rather than silently reverting to defaults`() {
        val broken = """
            ---
            version: "00.04.00"
            ---

            ```yaml
            settings:
              base-dir: "_RingRing"
             routing:
            - keywords: [unclosed
            ```
        """.trimIndent()
        assertThrows(ConfigCodec.ParseException::class.java) { ConfigCodec.decode(broken) }
    }

    @Test
    fun `the legacy single-keyword field is read but never written back`() {
        // It is compatibility scaffolding. Emitted, it put a `keyword: null`
        // line on every rule of a file whose whole purpose is hand-editing.
        val encoded = ConfigCodec.encode(ConfigCodec.decode(legacyFrontmatter))
        assertFalse(encoded.contains("keyword: null"))
        // Reading it still works, which is the half that matters.
        assertEquals(listOf("blogidea"), ConfigCodec.decode(encoded).routing[0].allKeywords())
    }

    @Test
    fun `unknown keys from a hand-edit do not cost the whole config`() {
        val withExtra = bodyBlock.replace(
            "routing:",
            "some-future-key: 42\nrouting:",
        )
        assertEquals(2, ConfigCodec.decode(withExtra).routing.size)
    }
}
