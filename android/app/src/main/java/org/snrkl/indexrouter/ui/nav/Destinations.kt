package org.snrkl.indexrouter.ui.nav

/**
 * The navigation graph.
 *
 * Four screens on one activity, replacing the three activities this app used to
 * have. The routes are named here rather than as string literals at call sites.
 */
internal object Destinations {
    const val MAIN = "main"
    const val RULES = "rules"
    const val LOG = "log"
    const val SETTINGS = "settings"

    /** Optionally opened on one gesture: `triggers?group=<wire name>`. */
    const val TRIGGERS = "triggers?group={group}"
    fun triggers(group: org.snrkl.indexrouter.routing.RuleGroup? = null) =
        if (group == null) "triggers" else "triggers?group=${group.wireName}"

    /**
     * A notification tapped from the shade names the screen it should open.
     * Route notifications open the log, which is the record of what happened to
     * that recording.
     */
    const val EXTRA_START_DESTINATION = "start_destination"
}
