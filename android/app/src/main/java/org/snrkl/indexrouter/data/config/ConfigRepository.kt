package org.snrkl.indexrouter.data.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.snrkl.indexrouter.AppIdentity
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.data.db.RoutingLogDao
import org.snrkl.indexrouter.data.db.RoutingRuleDao
import org.snrkl.indexrouter.data.db.RoutingRuleEntity
import org.snrkl.indexrouter.data.prefs.SettingsStore
import org.snrkl.indexrouter.data.prefs.VaultProfile
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.MatchType
import org.snrkl.indexrouter.routing.RoutingRule
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.routing.Tags
import org.snrkl.indexrouter.routing.VaultMode
import org.snrkl.indexrouter.service.Notifications
import org.snrkl.indexrouter.vault.VaultStore

/**
 * Owns the routing config in all of its homes.
 *
 * The database is authoritative at runtime. Each vault's file is a durable
 * mirror of that vault's part of it, written through on every edit and read
 * back only on demand — a webhook arriving must never wait on file I/O to find
 * out where it is going.
 *
 * With two vaults, an edit lands where it belongs:
 * - a trigger group's rule, or that vault's layout, is written to that vault;
 * - a common rule is written to **both**. A vault that cannot be reached is
 *   marked stale and caught up when it can be; the edit itself is never
 *   blocked or lost because a mirror failed.
 */
class ConfigRepository(
    private val context: Context,
    private val rulesDao: RoutingRuleDao,
    private val vaultStore: (RuleGroup) -> VaultStore?,
    private val settings: SettingsStore,
    private val logDao: RoutingLogDao? = null,
) {

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
     * A reload found common rules in a vault's file that differ from the
     * app's. Not resolved silently: either copy could be the one meant, so
     * the user picks. Held until [resolveCommonConflict].
     */
    data class CommonConflict(val group: RuleGroup, val fromVault: List<RuleBlock>)

    private val _commonConflict = MutableStateFlow<CommonConflict?>(null)
    val commonConflict: StateFlow<CommonConflict?> = _commonConflict.asStateFlow()

    /** Serialises writes to vault files, so two edits cannot interleave a write and its backup. */
    private val writeLock = Mutex()

    @Volatile
    private var cachedRules: List<RoutingRule>? = null

    /**
     * The whole rule set, every group, cached in memory: rules change only
     * when something in this class writes them, and re-reading on every render
     * once cost most of a second.
     */
    suspend fun rules(): List<RoutingRule> = cachedRules ?: withContext(Dispatchers.IO) {
        rulesDao.all().map { it.toDomain() }.also { cachedRules = it }
    }

    private fun invalidateRules() {
        cachedRules = null
    }

    /**
     * Ensures there is something to route with. Called on app start and before
     * the first webhook is served.
     */
    suspend fun ensureInitialised(): SyncResult = withContext(Dispatchers.IO) {
        settleOneVaultUpgrade()
        settings.vaultProfiles.forEach { upgradeReservedFolders(it) }
        if (rulesDao.count() > 0) {
            catchUpStaleVaults()
            return@withContext SyncResult.Loaded(rulesDao.count())
        }

        val loaded = settings.vaultProfiles
            .filter { it.isAssigned }
            .map { reloadFromVault(it.group) }
            .filterIsInstance<SyncResult.Loaded>()
        if (loaded.isNotEmpty() && rulesDao.count() > 0) {
            return@withContext SyncResult.Loaded(rulesDao.count())
        }
        loadBundledDefaults("no vault config to load from")
    }

    /**
     * Finishes an upgrade into one-vault mode, once there is something to do.
     *
     * - A vault from before there were two, still waiting for a gesture, is
     *   given to the gesture that holds the one vault: with one vault there is
     *   no choice to wait for.
     * - Rules left in the other gesture's group (the bundled defaults it was
     *   given in 0.20, or rules added for it) join the one vault limited to
     *   that gesture, so they still answer only it. One whose keyword the
     *   vault already uses for that gesture is dropped: it could never fire.
     */
    private suspend fun settleOneVaultUpgrade() {
        if (upgradeSettled) return
        upgradeSettled = true
        val mode = settings.vaultMode
        if (mode.perGesture) return
        if (!settings.profile(mode.home).isAssigned && settings.carriedOver != null) {
            assignCarriedOver(mode.home)
        }
        val other = mode.home.other ?: return
        val stray = rulesDao.all().filter { it.groupId == other.wireName }
        if (stray.isEmpty()) return
        invalidateRules()
        val kept = mutableListOf<RoutingRule>()
        stray.forEach { entity ->
            val moved = entity.toDomain().copy(group = mode.home, gesture = other)
            val existing = rules().filter { it.id != entity.id && it.group != other } + kept
            if (existing.none { mode.overlap(it, moved) && it.keywords.any { k -> k in moved.keywords } }) {
                kept += moved
            } else {
                Log.i(AppIdentity.LOG_TAG, "Dropped ${other.wireName} rule '${entity.keywords}': its keyword is already in use")
            }
        }
        rulesDao.clearGroup(other.wireName)
        rulesDao.insertAll(kept.map { it.toEntity() })
        invalidateRules()
        if (settings.profile(mode.home).isAssigned) writeThroughToVault(mode.home)
    }

    private suspend fun upgradeReservedFolders(profile: VaultProfile) {
        val renames = profile.upgradeReservedFolderNames()
        if (renames.isEmpty() || !profile.isAssigned) return
        Log.i(AppIdentity.LOG_TAG, "Reserved folders renamed in ${profile.group.wireName}: $renames")
        writeThroughToVault(profile.group)
        Notifications.alert(
            context,
            context.getString(R.string.alert_folders_renamed_title),
            context.getString(
                R.string.alert_folders_renamed_body,
                renames.joinToString(", ") { "${it.first} → ${it.second}" },
            ),
        )
    }

    /**
     * Reads one vault's file. That vault's group is replaced from it; nothing
     * else is. The common copy is compared, not applied, unless the app has no
     * common rules of its own yet.
     */
    suspend fun reloadFromVault(group: RuleGroup): SyncResult =
        reloadFromVaultUnrecorded(group).also { recordSync(group, it) }

    /**
     * Remembers why a vault's file could not be read or written, or that it
     * could. "No vault assigned" is not a failure of the file and is not kept.
     */
    private fun recordSync(group: RuleGroup, result: SyncResult) {
        val profile = settings.profile(group)
        if (!profile.isAssigned) return
        profile.lastSyncError = when (result) {
            is SyncResult.Loaded -> null
            is SyncResult.Fallback -> result.reason
            is SyncResult.Failed -> result.reason
        }
    }

    private suspend fun reloadFromVaultUnrecorded(group: RuleGroup): SyncResult = withContext(Dispatchers.IO) {
        val profile = settings.profile(group)
        val store = vaultStore(group)
            ?: return@withContext SyncResult.Fallback("no vault assigned")
        if (!store.isReady) {
            return@withContext SyncResult.Fallback("vault is not reachable — check this app's Storage Scopes")
        }

        val contents = runCatching { store.readText(profile.configPath()) }
            .getOrElse { return@withContext SyncResult.Failed(it.message ?: "vault read failed") }
            ?: return@withContext SyncResult.Fallback("no config file at ${profile.configPath()}")

        val document = try {
            ConfigCodec.decode(contents)
        } catch (e: ConfigCodec.UnsupportedVersionException) {
            // Not upgraded in place: v1 folder values are vault-root paths whose
            // meaning has narrowed, and guessing would write to the wrong place.
            Notifications.alert(
                context,
                context.getString(R.string.alert_legacy_config_title),
                context.getString(R.string.alert_legacy_config_body, profile.configPath()),
            )
            return@withContext SyncResult.Failed(
                "config file is version ${e.found}; delete it or use Reset to defaults",
            )
        } catch (e: ConfigCodec.ParseException) {
            // A typo in a hand-edit must not silently revert to defaults.
            return@withContext SyncResult.Failed(e.message ?: "config could not be parsed")
        }

        profile.apply(document.layout)
        // A single-vault file carried the device settings too. They are taken
        // in once, then the app's copy is the authority.
        document.legacySettings?.let { legacy ->
            if (!settings.deviceSettingsImported) {
                settings.applyDevice(legacy)
                settings.deviceSettingsImported = true
            }
        }

        val pending = pendingRules(group)
        val groupRules = toEntities(document.routing, group)
        invalidateRules()
        rulesDao.clearGroup(group.wireName)
        rulesDao.insertAll(groupRules)

        document.common?.let { fromVault ->
            val mine = rulesDao.all().filter { it.groupId == RuleGroup.COMMON.wireName }
            when {
                mine.isEmpty() -> replaceCommon(fromVault)
                sameRules(mine.map { it.toBlock() }, fromVault) -> Unit
                else -> _commonConflict.value = CommonConflict(group, fromVault)
            }
        }

        upgradeReservedFolders(profile)
        profile.lastSyncedAt = System.currentTimeMillis()
        profile.usingFallbackConfig = false
        // Rules sent to this vault before it was chosen, when a vault per
        // gesture was switched on: kept beside the ones its file holds.
        if (pending.isNotEmpty() && mergePending(group, pending) > 0) writeThroughUnrecorded(group)
        // A file in an older format is left exactly as it is — including the
        // device settings it still carries — and converted the next time an
        // edit writes it. Reading must never be what rewrites a file.
        SyncResult.Loaded(groupRules.size)
    }

    /**
     * The user's answer to a [CommonConflict]. Taking the vault's copy replaces
     * the app's common rules and writes them to every vault; keeping the app's
     * rewrites the vault that differed.
     */
    suspend fun resolveCommonConflict(useVault: Boolean) = withContext(Dispatchers.IO) {
        val conflict = _commonConflict.value ?: return@withContext
        _commonConflict.value = null
        if (useVault) {
            replaceCommon(conflict.fromVault)
            writeThroughAll()
        } else {
            writeThroughToVault(conflict.group)
        }
    }

    /**
     * Rules waiting in [group] for a vault that has not been chosen yet. A
     * vault per gesture was switched on and its rules were sent here, and the
     * file about to be read must not erase them.
     */
    private suspend fun pendingRules(group: RuleGroup): List<RoutingRuleEntity> {
        if (!group.isTrigger || !settings.profile(group).holdsPendingRules) return emptyList()
        return rulesDao.all().filter { it.groupId == group.wireName }
    }

    /** Adds [pending] back into [group], skipping any whose keyword is now taken. Returns how many. */
    private suspend fun mergePending(group: RuleGroup, pending: List<RoutingRuleEntity>): Int {
        settings.profile(group).holdsPendingRules = false
        val mode = settings.vaultMode
        val present = rulesDao.all().map { it.toDomain() }
        val added = pending.map { it.toDomain().copy(id = 0) }.filter { candidate ->
            present.none { mode.overlap(it, candidate) && it.keywords.any { k -> k in candidate.keywords } }
        }
        if (added.isEmpty()) return 0
        invalidateRules()
        rulesDao.insertAll(added.map { it.toEntity() })
        return added.size
    }

    private suspend fun replaceCommon(blocks: List<RuleBlock>) {
        invalidateRules()
        rulesDao.clearGroup(RuleGroup.COMMON.wireName)
        rulesDao.insertAll(toEntities(blocks, RuleGroup.COMMON))
    }

    /**
     * The bundled rules, for a first start with nothing to load and for Reset.
     * Common defaults, plus the default trigger rules in each gesture's group.
     * Layout is left alone: it describes vaults that exist, and a reset of the
     * rules should not move where anything is filed.
     */
    suspend fun loadBundledDefaults(reason: String): SyncResult = withContext(Dispatchers.IO) {
        val document = bundled() ?: return@withContext SyncResult.Failed("bundled defaults unreadable")
        invalidateRules()
        // The carried-over vault's rules are kept: they are the user's, waiting
        // for a gesture, and a reset of the defaults is not a reason to lose them.
        RuleGroup.entries.filter { it != RuleGroup.CARRIED_OVER }.forEach { rulesDao.clearGroup(it.wireName) }
        rulesDao.insertAll(toEntities(document.common.orEmpty(), RuleGroup.COMMON))
        settings.vaultMode.vaultGroups.forEach { rulesDao.insertAll(toEntities(document.routing, it)) }
        settings.vaultProfiles.forEach { it.usingFallbackConfig = true }
        SyncResult.Fallback(reason)
    }

    private fun bundled(): ConfigDocument? = runCatching {
        ConfigCodec.decode(context.assets.open(DEFAULT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) })
    }.getOrElse {
        Log.e(AppIdentity.LOG_TAG, "Bundled default config is unreadable", it)
        null
    }

    /**
     * A vault was just assigned to [group]. Its own file wins if it has one,
     * which is how a reinstall gets its rules back. Otherwise the gesture
     * starts with the bundled trigger rules if it has none, and the file is
     * created.
     */
    suspend fun onVaultAssigned(group: RuleGroup): SyncResult = withContext(Dispatchers.IO) {
        val profile = settings.profile(group)
        val existing = try {
            vaultStore(group)?.readText(profile.configPath())
        } catch (t: Throwable) {
            // There is a file, and it cannot be read. Writing a new one over it
            // would lose whatever it holds, so stop and say so.
            return@withContext SyncResult.Failed(t.message ?: "config file unreadable").also { recordSync(group, it) }
        }
        if (existing != null) return@withContext reloadFromVault(group)
        // No file yet: whatever is waiting in the group is what it starts with.
        profile.holdsPendingRules = false

        if (rulesDao.all().none { it.groupId == group.wireName }) {
            bundled()?.let { invalidateRules(); rulesDao.insertAll(toEntities(it.routing, group)) }
        }
        writeThroughToVault(group)
    }

    /**
     * Gives the vault an older install had to [gesture]: its settings, its
     * file and task rules, and the log rows that point into it. Priorities
     * are untouched, so it routes exactly as it did. Then its file is read,
     * as for any newly assigned vault.
     */
    suspend fun assignCarriedOver(gesture: RuleGroup): SyncResult = withContext(Dispatchers.IO) {
        require(gesture.isTrigger)
        if (settings.profile(gesture).isAssigned) {
            return@withContext SyncResult.Failed("${settings.groupName(gesture)} already has a vault")
        }
        val from = RuleGroup.CARRIED_OVER.wireName
        settings.moveCarriedOverTo(gesture)
        invalidateRules()
        // The gesture may have picked up bundled rules while it had no vault;
        // the carried-over rules are the real ones.
        rulesDao.clearGroup(gesture.wireName)
        rulesDao.moveGroup(from, gesture.wireName)
        logDao?.moveVaultGroup(from, gesture.wireName)
        logDao?.moveRuleGroup(from, gesture.wireName)
        onVaultAssigned(gesture)
    }

    @Volatile
    private var upgradeSettled = false

    // ---- switching between one vault and a vault per gesture -------------------

    /** What switching a vault per gesture on would do with the rules. For the preview. */
    data class OnPreview(
        /** Timer, calendar and dictation rules: stay common, keeping their gesture. */
        val common: List<RoutingRule>,
        /** Rules limited to the other gesture: go to its vault. */
        val toOther: List<RoutingRule>,
        /** Rules for either gesture: stay with the kept vault, or are copied to both. */
        val either: List<RoutingRule>,
        /** Rules limited to the gesture keeping the vault: stay, and stop needing the limit. */
        val toKept: List<RoutingRule>,
    )

    suspend fun previewOn(keptBy: RuleGroup): OnPreview {
        val mode = settings.vaultMode
        val inVault = rules().filter { it.group == mode.home }
        return OnPreview(
            common = rules().filter { it.group == RuleGroup.COMMON },
            toOther = inVault.filter { it.gesture == keptBy.other },
            either = inVault.filter { it.gesture == null },
            toKept = inVault.filter { it.gesture == keptBy },
        )
    }

    /**
     * One vault → a vault per gesture.
     *
     * The vault stays with [keptBy]. Its rules limited to the other gesture
     * move to the other gesture's group, waiting for that vault to be chosen;
     * those for either gesture stay, or are also copied across when
     * [copyEitherToBoth]. Common rules keep their gestures. Nothing is
     * deleted from the vault.
     */
    suspend fun switchToPerGesture(keptBy: RuleGroup, copyEitherToBoth: Boolean) = withContext(Dispatchers.IO) {
        val mode = settings.vaultMode
        require(!mode.perGesture && keptBy.isTrigger)
        val other = keptBy.other!!
        invalidateRules()
        if (keptBy != mode.home) {
            // The vault's settings and rules change hands; so do the log rows
            // that point into it, so re-routing from the log still finds it.
            settings.moveVault(mode.home, keptBy)
            rulesDao.clearGroup(keptBy.wireName)
            rulesDao.moveGroup(mode.home.wireName, keptBy.wireName)
            logDao?.moveVaultGroup(mode.home.wireName, keptBy.wireName)
            logDao?.moveRuleGroup(mode.home.wireName, keptBy.wireName)
        }
        // Whatever the other gesture's group held is from before; it is
        // replaced by what this switch sends it.
        rulesDao.clearGroup(other.wireName)
        val vaultRules = rulesDao.all().filter { it.groupId == keptBy.wireName }.map { it.toDomain() }
        val stays = vaultRules.filter { it.gesture != other }.map { it.copy(gesture = null) }
        val moves = vaultRules.filter { it.gesture == other }.map { it.copy(group = other, gesture = null) }
        val copies = if (copyEitherToBoth) {
            vaultRules.filter { it.gesture == null }
                .filter { c -> moves.none { m -> m.keywords.any { it in c.keywords } } }
                .map { it.copy(id = 0, group = other) }
        } else {
            emptyList()
        }
        rulesDao.insertAll((stays + moves).map { it.toEntity() })
        rulesDao.insertAll(copies.map { it.toEntity() })
        settings.vaultMode = VaultMode(perGesture = true, home = keptBy)
        invalidateRules()
        settings.profile(other).holdsPendingRules = moves.isNotEmpty() || copies.isNotEmpty()
        if (settings.profile(other).isAssigned) {
            // A vault the other gesture had before is still remembered: read
            // it, keeping what was just sent there.
            reloadFromVault(other)
        }
        if (settings.profile(keptBy).isAssigned) writeThroughToVault(keptBy)
        Unit
    }

    /**
     * A vault per gesture → one vault.
     *
     * [keep]'s vault becomes the one vault. The other vault is forgotten by
     * the app but not touched: its file keeps its rules, and choosing it
     * again later brings them back. With [bringAcross], its rules are also
     * copied into the kept vault, limited to the gesture they answered. One
     * whose keyword is already used for that gesture is skipped.
     *
     * @return how many rules were skipped.
     */
    suspend fun switchToOneVault(keep: RuleGroup, bringAcross: Boolean): Int = withContext(Dispatchers.IO) {
        require(settings.vaultMode.perGesture && keep.isTrigger)
        val other = keep.other!!
        val one = VaultMode(perGesture = false, home = keep)
        invalidateRules()
        val all = rulesDao.all().map { it.toDomain() }
        val theirs = all.filter { it.group == other }
        rulesDao.clearGroup(other.wireName)
        var skipped = 0
        if (bringAcross) {
            val present = all.filter { it.group != other }.toMutableList()
            val brought = mutableListOf<RoutingRule>()
            theirs.forEach { rule ->
                val moved = rule.copy(id = 0, group = keep, gesture = other)
                if (present.any { one.overlap(it, moved) && it.keywords.any { k -> k in moved.keywords } }) {
                    skipped++
                } else {
                    present += moved
                    brought += moved
                }
            }
            rulesDao.insertAll(brought.map { it.toEntity() })
        }
        settings.profile(other).unassign()
        settings.profile(other).lastSyncedAt = 0L
        settings.vaultMode = one
        settings.profile(other).holdsPendingRules = false
        invalidateRules()
        if (settings.profile(keep).isAssigned) writeThroughToVault(keep)
        skipped
    }

    /** Writes one vault's file: its layout, its gesture's rules, and the common copy. */
    suspend fun writeThroughToVault(group: RuleGroup): SyncResult =
        writeThroughUnrecorded(group).also { recordSync(group, it) }

    private suspend fun writeThroughUnrecorded(group: RuleGroup): SyncResult = withContext(Dispatchers.IO) {
        val profile = settings.profile(group)
        val store = vaultStore(group)
            ?: return@withContext SyncResult.Failed("no vault assigned")
        // A file this app has never read is not overwritten: it may hold edits
        // made elsewhere. It is read first (Sync now, or the next catch-up);
        // until then the vault is only marked as behind.
        if (profile.lastSyncedAt == 0L) {
            val unread = try {
                store.readText(profile.configPath()) != null
            } catch (_: Throwable) {
                true
            }
            if (unread) {
                profile.commonStale = true
                return@withContext SyncResult.Failed("this vault's config file has not been read yet — use Sync now")
            }
        }
        val all = rulesDao.all()
        // With a vault per gesture, the vault says which gesture its rules
        // answer, so the file says `any` for them.
        val perGesture = settings.vaultMode.perGesture
        val document = ConfigDocument(
            layout = profile.toLayout(),
            routing = all.filter { it.groupId == group.wireName }
                .map { it.toBlock().let { b -> if (perGesture) b.copy(gesture = RuleBlock.GESTURE_ANY) else b } },
            common = all.filter { it.groupId == RuleGroup.COMMON.wireName }.map { it.toBlock() },
        )
        try {
            writeLock.withLock {
                // Keep the previous file: a hand-edit clobbered by a write-through
                // is the one known sharp edge in this design.
                store.backup(profile.configPath())
                store.writeText(profile.configPath(), ConfigCodec.encode(document))
            }
        } catch (t: Throwable) {
            return@withContext SyncResult.Failed(t.message ?: "vault write failed")
        }
        profile.lastSyncedAt = System.currentTimeMillis()
        profile.usingFallbackConfig = false
        profile.commonStale = false
        SyncResult.Loaded(document.routing.size)
    }

    /**
     * Writes every assigned vault — what a common edit needs. A vault that
     * cannot be written is marked stale and caught up later; the others are
     * written regardless.
     */
    suspend fun writeThroughAll(): Map<RuleGroup, SyncResult> = withContext(Dispatchers.IO) {
        settings.vaultProfiles.filter { it.isAssigned }.associate { profile ->
            val result = writeThroughToVault(profile.group)
            if (result !is SyncResult.Loaded) profile.commonStale = true
            profile.group to result
        }
    }

    /**
     * Retries any vault whose common copy fell behind. Cheap when none has.
     *
     * A vault whose file has never been read is read first, through [syncNow]:
     * writing the app's copy over a file nobody has looked at would put
     * whatever was edited elsewhere into the backup folder, where nobody would
     * think to look for it.
     */
    suspend fun catchUpStaleVaults() = withContext(Dispatchers.IO) {
        settings.vaultProfiles
            .filter { it.isAssigned && it.commonStale }
            .forEach { profile ->
                if (vaultStore(profile.group)?.isReady == true) syncNow(profile.group)
            }
    }

    /**
     * The status card's "Sync now". A vault never read is read first, so a
     * file that is already there — a reinstall, or one edited elsewhere — is
     * taken in rather than overwritten. Then, if the vault has no file yet or
     * its common copy is behind, it is written.
     */
    suspend fun syncNow(group: RuleGroup): SyncResult = withContext(Dispatchers.IO) {
        val profile = settings.profile(group)
        if (!profile.isAssigned) return@withContext SyncResult.Failed("no vault assigned")
        var result: SyncResult? = null
        if (profile.lastSyncedAt == 0L) {
            result = reloadFromVault(group)
            if (result is SyncResult.Failed) return@withContext result
        }
        val noFile = result is SyncResult.Fallback
        if (profile.commonStale || noFile || result == null) result = writeThroughToVault(group)
        result
    }

    /** Writes wherever an edit to [group]'s rules belongs. */
    private suspend fun writeThroughFor(group: RuleGroup) {
        if (group == RuleGroup.COMMON) writeThroughAll() else writeThroughToVault(group)
    }

    /** Wipes the local rules and reloads the bundled defaults, then writes through. */
    suspend fun resetToDefaults(): SyncResult = withContext(Dispatchers.IO) {
        loadBundledDefaults("reset requested")
        writeThroughAll()
        SyncResult.Loaded(rulesDao.count())
    }

    suspend fun upsert(input: RoutingRule): SaveResult = withContext(Dispatchers.IO) {
        val rule = normalise(input)
        if (!rule.group.allows(rule.action)) {
            return@withContext SaveResult.Failed("${rule.action.displayName} rules cannot go in this group")
        }
        val entity = rule.toEntity()
        val mine = entity.keywords.split(',').filter { it.isNotBlank() }.toSet()
        // A keyword must be unambiguous among the rules that can be checked
        // against the same recording. Rules that never meet — the two
        // gestures' vaults, or two rules limited to different gestures — can
        // each have their own "task".
        val mode = settings.vaultMode
        val clash = rulesDao.allForValidation()
            .filter { it.id != rule.id && mode.overlap(rule, it.toDomain()) }
            .flatMap { other -> other.keywords.split(',').map { it.trim() } }
            .firstOrNull { it.isNotBlank() && it in mine }
        if (clash != null) return@withContext SaveResult.DuplicateKeyword(clash)

        val previousGroup = rule.id.takeIf { it != 0L }?.let { id ->
            rulesDao.allForValidation().firstOrNull { it.id == id }?.groupId?.let(RuleGroup::fromWire)
        }
        try {
            invalidateRules()
            rulesDao.upsert(entity)
        } catch (t: Throwable) {
            Log.e(AppIdentity.LOG_TAG, "Could not save rule '${entity.keywords}'", t)
            return@withContext SaveResult.Failed(t.message ?: "could not save the rule")
        }
        writeThroughFor(rule.group)
        if (previousGroup != null && previousGroup != rule.group) writeThroughFor(previousGroup)
        SaveResult.Saved
    }

    /**
     * Puts a rule where the mode says it belongs. In one vault a rule's type
     * decides its group, so changing a note rule to a timer moves it to the
     * common rules. With a vault per gesture, a vault's own rules answer
     * whichever gesture the vault is for.
     */
    private fun normalise(rule: RoutingRule): RoutingRule {
        val mode = settings.vaultMode
        return when {
            !mode.perGesture && rule.group != RuleGroup.CARRIED_OVER -> rule.copy(group = mode.groupFor(rule.action))
            rule.group.hasVault -> rule.copy(gesture = null)
            else -> rule
        }
    }

    /** Saves several rules of one group at once — a drag-reorder — with one write-through. */
    suspend fun upsertAll(rules: List<RoutingRule>) = withContext(Dispatchers.IO) {
        if (rules.isEmpty()) return@withContext
        invalidateRules()
        rulesDao.insertAll(rules.map { it.toEntity() })
        rules.map { it.group }.distinct().forEach { writeThroughFor(it) }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val group = rulesDao.allForValidation().firstOrNull { it.id == id }?.groupId?.let(RuleGroup::fromWire)
        invalidateRules()
        rulesDao.delete(id)
        group?.let { writeThroughFor(it) }
    }

    private fun toEntities(blocks: List<RuleBlock>, group: RuleGroup): List<RoutingRuleEntity> =
        blocks.mapNotNull { block ->
            if (block.allKeywords().isEmpty()) {
                Log.w(AppIdentity.LOG_TAG, "Skipping a rule with no keywords")
                return@mapNotNull null
            }
            val action = ActionType.fromWire(block.action)
            if (action == null) {
                Log.w(AppIdentity.LOG_TAG, "Skipping rule '${block.allKeywords().first()}': unknown action '${block.action}'")
                return@mapNotNull null
            }
            if (!group.allows(action)) {
                // A hand-edit put a timer among a vault's own rules, or a note
                // among the common ones. Skipped and said so, rather than
                // filed somewhere the user did not put it.
                Log.w(AppIdentity.LOG_TAG, "Skipping ${action.wireName} rule in the ${group.wireName} group")
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
                groupId = group.wireName,
                // With a vault per gesture, the vault decides; a stray
                // gesture on one of its rules would only stop it firing.
                gesture = block.gestureGroup()?.takeIf { group == RuleGroup.COMMON || !settings.vaultMode.perGesture }?.wireName,
            )
        }

    companion object {
        private const val DEFAULT_ASSET = "default-config.md"

        /**
         * Whether two common lists say the same thing. Order matters only
         * through priority; keyword order within a rule does not.
         */
        internal fun sameRules(a: List<RuleBlock>, b: List<RuleBlock>): Boolean {
            fun norm(list: List<RuleBlock>) = list.map { r ->
                listOf(
                    r.allKeywords().map { it.lowercase() }.sorted().joinToString(","),
                    r.action.lowercase(),
                    r.folder.orEmpty(),
                    Tags.canonical(r.tags).sorted().joinToString(","),
                    MatchType.fromWire(r.matchType).wireName,
                    r.priority.toString(),
                    r.enabled.toString(),
                    r.includeInMoc.toString(),
                    r.stripKeyword.toString(),
                    r.gestureGroup()?.wireName.orEmpty(),
                ).joinToString("|")
            }.sorted()
            return norm(a) == norm(b)
        }
    }
}

fun RoutingRuleEntity.toDomain() = RoutingRule(
    id = id,
    keywords = keywords.split(',').map { it.trim() }.filter { it.isNotBlank() },
    action = ActionType.fromWire(action) ?: ActionType.FILE,
    folder = folder,
    // Canonicalised on the way out as well as in: rows written before tags had
    // one form hold whichever was typed.
    tags = Tags.canonical(tags.split(',')),
    matchType = MatchType.fromWire(matchType),
    priority = priority,
    enabled = enabled,
    includeInMoc = includeInMoc,
    stripKeyword = stripKeyword,
    group = RuleGroup.fromWire(groupId) ?: RuleGroup.COMMON,
    gesture = RuleGroup.fromWire(gesture)?.takeIf { it.isTrigger },
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
    gesture = RuleGroup.fromWire(gesture)?.takeIf { it.isTrigger }?.wireName ?: RuleBlock.GESTURE_ANY,
)

fun RoutingRule.toEntity() = RoutingRuleEntity(
    id = id,
    keywords = keywords.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().joinToString(","),
    action = action.wireName,
    folder = folder,
    tags = Tags.canonical(tags).joinToString(","),
    matchType = matchType.wireName,
    priority = priority,
    enabled = enabled,
    includeInMoc = includeInMoc,
    stripKeyword = stripKeyword,
    groupId = group.wireName,
    gesture = gesture?.wireName,
)
