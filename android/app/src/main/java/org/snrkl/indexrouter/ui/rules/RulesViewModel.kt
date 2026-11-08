package org.snrkl.indexrouter.ui.rules

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.snrkl.indexrouter.IndexRouterApp
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.data.config.ConfigRepository
import org.snrkl.indexrouter.data.config.toDomain
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.RoutingRule
import org.snrkl.indexrouter.routing.RuleGroup

/** A group's header: its display name, and for a gesture the vault it writes to. */
internal data class GroupHeader(val name: String, val vault: String?, val seed: Int)

/** A rule's folder could not be written to, and this is the one to add. */
internal data class FolderWarning(val folder: String)

internal class RulesViewModel(private val app: Application) : ViewModel() {

    private val container get() = (app as IndexRouterApp).container
    private val settings get() = container.settings

    /**
     * Rules in the order the classifier evaluates them: priority ascending,
     * insertion order breaking ties. The list and the classifier must not be
     * able to drift apart, so both sort the same way.
     */
    val rules: StateFlow<List<RoutingRule>> =
        container.database.rules().observeAll()
            .map { entities ->
                entities.map { it.toDomain() }.sortedWith(compareBy({ it.priority }, { it.id }))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private val _folderWarning = MutableStateFlow<FolderWarning?>(null)
    val folderWarning: StateFlow<FolderWarning?> = _folderWarning.asStateFlow()

    /**
     * Folder names already under each gesture's base folder, for the folder
     * dropdown. Names rather than paths: a rule's folder is a single segment.
     */
    private val _folderOptions = MutableStateFlow<Map<RuleGroup, List<String>>>(emptyMap())
    val folderOptions: StateFlow<Map<RuleGroup, List<String>>> = _folderOptions.asStateFlow()

    /** What each group's header says: its name and, for a gesture, its vault. */
    private val _headers = MutableStateFlow<Map<RuleGroup, GroupHeader>>(emptyMap())
    val headers: StateFlow<Map<RuleGroup, GroupHeader>> = _headers.asStateFlow()

    fun refreshFolders() {
        viewModelScope.launch {
            val options = withContext(Dispatchers.IO) {
                RuleGroup.TRIGGERS.associateWith { group ->
                    val profile = settings.profile(group)
                    if (!profile.isAssigned) {
                        emptyList()
                    } else {
                        runCatching {
                            container.vaultStore(group)?.subfolderNames(profile.baseDir).orEmpty()
                        }.getOrDefault(emptySet()).sortedBy { it.lowercase() }
                    }
                }
            }
            _folderOptions.value = options
            _headers.value = RuleGroup.entries.associateWith { group ->
                GroupHeader(
                    seed = settings.groupSeed(group) ?: org.snrkl.indexrouter.ui.theme.GroupPalette.defaultSeed(group),
                    name = settings.groupName(group),
                    vault = if (group.isTrigger) {
                        settings.profile(group).takeIf { it.isAssigned }?.let { profile ->
                            listOfNotNull(
                                profile.vaultRootPath?.substringAfterLast('/'),
                                profile.baseDir.takeIf { it.isNotBlank() },
                            ).joinToString(" › ")
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun messageShown() {
        _messages.value = null
    }

    fun folderWarningShown() {
        _folderWarning.value = null
    }

    fun profile(group: RuleGroup) = settings.profile(group)

    /** `blogPostIdeas` shown as `_RingRing/blogPostIdeas`; still stored as the name. */
    fun resolvedFolder(group: RuleGroup, name: String?): String =
        if (group.isTrigger) settings.profile(group).rulePath(name) else ""

    /**
     * Where a route writes when the rule does not choose — shown rather than
     * left as an empty control.
     */
    fun fixedFolderFor(group: RuleGroup, action: ActionType): String? = when (action) {
        ActionType.TASK -> if (group.isTrigger) settings.profile(group).taskPath() else ""
        ActionType.TIMER, ActionType.CALENDAR, ActionType.DICTATION -> ""
        ActionType.FILE -> null
    }

    /**
     * Saves an edit made on the card.
     *
     * There is no Save button, so a change is committed as it is made. A
     * keyword clash cannot be reported against a field here, so it is said out
     * loud instead — and the rule is left as it was rather than half-applied.
     */
    fun saveInline(rule: RoutingRule) {
        viewModelScope.launch {
            val clash = save(rule)
            if (clash != null) {
                _messages.value = app.getString(R.string.rule_keyword_duplicate, clash)
            }
        }
    }

    /**
     * A new, empty rule in [group], expanded so there is somewhere to type its
     * keywords. Its type is the first one the group allows.
     */
    fun addRule(group: RuleGroup, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            // Priority is one number space shared by every group, so a new rule
            // goes after everything rather than after its own group's last.
            val nextPriority = (rules.value.maxOfOrNull { it.priority } ?: 0) + PRIORITY_STEP
            val blank = RoutingRule(
                keywords = emptyList(),
                action = ActionType.entries.first { group.allows(it) },
                priority = nextPriority,
                group = group,
            )
            // Saved with no keywords on purpose: the card is the editor, so the
            // rule has to exist before it can be filled in. The classifier
            // skips a keywordless rule, so it cannot misroute in the meantime.
            container.config.upsert(blank)
            container.database.rules().all()
                .lastOrNull { it.keywords.isBlank() && it.groupId == group.wireName }
                ?.let { onCreated(it.id) }
        }
    }

    /**
     * The order shown while a drag is in progress.
     *
     * Held separately from the database flow so the cards move under the finger
     * without a write per crossing. Null when nothing is being dragged, and the
     * database order is authoritative again the moment the drag settles.
     */
    private val _dragOrder = MutableStateFlow<List<RoutingRule>?>(null)

    /** What the list should show: the drag order if there is one, else the real one. */
    val displayedRules: StateFlow<List<RoutingRule>> = combine(rules, _dragOrder) { real, dragged ->
        dragged ?: real
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun moveRule(fromKey: Any, toKey: Any) {
        val current = _dragOrder.value ?: rules.value
        val from = current.indexOfFirst { it.id == fromKey }
        val to = current.indexOfFirst { it.id == toKey }
        if (from < 0 || to < 0 || current[from].group != current[to].group) return
        _dragOrder.value = current.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Drags stay inside a group: priorities are numbered per group. */
    fun sameGroup(a: Any, b: Any): Boolean {
        val current = _dragOrder.value ?: rules.value
        val ga = current.firstOrNull { it.id == a }?.group ?: return false
        return current.firstOrNull { it.id == b }?.group == ga
    }

    /**
     * Settles a drag, in one number space shared by every group.
     *
     * Only the moved rule's priority changes: it takes a number between its new
     * neighbours **within its group**. Every other rule keeps its number, so
     * nothing moves relative to the other groups' rules except the one rule
     * that was dragged — and where it lands among them is visible, because
     * each card shows its priority.
     *
     * When there is no whole number between the neighbours, every rule in
     * every group is renumbered 10, 20, 30… in the current overall order.
     * That order is consistent with both gestures' chains, so the renumbering
     * changes no rule's position in either.
     */
    fun commitOrder() {
        val ordered = _dragOrder.value ?: return
        val before = rules.value
        viewModelScope.launch {
            val moved = ordered.firstOrNull { rule ->
                val group = ordered.filter { it.group == rule.group }
                val was = before.filter { it.group == rule.group }
                group.indexOf(rule) != was.indexOfFirst { it.id == rule.id }
            }
            if (moved != null) {
                val group = ordered.filter { it.group == moved.group }
                val at = group.indexOfFirst { it.id == moved.id }
                val above = group.getOrNull(at - 1)?.priority
                val below = group.getOrNull(at + 1)?.priority
                val target = PriorityPlacement.between(above, below)
                if (target != null) {
                    container.config.upsertAll(listOf(moved.copy(priority = target)))
                } else {
                    container.config.upsertAll(PriorityPlacement.renumberAll(before, moved, above, below))
                }
            }
            // Only now let the database order take over again, so the list does
            // not flicker back through the old numbering on the way.
            _dragOrder.value = null
        }
    }

    fun saveAudioFolder(group: RuleGroup, name: String) {
        val clean = name.trim().trim('/').substringAfterLast('/')
        if (clean.isBlank() || !group.isTrigger) return
        settings.profile(group).audioDirName = clean
        viewModelScope.launch { container.config.writeThroughToVault(group) }
    }

    fun setEnabled(rule: RoutingRule, enabled: Boolean) {
        viewModelScope.launch {
            val result = container.config.upsert(rule.copy(enabled = enabled))
            if (result is ConfigRepository.SaveResult.Failed) {
                _messages.value = app.getString(R.string.rule_save_failed, result.reason)
            }
        }
    }

    /**
     * Saves a rule. Returns the duplicate keyword when one clashes, so the
     * editor can mark the field rather than closing over a refusal.
     *
     * Two rules for one word cannot both fire — first match wins — so the clash
     * is refused here rather than by SQLite, whose throw used to kill the app.
     */
    suspend fun save(rule: RoutingRule): String? =
        when (val result = container.config.upsert(rule)) {
            is ConfigRepository.SaveResult.Saved -> {
                warnIfFolderUnreachable(rule)
                null
            }
            is ConfigRepository.SaveResult.DuplicateKeyword -> result.keyword
            is ConfigRepository.SaveResult.Failed -> {
                _messages.value = app.getString(R.string.rule_save_failed, result.reason)
                null
            }
        }

    fun delete(id: Long) {
        viewModelScope.launch { container.config.delete(id) }
    }

    /** The catch-all is a setting, not a keyword-less rule the classifier could match. */
    fun saveDefaultRoute(group: RuleGroup, folderName: String) {
        val folder = folderName.trim().trim('/').substringAfterLast('/')
        if (folder.isBlank() || !group.isTrigger) return
        settings.profile(group).fallbackDirName = folder
        viewModelScope.launch { container.config.writeThroughToVault(group) }
    }

    private companion object {
        /** Gaps of ten, so a rule can be slotted between two by hand. */
        const val PRIORITY_STEP = 10
    }

    /**
     * Under Storage Scopes a rule can be perfectly valid and still have nowhere
     * to write. Say so on save, not at 3am.
     */
    private suspend fun warnIfFolderUnreachable(rule: RoutingRule) {
        if (!rule.group.isTrigger) return
        val profile = settings.profile(rule.group)
        val folder = when (rule.action) {
            ActionType.FILE -> profile.rulePath(rule.folder)
            ActionType.TASK -> profile.taskPath()
            else -> return
        }
        val store = container.vaultStore(rule.group) ?: return
        val access = withContext(Dispatchers.IO) { store.access(folder) }
        if (!access.usable) _folderWarning.value = FolderWarning(folder)
    }
}
