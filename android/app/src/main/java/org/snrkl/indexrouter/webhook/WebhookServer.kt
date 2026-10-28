package org.snrkl.indexrouter.webhook

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.snrkl.indexrouter.AppIdentity

/**
 * Loopback-only HTTP receiver for the Pebble app's webhook.
 *
 * Binds 127.0.0.1 exclusively — there is no LAN listener to find, because both
 * apps are on the same handset. GrapheneOS still gates this behind its per-app
 * Network permission: it enforces at socket creation, loopback included.
 *
 * Lives inside a foreground service; see RouterForegroundService for why.
 */
class WebhookServer(
    private val port: Int,
    private val secretProvider: () -> String?,
    private val onPayload: suspend (WebhookPayload) -> Unit,
    private val onRejected: (reason: String) -> Unit = {},
) {

    private var engine: ApplicationEngine? = null

    val isRunning: Boolean get() = engine != null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, host = LOOPBACK, port = port) {
            routing {
                // Liveness probe — no secret required, returns nothing sensitive.
                // Used by the in-app status card and by emulator smoke tests.
                get(PATH_HEALTH) {
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }

                post(PATH_WEBHOOK) { handleWebhook() }
                // The Pebble app's URL field is hand-typed; accepting the root path
                // too avoids a silent 404 costing a voice note over a missing suffix.
                post("/") { handleWebhook() }
            }
        }.also { it.start(wait = false) }
        Log.i(AppIdentity.LOG_TAG, "Webhook server listening on $LOOPBACK:$port")
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
        Log.i(AppIdentity.LOG_TAG, "Webhook server stopped")
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.handleWebhook() {
        when (val auth = WebhookAuth.check(call.request.header("Authorization"), secretProvider())) {
            is WebhookAuth.Result.Ok -> Unit
            is WebhookAuth.Result.NotConfigured -> {
                onRejected("no shared secret generated yet")
                call.respondText(
                    """{"ok":false,"error":"not configured"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return
            }
            else -> {
                // Deliberately vague to the caller, specific in our own log.
                onRejected("auth ${auth::class.simpleName}")
                call.respondText(
                    """{"ok":false,"error":"unauthorized"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                return
            }
        }

        val payload = try {
            parseMultipart()
        } catch (t: Throwable) {
            Log.e(AppIdentity.LOG_TAG, "Multipart parse failed", t)
            onRejected("parse failure: ${t.message}")
            call.respondText(
                """{"ok":false,"error":"bad request"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }

        // Answer the Pebble app before doing any vault work: it is waiting on this
        // response, and a slow SAF write must not look like a delivery failure.
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
        onPayload(payload)
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.parseMultipart(): WebhookPayload {
        var transcript: String? = null
        var recordedAt: Long? = null
        var client: String? = null
        var audio: WebhookPayload.AudioClip? = null

        call.receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> when (part.name) {
                    FIELD_TRANSCRIPTION -> transcript = part.value
                    FIELD_RECORDED_AT -> recordedAt = part.value.trim().toLongOrNull()
                    FIELD_CLIENT -> client = part.value
                    else -> Log.d(AppIdentity.LOG_TAG, "Ignoring unknown form field ${part.name}")
                }

                is PartData.FileItem -> if (part.name == FIELD_AUDIO) {
                    val bytes = part.streamProvider().use { it.readBytes() }
                    // The part header claims video/mp4; it is an m4a. Trust the
                    // extension the Pebble app supplies rather than the MIME type.
                    audio = WebhookPayload.AudioClip(
                        fileName = part.originalFileName ?: "recording.m4a",
                        bytes = bytes,
                    )
                }

                else -> Unit
            }
            part.dispose()
        }

        return WebhookPayload(
            transcript = transcript.orEmpty(),
            recordedAtMillis = recordedAt,
            client = client,
            trigger = call.request.header(HEADER_TRIGGER),
            userAgent = call.request.header("User-Agent"),
            audio = audio,
        )
    }

    companion object {
        const val DEFAULT_PORT = 8787
        const val LOOPBACK = "127.0.0.1"
        const val PATH_WEBHOOK = "/webhook"
        const val PATH_HEALTH = "/health"

        private const val FIELD_TRANSCRIPTION = "transcription"
        private const val FIELD_RECORDED_AT = "recordedAt"
        private const val FIELD_CLIENT = "client"
        private const val FIELD_AUDIO = "audio"
        private const val HEADER_TRIGGER = "x-index-trigger"
    }
}
