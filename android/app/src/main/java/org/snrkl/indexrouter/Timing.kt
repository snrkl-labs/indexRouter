package org.snrkl.indexrouter

import android.os.SystemClock
import android.util.Log

/**
 * Stopwatch for the paths that are suspected of stalling the interface.
 *
 * Deliberately measuring before optimising: the audio write is the obvious
 * suspect at ~35KB a recording, and the status screen's folder probe — which
 * writes and deletes a file in every folder it checks — is the less obvious one.
 * Guessing between them would mean optimising something that was never the
 * problem.
 *
 * Elapsed real time, not wall clock, so a clock adjustment cannot produce a
 * negative duration.
 */
object Timing {

    const val TAG = "IndexRouterPerf"

    /** Times a block and logs it as `stage=… ms=…`, greppable from logcat. */
    inline fun <T> stage(name: String, detail: String = "", block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            val took = SystemClock.elapsedRealtime() - start
            Log.i(TAG, buildString {
                append("stage=").append(name)
                append(" ms=").append(took)
                if (detail.isNotBlank()) append(' ').append(detail)
            })
        }
    }

    /** Accumulates stage timings across one webhook, for a single summary line. */
    class Run(private val label: String) {
        private val started = SystemClock.elapsedRealtime()
        private val stages = mutableListOf<Pair<String, Long>>()

        inline fun <T> stage(name: String, block: () -> T): T {
            val start = SystemClock.elapsedRealtime()
            try {
                return block()
            } finally {
                record(name, SystemClock.elapsedRealtime() - start)
            }
        }

        fun record(name: String, ms: Long) {
            stages += name to ms
        }

        /** Total elapsed, and logs the breakdown. */
        fun finish(): Long {
            val total = SystemClock.elapsedRealtime() - started
            Log.i(
                TAG,
                "run=$label total=${total}ms " + stages.joinToString(" ") { "${it.first}=${it.second}ms" },
            )
            return total
        }
    }
}
