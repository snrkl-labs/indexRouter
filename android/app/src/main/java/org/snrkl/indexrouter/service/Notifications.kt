package org.snrkl.indexrouter.service

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.snrkl.indexrouter.AppIdentity
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.ui.MainActivity
import org.snrkl.indexrouter.ui.nav.Destinations

/**
 * Every notification the app raises, and the channels they arrive on.
 *
 * The channels are the feature, not an implementation detail. Since Android 8 a
 * notification's sound is a property of its channel and cannot be set per
 * notification, so "a different alert per route" means **a channel per route**,
 * with the sound chosen in Android's own settings. That is why there is one
 * channel per action type rather than one channel and six titles.
 *
 * The weights differ deliberately:
 *  - [CHANNEL_SERVICE] is the silent, permanent "I am listening" notice the
 *    foreground service requires.
 *  - [CHANNEL_ALERTS] carries the failures that must never pass silently — a
 *    config the app could not read, a folder it could not write to, a timer
 *    with no duration in it. Not configurable: a capture tool losing a note
 *    quietly is the worst failure mode there is.
 *  - The per-route channels and [CHANNEL_FALLBACK] carry successful routing,
 *    and are off by default apart from the fallback. They exist at
 *    IMPORTANCE_DEFAULT so that a sound is *possible* — a channel created LOW
 *    offers no sound setting at all, and the point of these is that the user can map
 *    one.
 *  - [CHANNEL_DEBUG] is the milestone stream: LOW, grouped, and verbose by
 *    design, for working out why a webhook did not do what was expected.
 */
object Notifications {

    const val CHANNEL_SERVICE = "webhook_service"

    /** Failures. Channel id unchanged since v0.1.0, so a chosen sound survives. */
    const val CHANNEL_ALERTS = "routing_alerts"
    const val CHANNEL_FALLBACK = "route_fallback"
    const val CHANNEL_DEBUG = "debug_stream"

    /** Groups the route channels together in Android's notification settings. */
    private const val GROUP_ROUTES = "routes"

    /** Collapses the milestone stream under one summary rather than filling the shade. */
    private const val SHADE_GROUP_DEBUG = "org.snrkl.indexrouter.debug"

    const val ID_SERVICE = 1
    private const val ID_DEBUG_SUMMARY = 2

    /**
     * Ids come from fixed-size rings, not a running counter — see [IdRing] for
     * what an unbounded counter costs. Sized so that every ring being full at
     * once still leaves room under Android's 50-per-app cap:
     * `2 + 12 + 12 + 12 + 8 = 46`.
     */
    private val alertIds = IdRing(first = 100, size = 12)
    private val routeIds = IdRing(first = 1_000, size = 12)
    private val debugIds = IdRing(first = 5_000, size = 12)

    /** For ActivityLauncher's tap-to-fire notifications, which are also capped. */
    internal val launcherIds = IdRing(first = 500, size = 8)

    /** The channel a route's confirmation arrives on. */
    fun routeChannel(action: ActionType): String = "route_${action.wireName}"

    /**
     * The channel a route's **actionable** notification arrives on — the
     * tap-to-fire one a timer or calendar insert falls back to when Android
     * will not let the app start an activity from the background.
     *
     * Separate from [routeChannel] for two reasons, and the second is the
     * important one:
     *
     *  - These used to all land on [CHANNEL_ALERTS], so every timer and every
     *    calendar insert arrived on one channel shared with failure alerts.
     *    Per-action sounds were therefore impossible for exactly the two routes
     *    whose notification is the one you have to act on.
     *  - It must not be silenceable by the per-route *confirmation* preference.
     *    That toggle means "tell me when this route fires"; muting it should
     *    never take away the tap that actually starts the timer.
     */
    fun actionChannel(action: ActionType): String = "action_${action.wireName}"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val name = AppIdentity.displayName(context)

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, name, NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.fgs_channel_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "$name alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.alerts_channel_description)
            },
        )

        // One group so the six route channels read as a set in Android's
        // settings, rather than six loose entries among the app's own.
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_ROUTES, context.getString(R.string.channel_group_routes)),
        )
        ActionType.entries.forEach { action ->
            manager.createNotificationChannel(
                NotificationChannel(
                    routeChannel(action),
                    context.getString(R.string.channel_route, action.displayName),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    group = GROUP_ROUTES
                    description = context.getString(R.string.channel_route_description, action.displayName)
                },
            )
        }
        // Only the routes that can need a tap: a note or a task is written
        // directly and never waits on the user.
        listOf(ActionType.TIMER, ActionType.CALENDAR).forEach { action ->
            manager.createNotificationChannel(
                NotificationChannel(
                    actionChannel(action),
                    context.getString(R.string.channel_action, action.displayName),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    group = GROUP_ROUTES
                    description = context.getString(R.string.channel_action_description, action.displayName)
                },
            )
        }

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FALLBACK,
                context.getString(R.string.channel_route_fallback),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                group = GROUP_ROUTES
                description = context.getString(R.string.channel_route_fallback_description)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DEBUG, context.getString(R.string.channel_debug), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_debug_description)
                setShowBadge(false)
            },
        )
    }

    fun serviceNotification(context: Context, detail: String) =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle(context.getString(R.string.fgs_notification_title, AppIdentity.displayName(context)))
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .build()

    fun alert(context: Context, title: String, body: String) {
        post(
            context,
            id = alertIds.take(),
            notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    /**
     * A route announcing that it fired.
     *
     * Opens the log rather than the main screen: the log row is the record of
     * what happened to this recording, which is what someone tapping a routing
     * notification is asking to see.
     */
    fun route(context: Context, channelId: String, title: String, body: String) {
        post(
            context,
            id = routeIds.take(),
            notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setContentIntent(openLog(context))
                .build(),
        )
    }

    /**
     * One pipeline milestone.
     *
     * Grouped under a summary so that a burst — five or so events per recording
     * — collapses into one shade entry instead of burying everything else. The
     * summary is re-posted with each event, which is what keeps the group
     * intact as it grows.
     */
    fun debug(context: Context, stage: String, detail: String) {
        val id = debugIds.take()
        post(
            context,
            id = id,
            notification = NotificationCompat.Builder(context, CHANNEL_DEBUG)
                .setContentTitle(stage)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setSilent(true)
                .setGroup(SHADE_GROUP_DEBUG)
                .setContentIntent(openLog(context))
                .build(),
        )
        post(
            context,
            id = ID_DEBUG_SUMMARY,
            notification = NotificationCompat.Builder(context, CHANNEL_DEBUG)
                .setContentTitle(context.getString(R.string.debug_summary_title))
                .setSmallIcon(R.drawable.ic_notification)
                .setSilent(true)
                .setGroup(SHADE_GROUP_DEBUG)
                .setGroupSummary(true)
                .setContentIntent(openLog(context))
                .build(),
        )
    }

    /** POST_NOTIFICATIONS may be denied; never let a missing toast kill the routing path. */
    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openLog(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Destinations.EXTRA_START_DESTINATION, Destinations.LOG),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
