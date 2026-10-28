package org.snrkl.indexrouter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.snrkl.indexrouter.data.prefs.SettingsStore

/**
 * The app is never opened in normal use — a voice note arrives at a device that
 * has been sitting in a pocket since the last reboot. Without this, the first
 * note after every restart is lost, and silently.
 *
 * Also restarts after the app itself is updated (sideloaded APK replace).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> if (SettingsStore(context).receiverEnabled) RouterForegroundService.start(context)
        }
    }
}
