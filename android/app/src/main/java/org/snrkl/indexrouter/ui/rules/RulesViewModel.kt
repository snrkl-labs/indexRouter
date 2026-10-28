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
     * Folder names already under the base folder, for the folder dropdown.
     *
     * Names rather than paths: a rule's folder is a single segment, and handing
     * the UI paths is how the old vault-root semantics leaked back in once
     * already. The dropdown resolves them for display only.
     */
    private val _folderOptions = MutableStateFlow<List<String>>(emptyList())
    val folderOptions: StateFlow<List<String>> = _folderOptions.asStateFlow()

    fun refreshFolders() {
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) {
                container.vaultStore()?.subfolderNames(settings.baseDir).orEmpty()
            }
            _folderOptions.value = names.sortedBy { it.lowercase() }
        }
    }

    fun messageShown() {
        _messages.value = null
    }

    fun folderWarningShown() {
        _folderWarning.value = null
    }

    val fallbackDirName: String get() = settings.fallbackDirName
    val fallbackPath: String get() = settings.fallbackPath()
    val taskPath: String get() = settings.taskPath()
    val audioPath: String get() = settings.audioPath()
    val baseDir: String get() = settings.baseDir
    val vaultRootPath: String? get() = settings.vaultRootPath
    val taskTags: List<String> get() = settings.taskTags
    val audioDirName: String get() = settings.audioDirName

    /** `blogPostIdeas` shown as `_RingRing/blogPostIdeas`; still stored as the name. */
    fun resolvedFolder(name: String?): String = settings.rulePath(name)

    /**
     * Where a route writes when the rule does not choose — shown rather than
     * left as an empty control, because "the same place every time" is still
     * worth knowing without going hunting for it.
     */
    fun fixedFolderFor(action: ActionType): String? = when (action) {
        ActionType.TASK -> settings.taskPath()
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

    /** A new, empty rule, expanded so there is somewhere to type its keywords. */
    fun addRule(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val nextPriority = (rules.value.maxOfOrNull { it.priority } ?: 0) + PRIORITY_STEP
            val blank = RoutingRule(
                keywords = emptyList(),
                action = ActionType.FILE,
                priority = nextPriority,
            )
            // Saved with no keywords on purpose: the card is the editor, so the
            // rule has to exist before it can be filled in. The classifier
            // skips a keywordless rule, so it cannot misroute in the meantime.
            container.config.upsert(blank)
            rules.value.lastOrNull { it.keywords.isEmpty() }?.let { onCreated(it.id) }
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

    fun moveRule(from: Int, to: Int) {
        val current = _dragOrder.value ?: rules.value
        if (from !in current.indices || to !in current.indices) return
        _dragOrder.value = current.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * Renumbers priorities to 10, 20, 30… in the order the cards now sit.
     *
     * Gaps of ten on purpose: the numbers exist so the routing table can be
     * re-ordered by hand in Obsidian, and consecutive numbers would leave no
     * room to slot a rule between two others without renumbering the file. The
     * handle is only the convenient way to do it on the phone.
     */
    fun commitOrder() {
        val ordered = _dragOrder.value ?: return
        viewModelScope.launch {
            ordered.forEachIndexed { index, rule ->
                val priority = (index + 1) * PRIORITY_STEP
                if (rule.priority != priority) {
                    container.config.upsert(rule.copy(priority = priority))
                }
            }
            // Only now let the database order take over again, so the list does
            // not flicker back through the old numbering on the way.
            _dragOrder.value = null
        }
    }

    fun saveAudioFolder(name: String) {
        val clean = name.trim().trim('/').substringAfterLast('/')
        if (clean.isBlank()) return
        settings.audioDirName = clean
        viewModelScope.launch { container.config.writeThroughToVault() }
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
    fun saveDefaultRoute(folderName: String) {
        val folder = folderName.trim().trim('/').substringAfterLast('/')
        if (folder.isBlank()) return
        settings.fallbackDirName = folder
        viewModelScope.launch { container.config.writeThroughToVault() }
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
        val folder = when (rule.action) {
            ActionType.FILE -> settings.rulePath(rule.folder)
            ActionType.TASK -> settings.taskPath()
            else -> return
        }
        val store = container.vaultStore() ?: return
        val access = withContext(Dispatchers.IO) { store.access(folder) }
        if (!access.usable) _folderWarning.value = FolderWarning(folder)
    }
}
