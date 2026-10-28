package org.snrkl.indexrouter.service

import android.content.Context
import android.util.Log
import org.snrkl.indexrouter.AppIdentity
import org.snrkl.indexrouter.data.prefs.SettingsStore

/**
 * The optional milestone stream: each step of handling a webhook, posted as its
 * own notification.
 *
 * Why it exists. When a voice note does not end up where it should, the
 * question is *which* step went wrong — did the webhook arrive at all, was it
 * rejected at the door, did it classify to the wrong rule, did the vault write
 * fail — and the routing log only records the answer after the fact. On a
 * handset there is no logcat to watch. This puts the same milestones somewhere
 * readable while it happens.
 *
 * Off by default, and silent and low-importance when on, because it is verbose:
 * five or so events per recording. Its notifications are grouped, so the shade
 * collapses them rather than burying everything else.
 *
 * Every event is logged regardless of the setting — the log line costs nothing
 * and is what a `logcat` session on the emulator reads.
 */
class DebugStream(
    private val context: Context,
    private val settings: SettingsStore,
) {

    val enabled: Boolean get() = settings.debugStream

    fun event(stage: String, detail: String) {
        Log.i(AppIdentity.LOG_TAG, "debug stage=$stage $detail")
        if (!enabled) return
        // Never let the diagnostic tool be the thing that breaks the pipeline
        // it is diagnosing.
        runCatching { Notifications.debug(context, stage, detail) }
    }
}
