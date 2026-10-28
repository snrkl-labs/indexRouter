package org.snrkl.indexrouter.voice

import android.content.Context
import android.view.inputmethod.InputMethodManager

/**
 * Whether a keyboard's mic key can actually reach this app.
 *
 * Worth a diagnostic rather than an assumption, because the feature has three
 * separate ways to be silently unreachable and they look identical from the
 * outside — the key just does nothing:
 *
 *  1. the input method is not enabled in Android's settings;
 *  2. it is enabled, but its voice subtype did not register, so the system's
 *     shortcut list does not contain it;
 *  3. another voice input method is ahead of it in that list, and the keyboard
 *     takes the first.
 *
 * [check] answers all three by asking the system the same question the keyboard
 * asks: `shortcutInputMethodsAndSubtypes`, which is precisely what HeliBoard's
 * mic key reads before switching.
 */
object VoiceImeStatus {

    data class Status(
        val enabled: Boolean,
        /** This app appears in the system's voice-shortcut list. */
        val reachable: Boolean,
        /** Ours is the one a keyboard taking the first entry would pick. */
        val isFirst: Boolean,
        /** Other input methods competing for the same key. */
        val competitors: List<String>,
    )

    private const val SERVICE = "org.snrkl.indexrouter.voice.VoiceInputMethodService"

    fun check(context: Context): Status {
        val imm = context.getSystemService(InputMethodManager::class.java)
            ?: return Status(enabled = false, reachable = false, isFirst = false, competitors = emptyList())

        val enabled = imm.enabledInputMethodList.orEmpty().any { it.serviceName == SERVICE }

        // The same call the keyboard makes. Ordered, and a keyboard that takes
        // the first entry is why the order matters rather than just membership.
        val shortcuts = runCatching { imm.shortcutInputMethodsAndSubtypes }.getOrNull().orEmpty()
        val ids = shortcuts.keys.map { it.serviceName }
        val reachable = ids.any { it == SERVICE }
        val isFirst = ids.firstOrNull() == SERVICE
        val competitors = shortcuts.keys
            .filter { it.serviceName != SERVICE }
            .map { it.loadLabel(context.packageManager).toString() }

        return Status(enabled = enabled, reachable = reachable, isFirst = isFirst, competitors = competitors)
    }
}
