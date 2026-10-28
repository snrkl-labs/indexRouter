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

    /**
     * A notification tapped from the shade names the screen it should open.
     * Route notifications open the log, which is the record of what happened to
     * that recording.
     */
    const val EXTRA_START_DESTINATION = "start_destination"
}
