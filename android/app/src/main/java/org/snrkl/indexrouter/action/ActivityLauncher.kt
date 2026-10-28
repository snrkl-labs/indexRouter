package org.snrkl.indexrouter.action

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.snrkl.indexrouter.AppIdentity
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.service.Notifications

/**
 * Starts an activity from the background — or, when Android will not allow it,
 * hands the user a one-tap notification instead.
 *
 * Since Android 10, a background app cannot launch an activity, and a foreground
 * service does not exempt it. The launch is not refused with an exception: it is
 * dropped, logged by the framework and nothing happens. For a capture tool that
 * is the worst possible failure — the note is gone and nobody knows.
 *
 * So the decision is made up front rather than hoped for: with the overlay
 * permission ("Display over other apps") the app is exempt and fires directly;
 * without it, the intent is wrapped in a notification the user taps. Both paths
 * are recorded in the routing log.
 */
object ActivityLauncher {

    // One value for both the request code and the notification id, drawn from
    // a ring: a burst of recordings launches concurrently, and two sharing a
    // request code means FLAG_UPDATE_CURRENT replaces the first intent — so
    // tapping the first notification would fire the second's timer. The ring
    // bounds it, because these count against Android's 50-per-app cap like
    // everything else; wrapping replaces the oldest pending tap, whose timer
    // was for a recording made long enough ago to be stale anyway.

    enum class Outcome { LAUNCHED, NOTIFIED, FAILED }

    fun canLaunchFromBackground(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun launchOrNotify(
        context: Context,
        intent: Intent,
        channelId: String,
        notificationTitle: String,
        notificationBody: String,
    ): Outcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (intent.resolveActivity(context.packageManager) == null) {
            Log.w(AppIdentity.LOG_TAG, "No app handles ${intent.action}")
            return Outcome.FAILED
        }

        if (canLaunchFromBackground(context)) {
            return runCatching {
                context.startActivity(intent)
                Outcome.LAUNCHED
            }.getOrElse {
                Log.w(AppIdentity.LOG_TAG, "Direct launch refused, falling back to notification", it)
                notify(context, intent, channelId, notificationTitle, notificationBody)
            }
        }
        return notify(context, intent, channelId, notificationTitle, notificationBody)
    }

    private fun notify(
        context: Context,
        intent: Intent,
        channelId: String,
        title: String,
        body: String,
    ): Outcome {
        val code = Notifications.launcherIds.take()
        val pending = PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(code, notification)
            Outcome.NOTIFIED
        }.getOrElse { Outcome.FAILED }
    }

    /** Sends the user to the system screen where the overlay exemption is granted. */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
}
