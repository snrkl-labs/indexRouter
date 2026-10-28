package org.snrkl.indexrouter.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantCheckTest {

    private val paths = listOf(
        "_Daily Notes/{YYYY}/{MM}/{YYYY-MM-DD}.md",
        "_RingRing/Audio",
        "_RingRing/Inbox",
        "Blog Ideas",
    )

    @Test
    fun `vault root passes`() {
        val result = GrantCheck.diagnose(
            grantedFolderName = "snrkl_vault",
            configuredPaths = paths,
        )
        assertEquals(GrantCheck.Result.Ok, result)
    }

    @Test
    fun `granting _RingRing itself is caught before anything is written`() {
        // The real report: notes landed in _RingRing/_RingRing/Inbox.
        val result = GrantCheck.diagnose(
            grantedFolderName = "_RingRing",
            configuredPaths = paths,
        )
        assertEquals(GrantCheck.Result.NestedInsideItself("_RingRing"), result)
    }

    @Test
    fun `an empty vault on a clean install is fine`() {
        // baseDir is created by the app on first write, so demanding it already
        // exist would refuse every fresh setup.
        val result = GrantCheck.diagnose(
            grantedFolderName = "Vault2",
            configuredPaths = listOf("_RingRing"),
        )
        assertEquals(GrantCheck.Result.Ok, result)
    }

    @Test
    fun `stripping the duplicated segment`() {
        assertEquals("Audio", GrantCheck.stripPrefix("_RingRing/Audio", "_RingRing"))
        assertEquals("Inbox", GrantCheck.stripPrefix("_RingRing/Inbox", "_ringring"))
        // Paths that do not start with the prefix are left alone.
        assertEquals("Blog Ideas", GrantCheck.stripPrefix("Blog Ideas", "_RingRing"))
    }
}
