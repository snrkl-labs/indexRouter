package org.snrkl.indexrouter.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GrantBackedVaultStoreTest {

    @get:Rule val temp = TemporaryFolder()

    /** Stands in for the picker grant: sees every file, whoever wrote it. */
    private class FakeGrant(val files: MutableMap<String, String> = mutableMapOf(), var ready: Boolean = true) : VaultStore {
        override val isReady get() = ready
        override fun describe() = "grant"
        override fun readText(path: String) = files[path]
        override fun writeText(path: String, contents: String): String { files[path] = contents; return path }
        override fun writeTextUnique(folderPath: String, baseName: String, contents: String) = error("unused")
        override fun writeBytesUnique(folderPath: String, baseName: String, extension: String, bytes: ByteArray, mime: String) = error("unused")
        override fun backup(path: String) = Unit
        override fun delete(path: String) = files.remove(path) != null
        override fun listFiles(folderPath: String) =
            files.keys.filter { it.substringBeforeLast('/', "") == folderPath }.map { it.substringAfterLast('/') }
        override fun listFilesRecursive(folderPath: String) = listFiles(folderPath)
        override fun access(folderPath: String): FolderAccess = FolderAccess.Writable
        override fun topLevelFolderNames() = emptySet<String>()
        override fun subfolderNames(folderPath: String) = emptySet<String>()
        override fun rootName() = "vault"
    }

    private val config = "_RingRing/indexrouter-config.md"

    /** A config file the path cannot open, as when another app wrote it. */
    private fun foreignFile(root: File): File {
        val file = File(root, config).apply { parentFile!!.mkdirs(); writeText("by path") }
        assumeTrue("needs a non-root user", file.setReadable(false) && !file.canRead())
        return file
    }

    @Test
    fun `a file the path may not open is read through the grant`() {
        val root = temp.newFolder("vault")
        val file = foreignFile(root)
        try {
            val store = GrantBackedVaultStore(FileVaultStore(root), FakeGrant(mutableMapOf(config to "from the laptop")))
            assertEquals("from the laptop", store.readText(config))
        } finally {
            file.setReadable(true)
        }
    }

    @Test
    fun `without a usable grant the refusal stands, never an absence`() {
        val root = temp.newFolder("vault")
        val file = foreignFile(root)
        try {
            for (grant in listOf(FakeGrant(ready = false), FakeGrant())) {
                val error = runCatching { GrantBackedVaultStore(FileVaultStore(root), grant).readText(config) }.exceptionOrNull()
                assertTrue("expected the path's refusal, got $error", error is java.io.IOException)
            }
        } finally {
            file.setReadable(true)
        }
    }

    @Test
    fun `a write the path refuses goes through the grant`() {
        val root = temp.newFolder("vault")
        val file = File(root, config).apply { parentFile!!.mkdirs(); writeText("old") }
        assumeTrue("needs a non-root user", file.setWritable(false) && !file.canWrite())
        try {
            val grant = FakeGrant()
            GrantBackedVaultStore(FileVaultStore(root), grant).writeText(config, "new")
            assertEquals("new", grant.files[config])
        } finally {
            file.setWritable(true)
        }
    }

    @Test
    fun `a missing vault is not written through the grant either`() {
        val root = File(temp.root, "not-mounted")
        val grant = FakeGrant()
        val error = runCatching { GrantBackedVaultStore(FileVaultStore(root), grant).writeText(config, "x") }.exceptionOrNull()
        assertTrue(error != null)
        assertTrue(grant.files.isEmpty())
    }

    @Test
    fun `own files are served by path, and listings include the other apps' files`() {
        val root = temp.newFolder("vault")
        val grant = FakeGrant(mutableMapOf("_RingRing/theirs.md" to "x"))
        val store = GrantBackedVaultStore(FileVaultStore(root), grant)
        store.writeText("_RingRing/mine.md", "mine")
        assertEquals("mine", File(root, "_RingRing/mine.md").readText())
        assertEquals(listOf("mine.md", "theirs.md"), store.listFiles("_RingRing"))
    }
}
