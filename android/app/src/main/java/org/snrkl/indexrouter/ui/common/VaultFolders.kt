package org.snrkl.indexrouter.ui.common

import android.content.Context
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.data.prefs.SettingsStore
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.RoutingRule

/**
 * Every folder the current configuration needs, with the reason it needs it.
 *
 * Under Storage Scopes "the vault" is not one permission: the app's own files
 * live in one folder, and every file rule needs its own target. Both the status
 * screen and the settings screen ask this question, so it is answered in one
 * place — two lists that could drift apart would show two different answers to
 * "is the vault working".
 */
internal object VaultFolders {

    /** Reserved folders, present on every install regardless of the rules. */
    fun core(context: Context, settings: SettingsStore): List<Pair<String, String>> = listOf(
        settings.baseDir to context.getString(R.string.vault_reason_config),
        settings.audioPath() to context.getString(R.string.vault_reason_audio),
        settings.taskPath() to context.getString(R.string.vault_reason_tasks),
        settings.fallbackPath() to context.getString(R.string.vault_reason_inbox),
    )

    /** One folder per file rule, named after the rule that needs it. */
    fun forRules(
        context: Context,
        settings: SettingsStore,
        rules: List<RoutingRule>,
    ): List<Pair<String, String>> = rules.mapNotNull { rule ->
        if (rule.action != ActionType.FILE) return@mapNotNull null
        rule.folder?.takeIf { it.isNotBlank() }?.let { folder ->
            settings.rulePath(folder) to
                context.getString(R.string.vault_reason_rule, rule.keywords.firstOrNull().orEmpty())
        }
    }
}
