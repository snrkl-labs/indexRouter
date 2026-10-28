package org.snrkl.indexrouter.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.snrkl.indexrouter.AppIdentity
import org.snrkl.indexrouter.IndexRouterApp
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.webhook.WebhookServer

/**
 * Keeps the loopback receiver alive.
 *
 * Foreground service type is `specialUse`, not `dataSync`: nothing is being
 * transferred, and `dataSync` acquires a 6h-per-24h runtime cap the moment
 * targetSdk reaches 35 — fatal for a listener that must be up whenever the ring
 * is worn. `specialUse` has no such cap. The Play Console reviews the
 * justification string in the manifest; this app is sideloaded, so nothing gates
 * it here.
 */
class RouterForegroundService : LifecycleService() {

    private var server: WebhookServer? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundNotice(getString(R.string.fgs_status_starting))

        val app = applicationContext as IndexRouterApp
        // A rejection never reaches the pipeline, so this is the only place it
        // can be reported — and "the Pebble app's secret does not match" is
        // exactly the sort of thing the stream exists to make visible.
        val debug = DebugStream(this, app.container.settings)
        server = WebhookServer(
            port = app.container.settings.serverPort,
            secretProvider = { app.container.secrets.read() },
            onPayload = { payload -> app.container.pipeline.handle(payload) },
            onRejected = { reason ->
                Log.w(AppIdentity.LOG_TAG, "Rejected webhook: $reason")
                debug.event(getString(R.string.debug_stage_rejected), reason)
            },
        ).also { it.start() }

        lifecycleScope.launch {
            startForegroundNotice(
                getString(R.string.fgs_status_listening, app.container.settings.serverPort),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Restart if the system kills us: a dropped listener means dropped notes.
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun startForegroundNotice(detail: String) {
        val notification = Notifications.serviceNotification(this, detail)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(Notifications.ID_SERVICE, notification)
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, RouterForegroundService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.e(AppIdentity.LOG_TAG, "Could not start receiver service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RouterForegroundService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            @Suppress("DEPRECATION")
            return context.getSystemService(android.app.ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == RouterForegroundService::class.java.name }
        }
    }
}
