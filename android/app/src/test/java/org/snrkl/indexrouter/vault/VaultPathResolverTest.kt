package org.snrkl.indexrouter.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultPathResolverTest {

    private val root = "/storage/emulated/0"

    @Test
    fun `maps a picked folder on primary storage to a path`() {
        assertEquals(
            "/storage/emulated/0/Documents/snrkl_vault",
            VaultPathResolver.toFilesystemPath("primary:Documents/snrkl_vault", root),
        )
    }

    @Test
    fun `handles the storage root itself`() {
        assertEquals(root, VaultPathResolver.toFilesystemPath("primary:", root))
    }

    @Test
    fun `folder names with spaces survive`() {
        assertEquals(
            "/storage/emulated/0/Obsidian/My Vault",
            VaultPathResolver.toFilesystemPath("primary:Obsidian/My Vault", root),
        )
    }

    @Test
    fun `a folder chosen inside the vault becomes a vault-relative path`() {
        assertEquals(
            "Blog Ideas",
            VaultPathResolver.toVaultRelative("/storage/emulated/0/snrkl_vault/Blog Ideas", "/storage/emulated/0/snrkl_vault"),
        )
        assertEquals(
            "_RingRing/Shopping",
            VaultPathResolver.toVaultRelative("/storage/emulated/0/snrkl_vault/_RingRing/Shopping/", "/storage/emulated/0/snrkl_vault"),
        )
        // The vault root itself is a legitimate choice: notes land at the top.
        assertEquals("", VaultPathResolver.toVaultRelative("/storage/emulated/0/snrkl_vault", "/storage/emulated/0/snrkl_vault"))
    }

    @Test
    fun `a folder outside the vault is refused rather than stored unusably`() {
        assertNull(VaultPathResolver.toVaultRelative("/storage/emulated/0/Download", "/storage/emulated/0/snrkl_vault"))
        // A sibling whose name merely starts the same way is still outside.
        assertNull(VaultPathResolver.toVaultRelative("/storage/emulated/0/snrkl_vault_old/Notes", "/storage/emulated/0/snrkl_vault"))
    }

    @Test
    fun `a removable volume has no stable path, so it stays on SAF`() {
        assertNull(VaultPathResolver.toFilesystemPath("1A2B-3C4D:vault", root))
        assertNull(VaultPathResolver.toFilesystemPath(null, root))
    }
}
