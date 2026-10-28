package org.snrkl.indexrouter

import android.app.Application
import org.snrkl.indexrouter.action.Rerouter
import org.snrkl.indexrouter.action.RoutingPipeline
import org.snrkl.indexrouter.data.config.ConfigRepository
import org.snrkl.indexrouter.data.db.AppDatabase
import org.snrkl.indexrouter.data.prefs.SecretStore
import org.snrkl.indexrouter.data.prefs.SettingsStore
import org.snrkl.indexrouter.moc.MocGenerator
import org.snrkl.indexrouter.data.prefs.StorageMode
import org.snrkl.indexrouter.vault.FileVaultStore
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
        val settings: SettingsStore by lazy { SettingsStore(app) }
        val secrets: SecretStore by lazy { SecretStore(app) }
        val vault: VaultRepository by lazy { VaultRepository(app) }

        /**
         * Resolved on every call rather than cached: the mode and root can change
         * while the receiver is up, and a stale store would write to the old place.
         */
        fun vaultStore(): VaultStore? = when (settings.storageMode) {
            StorageMode.FILES -> settings.vaultRootPath?.let { FileVaultStore(File(it)) }
            StorageMode.SAF -> settings.vaultTreeUri?.let { SafVaultStore(vault, it) }
        }

        val config: ConfigRepository by lazy {
            ConfigRepository(app, database.rules(), ::vaultStore, settings)
        }

        val rerouter: Rerouter by lazy {
            Rerouter(settings, ::vaultStore, database.log())
        }

        val vaultAccess: VaultAccessCache by lazy { VaultAccessCache(::vaultStore) }

        val moc: MocGenerator by lazy {
            MocGenerator(settings, config, ::vaultStore)
        }

        val pipeline: RoutingPipeline by lazy {
            RoutingPipeline(app, config, database.log(), ::vaultStore, settings) { folder ->
                moc.writeProFormaIfMissing(folder)
            }
        }
    }
}
