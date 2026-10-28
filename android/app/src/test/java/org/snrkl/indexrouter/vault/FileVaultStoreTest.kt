package org.snrkl.indexrouter.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileVaultStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store(): Pair<FileVaultStore, File> {
        val root = temp.newFolder("snrkl_vault")
        return FileVaultStore(root) to root
    }

    @Test
    fun `paths are vault-relative, not relative to some picked subfolder`() {
        // The bug this replaces: granting _RingRing made "_RingRing/Inbox" land in
        // _RingRing/_RingRing/Inbox. With a root path there is nothing to nest.
        val (vault, root) = store()
        vault.writeTextUnique("_RingRing/Inbox", "note", "body")

        assertTrue(File(root, "_RingRing/Inbox/note.md").isFile)
        assertFalse(File(root, "_RingRing/_RingRing").exists())
    }

    @Test
    fun `two notes in the same minute do not overwrite each other`() {
        val (vault, root) = store()
        val first = vault.writeTextUnique("Blog Ideas", "2026-08-18 1957 Idea", "one")
        val second = vault.writeTextUnique("Blog Ideas", "2026-08-18 1957 Idea", "two")

        assertEquals("Blog Ideas/2026-08-18 1957 Idea.md", first)
        assertEquals("Blog Ideas/2026-08-18 1957 Idea 2.md", second)
        assertEquals("one", File(root, first).readText())
        assertEquals("two", File(root, second).readText())
    }

    @Test
    fun `recordings get the same collision protection as notes`() {
        val (vault, _) = store()
        val a = vault.writeBytesUnique("_RingRing/Audio", "clip", "m4a", byteArrayOf(1), "audio/mp4")
        val b = vault.writeBytesUnique("_RingRing/Audio", "clip", "m4a", byteArrayOf(2), "audio/mp4")
        assertEquals("_RingRing/Audio/clip.m4a", a)
        assertEquals("_RingRing/Audio/clip 2.m4a", b)
    }

    @Test
    fun `backups go to a dot-folder Obsidian ignores, keeping the original name`() {
        val (vault, root) = store()
        vault.writeText("_RingRing/indexrouter-config.md", "first")
        vault.backup("_RingRing/indexrouter-config.md")
        vault.writeText("_RingRing/indexrouter-config.md", "second")

        assertEquals("first", File(root, "_RingRing/.backups/indexrouter-config.md").readText())
        assertEquals("second", File(root, "_RingRing/indexrouter-config.md").readText())
    }

    @Test
    fun `rewriting a file truncates rather than leaving a tail`() {
        val (vault, _) = store()
        vault.writeText("note.md", "a long piece of original content")
        vault.writeText("note.md", "short")
        assertEquals("short", vault.readText("note.md"))
    }

    @Test
    fun `an existing writable folder is usable, and one not yet created is creatable`() {
        val (vault, root) = store()
        File(root, "Blog Ideas").mkdirs()
        assertEquals(FolderAccess.Writable, vault.access("Blog Ideas"))
        assertEquals(FolderAccess.Creatable, vault.access("_RingRing/Audio"))
    }

    @Test
    fun `a folder outside the granted scopes reports which folder is missing`() {
        // Stands in for a folder GrapheneOS has not scoped to this app: present in
        // the vault, but not writable by us.
        val (vault, root) = store()
        val scoped = File(root, "Private").apply { mkdirs() }
        assertTrue("test needs a non-root user to be meaningful", scoped.setWritable(false, false))

        val access = vault.access("Private")
        assertTrue(access is FolderAccess.NoScope)
        assertEquals("Private", (access as FolderAccess.NoScope).folderPath)
        assertFalse(access.usable)

        scoped.setWritable(true, true)
    }

    @Test
    fun `writability is proven by writing, not by asking the filesystem`() {
        // Android answers isDirectory() and canWrite() from stat, which succeed
        // even when open() returns EPERM for an app holding no storage
        // permission. Trusting those made the app report a healthy vault while
        // every note failed to save, so access() now writes a probe file.
        val (vault, root) = store()
        val folder = File(root, "Locked").apply { mkdirs() }
        assertEquals(FolderAccess.Writable, vault.access("Locked"))

        assertTrue("test needs a non-root user to be meaningful", folder.setWritable(false, false))
        assertTrue(vault.access("Locked") is FolderAccess.NoScope)
        folder.setWritable(true, true)
    }

    @Test
    fun `the write probe never leaves a file behind`() {
        val (vault, root) = store()
        File(root, "Notes").mkdirs()
        vault.access("Notes")
        assertTrue(root.resolve("Notes").listFiles()!!.isEmpty())
    }

    @Test
    fun `reading a file that is not there is not an error`() {
        val (vault, _) = store()
        assertEquals(null, vault.readText("_Daily Notes/2026/08/2026-08-18.md"))
    }
}
