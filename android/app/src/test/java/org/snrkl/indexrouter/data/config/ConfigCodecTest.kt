package org.snrkl.indexrouter.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
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

    /** The per-vault layout: this vault's rules, a common copy, layout only. */
    private val perVault = """
        ---
        version: "00.05.00"
        ---

        ```yaml
        settings:
          base-dir: "Inbox/_RingRing"
          task-tags: ["#task"]
        routing:
          - keywords: ["blog idea"]
            action: file
            folder: "blogPostIdeas"
            priority: 20
        common:
          - keywords: ["alarm"]
            action: timer
            priority: 10
        ```
    """.trimIndent()

    @Test
    fun `reads an older single-vault file, splitting common rules from the vault's own`() {
        val document = ConfigCodec.decode(bodyBlock)
        assertEquals(1, document.routing.size)
        assertEquals(listOf("blog idea", "content idea"), document.routing[0].allKeywords())
        assertEquals("blogPostIdeas", document.routing[0].folder)
        assertEquals(listOf("blogPostIdeas"), document.routing[0].tags)
        // The timer needs no vault, so it is a common rule now.
        assertEquals(listOf("timer"), document.common!!.map { it.allKeywords().single() })
        // Priorities are carried exactly: evaluation order must not change.
        assertEquals(10, document.routing[0].priority)
        assertEquals(100, document.common!![0].priority)
        assertEquals("_RingRing", document.layout.baseDir)
        assertEquals("_Tasks", document.layout.taskDir)
        // Device settings are handed back for the one-time import.
        assertEquals(8787, document.legacySettings!!.serverPort)
    }

    @Test
    fun `still reads a file that keeps its config in the frontmatter`() {
        val document = ConfigCodec.decode(legacyFrontmatter)
        assertEquals(1, document.routing.size)
        assertEquals(listOf("blogidea"), document.routing[0].allKeywords())
        assertEquals("_RingRing", document.layout.baseDir)
        assertEquals(1, document.common!!.size)
    }

    @Test
    fun `an older config is converted to the per-vault layout when written back`() {
        val moved = ConfigCodec.encode(ConfigCodec.decode(legacyFrontmatter))
        assertTrue(moved.contains("```yaml"))
        assertTrue(moved.contains("version: \"00.06.00\""))
        val frontmatter = moved.substringAfter("---\n").substringBefore("\n---")
        assertFalse(frontmatter.contains("base-dir"))
        // Device settings are no longer written to a vault file.
        assertFalse(moved.contains("server-port"))
        assertFalse(moved.contains("notifications:"))
        assertFalse(moved.contains("privacy:"))
        val reread = ConfigCodec.decode(moved)
        assertEquals(ConfigCodec.decode(legacyFrontmatter).routing, reread.routing)
        assertEquals(ConfigCodec.decode(legacyFrontmatter).common, reread.common)
        assertEquals(null, reread.legacySettings)
    }

    @Test
    fun `reads the per-vault format with a multi-segment base folder`() {
        val document = ConfigCodec.decode(perVault)
        assertEquals("Inbox/_RingRing", document.layout.baseDir)
        assertEquals(1, document.routing.size)
        assertEquals(1, document.common!!.size)
        assertEquals("timer", document.common!![0].action)
        assertEquals(null, document.legacySettings)
    }

    @Test
    fun `round-trips without losing a rule`() {
        val original = ConfigCodec.decode(perVault)
        val reparsed = ConfigCodec.decode(ConfigCodec.encode(original))
        assertEquals(original, reparsed)
    }

    @Test
    fun `a per-vault file with no common key is silence, not an empty list`() {
        val noCommon = perVault.substringBefore("common:").trimEnd() + "\n```\n"
        assertEquals(null, ConfigCodec.decode(noCommon).common)
    }

    @Test
    fun `a file never names its gesture or another vault`() {
        val encoded = ConfigCodec.encode(ConfigCodec.decode(perVault))
        // The rules block: rules for either gesture say so, and nothing says
        // which gesture the vault belongs to.
        val block = encoded.substringAfter("```yaml").substringBefore("```")
        assertFalse(block.contains("click-hold"))
        assertFalse(encoded.contains("vault-root"))
        assertFalse(encoded.contains("trigger"))
    }

    @Test
    fun `a rule's gesture survives the round trip`() {
        val document = ConfigDocument(
            routing = listOf(
                RuleBlock(keywords = listOf("idea"), action = "file", gesture = "double-click-hold"),
                RuleBlock(keywords = listOf("note"), action = "file"),
            ),
        )
        val reparsed = ConfigCodec.decode(ConfigCodec.encode(document))
        assertEquals(org.snrkl.indexrouter.routing.RuleGroup.DOUBLE_CLICK_HOLD, reparsed.routing[0].gestureGroup())
        assertEquals(null, reparsed.routing[1].gestureGroup())
        assertEquals("any", reparsed.routing[1].gesture)
    }

    @Test
    fun `a 00_05_00 rule has no gesture and answers either`() {
        val document = ConfigCodec.decode(perVault)
        assertEquals("any", document.routing[0].gesture)
        assertEquals(null, document.routing[0].gestureGroup())
    }

    @Test
    fun `an unknown gesture reads as either rather than failing the file`() {
        val document = ConfigCodec.decode(perVault.replace("    action: file", "    action: file\n    gesture: triple-tap"))
        assertEquals(null, document.routing[0].gestureGroup())
    }

    @Test
    fun `the older file's device settings read with their defaults`() {
        val document = ConfigCodec.decode(legacyFrontmatter)
        val legacy = document.legacySettings!!
        assertEquals(CalendarMode.DEFAULT.wireName, legacy.calendar.mode)
        assertEquals(2, legacy.privacy.logRetentionHours)
        assertFalse(legacy.privacy.detailedContentLogging)
        assertTrue(document.routing[0].stripKeyword)
    }

    @Test
    fun `the strip switch survives the round trip`() {
        val document = ConfigDocument(
            routing = listOf(RuleBlock(keywords = listOf("note"), action = "file", stripKeyword = false)),
        )
        val reparsed = ConfigCodec.decode(ConfigCodec.encode(document))
        assertFalse(reparsed.routing[0].stripKeyword)
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
        assertEquals("_RingRing", ConfigCodec.decode(withSecondBlock).layout.baseDir)
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
        assertEquals("Vault", document.layout.baseDir)
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
        assertEquals(1, ConfigCodec.decode(withExtra).routing.size)
    }
}
