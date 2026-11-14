package org.snrkl.indexrouter

import android.app.Application
import android.os.Environment
import android.provider.DocumentsContract
import org.snrkl.indexrouter.action.Rerouter
import org.snrkl.indexrouter.action.RoutingPipeline
import org.snrkl.indexrouter.data.config.ConfigRepository
import org.snrkl.indexrouter.data.db.AppDatabase
import org.snrkl.indexrouter.data.db.DeliveryLedger
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.data.prefs.SecretStore
import org.snrkl.indexrouter.data.prefs.SettingsStore
import org.snrkl.indexrouter.moc.MocGenerator
import org.snrkl.indexrouter.data.prefs.StorageMode
import org.snrkl.indexrouter.vault.FileVaultStore
import org.snrkl.indexrouter.vault.GrantBackedVaultStore
import org.snrkl.indexrouter.vault.VaultPathResolver
import org.snrkl.indexrouter.vault.SafVaultStore
import org.snrkl.indexrouter.vault.VaultAccessCache
import org.snrkl.indexrouter.vault.VaultRepository
import org.snrkl.indexrouter.vault.VaultStore
import java.io.File

class IndexRouterApp : Application() {

    val container: Container by lazy { Container(this) }

    /**
     * Hand-rolled service locator. One activity, one service and a handful of
     * singletons do not justify a DI framework's build-time cost here.
     */
    class Container(app: Application) {
        val database: AppDatabase by lazy { AppDatabase.open(app) }

        val settings: SettingsStore by lazy {
            // An install from before there were two vaults: its one vault is
            // carried over as a profile of its own, then the mode is chosen
            // from what the install already had.
            SettingsStore(app).also {
                it.migrateLegacyVault()
                it.settleVaultMode()
            }
        }
        val secrets: SecretStore by lazy { SecretStore(app) }
        val vault: VaultRepository by lazy { VaultRepository(app) }

        /**
         * The vault of one gesture, resolved on every call rather than cached:
         * the folder can change while the receiver is up, and a stale store
         * would write to the old place. Null when the gesture has no vault.
         *
         * There is deliberately no "the vault": every caller names the
         * gesture, which is what makes writing to the wrong one impossible to
         * do by accident.
         */
        fun vaultStore(group: RuleGroup): VaultStore? {
            if (!group.hasVault) return null
            val profile = settings.profile(group)
            return when (profile.storageMode) {
                StorageMode.FILES -> profile.vaultRootPath?.let { root ->
                    val files = FileVaultStore(File(root))
                    // The grant from picking the vault, when it is for this
                    // same folder: it opens the files other apps wrote there.
                    val tree = profile.vaultTreeUri
                    if (tree != null && VaultPathResolver.grantIsFor(
                            runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull(),
                            root,
                            Environment.getExternalStorageDirectory().absolutePath,
                        )
                    ) {
                        GrantBackedVaultStore(files, SafVaultStore(vault, tree))
                    } else {
                        files
                    }
                }
                StorageMode.SAF -> profile.vaultTreeUri?.let { SafVaultStore(vault, it) }
            }
        }

        val config: ConfigRepository by lazy {
            ConfigRepository(app, database.rules(), ::vaultStore, settings, database.log())
        }

        val rerouter: Rerouter by lazy {
            Rerouter(settings, ::vaultStore, database.log())
        }

        /** One access cache per gesture, so probing one vault never answers for the other. */
        private val accessCaches: Map<RuleGroup, VaultAccessCache> by lazy {
            RuleGroup.TRIGGERS.associateWith { group -> VaultAccessCache { vaultStore(group) } }
        }

        fun vaultAccess(group: RuleGroup): VaultAccessCache = accessCaches.getValue(group)

        val moc: MocGenerator by lazy {
            MocGenerator(settings, config, ::vaultStore)
        }

        val ledger: DeliveryLedger by lazy { DeliveryLedger(database.deliveries()) }

        val pipeline: RoutingPipeline by lazy {
            RoutingPipeline(app, config, database.log(), ledger, ::vaultStore, settings) { group, folder ->
                moc.writeProFormaIfMissing(group, folder)
            }
        }
    }
}
