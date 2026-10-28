package org.snrkl.indexrouter.data.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.snrkl.indexrouter.AppIdentity
import org.snrkl.indexrouter.data.db.RoutingRuleDao
import org.snrkl.indexrouter.data.db.RoutingRuleEntity
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.data.prefs.SettingsStore
import org.snrkl.indexrouter.service.Notifications
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.MatchType
import org.snrkl.indexrouter.routing.RoutingRule
import org.snrkl.indexrouter.routing.Tags
import org.snrkl.indexrouter.vault.VaultStore

/**
 * Owns the routing config in both of its homes.
 *
 * The database is authoritative at runtime; the vault file is a durable mirror
 * written through on every edit and read back only on demand. A webhook arriving
 * must never wait on file I/O to find out where it is going.
 */
class ConfigRepository(
    private val context: Context,
    private val rulesDao: RoutingRuleDao,
    private val vaultStore: () -> VaultStore?,
    private val settings: SettingsStore,
) {

    /**
     * Outcome of saving one rule.
     *
     * `keyword` is uniquely indexed, because two rules for the same word are
     * meaningless — the first match wins and the second could never fire. That
     * index used to be enforced only by SQLite, which throws, and the throw
     * killed the app: renaming a rule onto an existing keyword crashed it
     * outright. The clash is now a result the UI can act on.
     */
    sealed interface SaveResult {
        data object Saved : SaveResult
        data class DuplicateKeyword(val keyword: String) : SaveResult
        data class Failed(val reason: String) : SaveResult
    }

    sealed interface SyncResult {
        data class Loaded(val ruleCount: Int) : SyncResult
        data class Fallback(val reason: String) : SyncResult
        data class Failed(val reason: String) : SyncResult
    }

    /**
     * The rule set, cached in memory.
     *
     * Measured at ~346ms per read on the status screen, which is most of a
     * second-long render — and rules change only when something in this class
     * writes them, so re-reading on every render was paying a database round
     * trip for an answer that had not moved.
     */
    @Volatile
    private var cachedRules: List<RoutingRule>? = null

    suspend fun rules(): List<RoutingRule> = cachedRules ?: withContext(Dispatchers.IO) {
        rulesDao.all().map { it.toDomain() }.also { cachedRules = it }
    }

    /** Every path that writes rules clears the cache; nothing else may change them. */
    private fun invalidateRules() {
        cachedRules = null
    }

    /**
     * Ensures the database has something to route with. Called on app start and
     * before the first webhook is served.
     */
    suspend fun ensureInitialised(): SyncResult = withContext(Dispatchers.IO) {
        upgradeReservedFolders()
        if (rulesDao.count() > 0) return@withContext SyncResult.Loaded(rulesDao.count())
        when (val fromVault = reloadFromVault()) {
            is SyncResult.Loaded -> fromVault
            else -> loadBundledDefaults(
                reason = (fromVault as? SyncResult.Fallback)?.reason
                    ?: (fromVault as? SyncResult.Failed)?.reason
                    ?: "vault config unavailable",
            )
        }
    }

    /**
     * Applies the reserved-folder rename once, and makes the config file agree.
     *
     * Without the write-through the next reload would restore the old names
     * from the file, which is how an upgraded install kept filing into `tasks`
     * after the default changed.
     */
    private suspend fun upgradeReservedFolders() {
        val renames = settings.upgradeReservedFolderNames()
        if (renames.isEmpty()) return

        Log.i(AppIdentity.LOG_TAG, "Reserved folders renamed: $renames")
        writeThroughToVault()
        Notifications.alert(
            context,
            context.getString(R.string.alert_folders_renamed_title),
            context.getString(
                R.string.alert_folders_renamed_body,
                renames.joinToString(", ") { "${it.first} → ${it.second}" },
            ),
        )
    }

    /** Manual reload / app-open: the vault file wins over what is in the database. */
    suspend fun reloadFromVault(): SyncResult = withContext(Dispatchers.IO) {
        val store = vaultStore()
            ?: return@withContext SyncResult.Fallback("no vault access configured")
        if (!store.isReady) {
            return@withContext SyncResult.Fallback("vault is not reachable — check this app's Storage Scopes")
        }

        val contents = runCatching { store.readText(settings.configPath()) }
            .getOrElse { return@withContext SyncResult.Failed(it.message ?: "vault read failed") }
            ?: return@withContext SyncResult.Fallback("no config file at ${settings.configPath()}")

        // A file written before the rename carries the old names; the same
        // upgrade applies to what was just read as to what was already stored.
        val document = try {
            ConfigCodec.decode(contents)
        } catch (e: ConfigCodec.UnsupportedVersionException) {
            // Deliberately not upgraded in place: v1 folder values are vault-root
            // paths whose meaning has narrowed, and guessing would write to the
            // wrong place. Start clean and say so — out loud, because the file is
            // about to be replaced and a silent swap is exactly the kind of
            // quiet degradation this app exists to avoid.
            Notifications.alert(
                context,
                context.getString(R.string.alert_legacy_config_title),
                context.getString(R.string.alert_legacy_config_body, settings.configPath()),
            )
            return@withContext loadBundledDefaults(
                "config file is version ${e.found}; starting from defaults — delete it or use Reset to defaults",
            )
        } catch (e: ConfigCodec.ParseException) {
            // A typo in a hand-edit must not silently revert to defaults.
            return@withContext SyncResult.Failed(e.message ?: "config could not be parsed")
        }

        applyDocument(document)
        upgradeReservedFolders()
        settings.lastSyncedAt = System.currentTimeMillis()
        settings.usingFallbackConfig = false
        SyncResult.Loaded(document.routing.size)
    }

    suspend fun loadBundledDefaults(reason: String): SyncResult = withContext(Dispatchers.IO) {
        val document = runCatching {
            ConfigCodec.decode(context.assets.open("default-config.md").use { it.readBytes().toString(Charsets.UTF_8) })
        }.getOrElse {
            Log.e(AppIdentity.LOG_TAG, "Bundled default config is unreadable", it)
            return@withContext SyncResult.Failed("bundled defaults unreadable")
        }
        applyDocument(document)
        settings.usingFallbackConfig = true
        SyncResult.Fallback(reason)
    }

    /** Write-through: called after every in-app edit. Also the config export. */
    suspend fun writeThroughToVault(): SyncResult = withContext(Dispatchers.IO) {
        val store = vaultStore()
            ?: return@withContext SyncResult.Failed("no vault access configured")
        val document = ConfigDocument(
            settings = settings.toBlock(),
            routing = rulesDao.all().map { it.toBlock() },
        )
        try {
            // Keep the previous file: a hand-edit clobbered by a write-through is
            // the one known sharp edge in this design, and a .bak makes it recoverable.
            store.backup(settings.configPath())
            store.writeText(settings.configPath(), ConfigCodec.encode(document))
        } catch (t: Throwable) {
            return@withContext SyncResult.Failed(t.message ?: "vault write failed")
        }
        settings.lastSyncedAt = System.currentTimeMillis()
        settings.usingFallbackConfig = false
        SyncResult.Loaded(document.routing.size)
    }

    /** Wipes the local rules and reloads the bundled defaults, then writes through. */
    suspend fun resetToDefaults(): SyncResult = withContext(Dispatchers.IO) {
        loadBundledDefaults("reset requested")
        writeThroughToVault()
    }

    suspend fun upsert(rule: RoutingRule): SaveResult = withContext(Dispatchers.IO) {
        val entity = rule.toEntity()
        // Uniqueness spans every rule's keywords together, so the clash is found
        // by comparing sets rather than by a WHERE clause.
        val mine = entity.keywords.split(',').filter { it.isNotBlank() }.toSet()
        val clash = rulesDao.allForValidation()
            .filter { it.id != rule.id }
            .flatMap { other -> other.keywords.split(',').map { it.trim() } }
            .firstOrNull { it.isNotBlank() && it in mine }
        if (clash != null) {
            return@withContext SaveResult.DuplicateKeyword(clash)
        }
        try {
            invalidateRules()
            rulesDao.upsert(entity)
        } catch (t: Throwable) {
            // Belt and braces: the check above races with a config reload, and a
            // constraint violation must never take the app down with it.
            Log.e(AppIdentity.LOG_TAG, "Could not save rule '${entity.keywords}'", t)
            return@withContext SaveResult.Failed(t.message ?: "could not save the rule")
        }
        writeThroughToVault()
        SaveResult.Saved
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        invalidateRules()
        rulesDao.delete(id)
        writeThroughToVault()
    }

    private suspend fun applyDocument(document: ConfigDocument) {
        settings.apply(document.settings)
        invalidateRules()
        rulesDao.clear()
        rulesDao.insertAll(
            document.routing.mapNotNull { block ->
                if (block.allKeywords().isEmpty()) {
                    Log.w(AppIdentity.LOG_TAG, "Skipping a rule with no keywords")
                    return@mapNotNull null
                }
                val action = ActionType.fromWire(block.action)
                if (action == null) {
                    Log.w(
                        AppIdentity.LOG_TAG,
                        "Skipping rule '${block.allKeywords().first()}': unknown action '${block.action}'",
                    )
                    return@mapNotNull null
                }
                RoutingRuleEntity(
                    keywords = block.allKeywords().joinToString(",") { it.lowercase() },
                    action = action.wireName,
                    // A single folder name under baseDir; anything path-like is a
                    // leftover from the old vault-root semantics.
                    folder = block.folder?.trim('/')?.substringAfterLast('/'),
                    tags = Tags.canonical(block.tags).joinToString(","),
                    matchType = MatchType.fromWire(block.matchType).wireName,
                    priority = block.priority,
                    enabled = block.enabled,
                    includeInMoc = block.includeInMoc,
                    stripKeyword = block.stripKeyword,
                )
            },
        )
    }
}

fun RoutingRuleEntity.toDomain() = RoutingRule(
    id = id,
    keywords = keywords.split(',').map { it.trim() }.filter { it.isNotBlank() },
    action = ActionType.fromWire(action) ?: ActionType.FILE,
    folder = folder,
    // Canonicalised on the way out as well as in. Rows written before tags had
    // one form hold whichever was typed, and waiting for each rule to be
    // re-saved would leave them displaying uncanonically in the meantime —
    // which is exactly the inconsistency this fixes.
    tags = Tags.canonical(tags.split(',')),
    matchType = MatchType.fromWire(matchType),
    priority = priority,
    enabled = enabled,
    includeInMoc = includeInMoc,
    stripKeyword = stripKeyword,
)

fun RoutingRuleEntity.toBlock() = RuleBlock(
    keywords = keywords.split(',').map { it.trim() }.filter { it.isNotBlank() },
    action = action,
    folder = folder,
    tags = tags.split(',').map { it.trim() }.filter { it.isNotBlank() },
    matchType = matchType,
    priority = priority,
    enabled = enabled,
    includeInMoc = includeInMoc,
    stripKeyword = stripKeyword,
)

fun RoutingRule.toEntity() = RoutingRuleEntity(
    id = id,
    keywords = keywords.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().joinToString(","),
    action = action.wireName,
    folder = folder,
    // Canonical on the way in, so the same tag cannot be stored two ways
    // depending on whether the hash was typed.
    tags = Tags.canonical(tags).joinToString(","),
    matchType = matchType.wireName,
    priority = priority,
    enabled = enabled,
    includeInMoc = includeInMoc,
    stripKeyword = stripKeyword,
)
