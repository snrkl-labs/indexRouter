package org.snrkl.indexrouter.webhook

import java.security.MessageDigest

/**
 * Shared-secret check on the Authorization header.
 *
 * Loopback is not a trust boundary — any app on the device can POST to
 * 127.0.0.1 — so every request is authenticated.
 *
 * Accepts the bare secret or a `Bearer <secret>` form, because the Pebble app's
 * Authorization Header field is free text and either is a reasonable thing to
 * paste. Comparison is constant-time.
 *
 * Pure logic, no Android types: covered by JVM unit tests.
 */
object WebhookAuth {

    sealed interface Result {
        data object Ok : Result
        /** No secret generated yet — the app is not configured, so nothing is accepted. */
        data object NotConfigured : Result
        data object Missing : Result
        data object Mismatch : Result
    }

    fun check(headerValue: String?, expectedSecret: String?): Result {
        if (expectedSecret.isNullOrBlank()) return Result.NotConfigured
        if (headerValue.isNullOrBlank()) return Result.Missing
        val presented = headerValue.trim().removePrefix("Bearer ").trim()
        val a = presented.toByteArray(Charsets.UTF_8)
        val b = expectedSecret.toByteArray(Charsets.UTF_8)
        return if (MessageDigest.isEqual(a, b)) Result.Ok else Result.Mismatch
    }
}
