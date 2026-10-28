package org.snrkl.indexrouter.action

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.snrkl.indexrouter.AppIdentity
import org.snrkl.indexrouter.Timing
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.data.config.ConfigRepository
import org.snrkl.indexrouter.data.db.LogKeeper
import org.snrkl.indexrouter.data.db.RoutingLogDao
import org.snrkl.indexrouter.data.db.RoutingLogEntity
import org.snrkl.indexrouter.data.prefs.SettingsStore
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.CalendarMode
import org.snrkl.indexrouter.routing.KeywordStripper
import org.snrkl.indexrouter.routing.RoutingClassifier
import org.snrkl.indexrouter.routing.RoutingDecision
import org.snrkl.indexrouter.routing.SharePhrase
import org.snrkl.indexrouter.service.DebugStream
import org.snrkl.indexrouter.service.Notifications
import org.snrkl.indexrouter.vault.VaultStore
import org.snrkl.indexrouter.webhook.WebhookPayload
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Everything that happens between "a voice note arrived" and "it is where it
 * belongs".
 *
 * Rule one throughout: never lose a recording silently. Every path — match, no
 * match, or outright failure — ends in a log row, and everything except a clean
 * match also raises a notification.
 */
class RoutingPipeline(
    private val context: Context,
    private val config: ConfigRepository,
    private val logDao: RoutingLogDao,
    private val vaultStore: () -> VaultStore?,
    private val settings: SettingsStore,
    /** Given a folder name, drops a query sample in it if it has none yet. */
    private val onFolderUsed: suspend (String) -> Unit = {},
) {

    private val debug = DebugStream(context, settings)
    private val logKeeper = LogKeeper(logDao) { settings.logRetentionHours }

    suspend fun handle(payload: WebhookPayload) = withContext(Dispatchers.IO) {
        val receivedAt = System.currentTimeMillis()
        val recordedAt = NoteContent.toLocalDateTime(payload.recordedAtMillis) ?: LocalDateTime.now()
        val timing = Timing.Run("webhook")

        debug.event(
            context.getString(R.string.debug_stage_received),
            context.getString(
                R.string.debug_received_detail,
                payload.trigger ?: "—",
                payload.audio?.bytes?.size ?: 0,
                quotable(payload.transcript),
            ),
        )

        val decision = timing.stage("classify") {
            config.ensureInitialised()
            RoutingClassifier(config.rules()).classify(payload.transcript)
        }
        debug.event(
            context.getString(R.string.debug_stage_classified),
            if (decision.matched) {
                context.getString(
                    R.string.debug_classified_detail,
                    if (settings.detailedContentLogging) decision.matchedKeyword.orEmpty() else "—",
                    decision.rule?.action?.displayName.orEmpty(),
                )
            } else {
                context.getString(R.string.debug_classified_no_match)
            },
        )

        // Audio first: if the note-writing step fails, the recording is still on
        // disk and the transcript can be recovered by ear.
        //
        // Except for a dictation, which is explicitly ephemeral: keeping the
        // recording would leave an m4a in the vault for the one route whose
        // whole point is leaving nothing behind. That trades "recoverable by
        // ear" for "leaves no trace", deliberately.
        val audioPath = timing.stage("audio") {
            if (decision.rule?.action == ActionType.DICTATION) null else persistAudio(payload, recordedAt)
        }
        debug.event(
            context.getString(R.string.debug_stage_audio),
            audioPath ?: context.getString(R.string.debug_audio_none),
        )

        val result = try {
            timing.stage("dispatch") {
            when (decision.rule?.action) {
                ActionType.FILE -> fileNote(payload, decision, recordedAt, audioPath)
                ActionType.TASK -> insertTask(payload, decision, recordedAt, audioPath)
                ActionType.TIMER -> startTimer(payload, decision)
                ActionType.CALENDAR -> insertCalendarEvent(payload, decision)
                ActionType.DICTATION -> dictate(payload, decision)
                null -> catchAll(payload, decision, recordedAt, audioPath)
            }
            }
        } catch (t: Throwable) {
            Log.e(AppIdentity.LOG_TAG, "Routing failed", t)
            Notifications.alert(
                context,
                context.getString(R.string.alert_route_failed_title),
                context.getString(R.string.alert_route_failed_body, quotable(payload.transcript), t.message.orEmpty()),
            )
            Outcome(status = STATUS_FAILED, error = t.message)
        }

        debug.event(
            context.getString(R.string.debug_stage_dispatched),
            context.getString(
                R.string.debug_dispatched_detail,
                decision.rule?.action?.displayName ?: context.getString(R.string.route_default_name),
                result.status,
                result.targetPath ?: result.error ?: "—",
            ),
        )

        announceRoute(payload, decision, result)

        // A folder written to for the first time gets its worked example, which
        // is the moment it is most useful.
        timing.stage("sample") {
            result.folderName?.let { runCatching { onFolderUsed(it) } }
        }

        val durationMs = timing.finish()
        logDao.insert(
            RoutingLogEntity(
                durationMs = durationMs,
                receivedAt = receivedAt,
                recordedAt = payload.recordedAtMillis,
                client = payload.client,
                trigger = payload.trigger,
                userAgent = payload.userAgent,
                transcript = payload.transcript,
                matchedRuleId = decision.rule?.id,
                matchedKeyword = decision.matchedKeyword,
                action = decision.rule?.action?.wireName,
                outcome = result.status,
                targetPath = result.targetPath,
                audioPath = audioPath,
                audioBytes = payload.audio?.bytes?.size ?: 0,
                keywordStripped = decision.rule?.stripKeyword ?: false,
                errorMessage = result.error,
            ),
        )
        // Pruned where recordings arrive, which is where the log grows.
        timing.stage("prune") { runCatching { logKeeper.prune() } }
        debug.event(
            context.getString(R.string.debug_stage_logged),
            context.getString(R.string.debug_logged_detail, result.status, durationMs),
        )
    }

    /**
     * Tells the user a route fired, if they asked to be told.
     *
     * Each action type has its own notification channel, so the sound is theirs
     * to choose in Android's settings; this only decides whether anything is
     * posted. Deliberately quiet by default — the app's whole value is that a
     * note files itself without the phone being touched.
     *
     * Skipped when the route already raised its own notification: a timer that
     * could not be launched directly hands the user a tap-to-start notification,
     * and a second one saying "a timer was routed" is noise on top of the thing
     * that actually needs the tap.
     */
    private fun announceRoute(
        payload: WebhookPayload,
        decision: RoutingDecision,
        result: Outcome,
    ) {
        if (result.announced || result.status == STATUS_FAILED) return
        // What was said stays out of the notification unless asked for: a
        // notification carrying it puts the words on the lock screen, readable
        // by whoever picks the phone up.
        val transcript = quotable(payload.transcript)

        val action = decision.rule?.action
        if (action == null) {
            // The catch-all. On by default, because a recording that matched
            // nothing is waiting for a decision rather than filed away.
            if (!settings.notifyFallback) return
            Notifications.route(
                context,
                Notifications.CHANNEL_FALLBACK,
                context.getString(R.string.alert_no_match_title),
                context.getString(R.string.alert_no_match_body, transcript),
            )
            return
        }

        if (!settings.routeNotify(action)) return
        Notifications.route(
            context,
            Notifications.routeChannel(action),
            context.getString(R.string.route_notify_title, action.displayName, decision.routedBy),
            context.getString(
                R.string.route_notify_body,
                transcript,
                result.targetPath ?: context.getString(R.string.route_notify_no_file),
            ),
        )
    }

    private data class Outcome(
        val status: String,
        val targetPath: String? = null,
        val error: String? = null,
        /**
         * The logical folder this went to — a rule's folder or a reserved name —
         * not the path it landed at. With date filing those differ: a note in
         * `_Inbox/2026/08/21` belongs to `_Inbox`, and deriving the name from the
         * path instead produced a folder called "21".
         */
        val folderName: String? = null,
        /**
         * The route has already put a notification in front of the user, so the
         * per-route announcement would be a second one about the same recording.
         */
        val announced: Boolean = false,
    )

    private fun persistAudio(payload: WebhookPayload, recordedAt: LocalDateTime): String? {
        val clip = payload.audio ?: return null
        val store = vaultStore() ?: return null
        return runCatching {
            // Prefix with a sortable stamp: the Pebble app's own filenames are
            // opaque UUIDs and sort meaninglessly in a folder listing. Unique
            // naming matters here — the stamp is minute-resolution, so two notes
            // in the same minute would otherwise silently overwrite one another.
            val stamp = NoteContent.fileName("", recordedAt)
            val extension = clip.fileName.substringAfterLast('.', "m4a")
            val baseName = "$stamp ${clip.fileName.substringBeforeLast('.')}".trim()
            store.writeBytesUnique(
                folderPath = settings.audioPathFor(recordedAt),
                baseName = baseName,
                extension = extension,
                bytes = clip.bytes,
                mime = "audio/mp4",
            )
        }.getOrElse {
            Log.e(AppIdentity.LOG_TAG, "Could not save audio to the vault", it)
            null
        }
    }

    private fun fileNote(
        payload: WebhookPayload,
        decision: RoutingDecision,
        recordedAt: LocalDateTime,
        audioPath: String?,
    ): Outcome {
        val store = vaultStore() ?: return vaultMissing(payload, settings.fallbackPath())
        val folder = settings.rulePath(decision.rule?.folder)
        // Notes strip too now, when their rule says so — it used to be decided
        // by action type, which could not be right for everyone's rules.
        val text = stripKeyword(payload.transcript, decision)
        val note = NoteContent.build(
            transcript = text,
            routedBy = decision.routedBy,
            tags = decision.rule?.tags.orEmpty(),
            createdOn = LocalDate.now(),
            recordedAt = recordedAt,
            audioFileName = audioPath?.substringAfterLast('/'),
            trigger = payload.trigger,
        )
        val path = store.writeTextUnique(
            folderPath = folder,
            baseName = NoteContent.fileName(text, recordedAt),
            contents = note,
        )
        return Outcome(
            STATUS_SUCCESS,
            targetPath = path,
            folderName = decision.rule?.folder?.takeIf { it.isNotBlank() } ?: settings.fallbackDirName,
        )
    }

    /**
     * Writes the task as its own file under the reserved task folder.
     *
     * It used to insert a line into the live daily note, which lost races against
     * Obsidian's mobile sync — that file is edited by other devices and sync only
     * runs while Obsidian is open. A new file is a single atomic write with no
     * existing content to merge, and the daily note now pulls tasks in with a
     * query instead. Nothing outside this app's own folder is written any more.
     */
    private fun insertTask(
        payload: WebhookPayload,
        decision: RoutingDecision,
        recordedAt: LocalDateTime,
        audioPath: String?,
    ): Outcome {
        val store = vaultStore() ?: return vaultMissing(payload, settings.taskPath())
        val today = LocalDate.now()
        val text = stripKeyword(payload.transcript, decision)
        // A rule's own tags win, so a "work task" route and a "home task" route
        // can carry different hashtags. The setting is the fallback, not the law.
        val tags = decision.rule?.tags?.takeIf { it.isNotEmpty() } ?: settings.taskTags
        val line = TaskLineFormatter.format(
            transcript = text,
            createdOn = today,
            tags = tags,
            includeCreatedDate = settings.taskCreatedDate,
        )

        val note = NoteContent.build(
            transcript = line,
            routedBy = decision.routedBy,
            // Frontmatter tags mirror the task line, so a query can find these
            // files by tag as well as by path.
            tags = tags,
            createdOn = today,
            recordedAt = recordedAt,
            audioFileName = audioPath?.substringAfterLast('/'),
            trigger = payload.trigger,
        )

        val path = store.writeTextUnique(
            folderPath = settings.taskPath(),
            baseName = NoteContent.fileName(text, recordedAt),
            contents = note,
        )
        return Outcome(STATUS_SUCCESS, targetPath = path, folderName = settings.taskDirName)
    }

    private fun startTimer(payload: WebhookPayload, decision: RoutingDecision): Outcome {
        val seconds = DurationParser.parseSeconds(payload.transcript)
            ?: run {
                Notifications.alert(
                    context,
                    context.getString(R.string.alert_timer_no_length_title),
                    context.getString(R.string.alert_timer_no_length_body, quotable(payload.transcript)),
                )
                return Outcome(STATUS_FAILED, error = "no duration found in transcript")
            }

        // "Alarm for ten minutes" labels the timer "for ten minutes": the
        // keyword told the app where to send this, and repeating it back is
        // noise on a watch face. Notes keep theirs, which leaves the filename
        // convention untouched.
        val label = stripKeyword(payload.transcript, decision).trim().take(60)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            // Honoured at the target app's discretion; Alarm Rock is ours, so
            // whether this fires headlessly is its call, not an assumption here.
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }

        val outcome = ActivityLauncher.launchOrNotify(
            context = context,
            intent = intent,
            // The timer's own channel, not the shared alerts one: this is the
            // notification that starts the timer, so it is the one that most
            // deserves a sound of its own.
            channelId = Notifications.actionChannel(ActionType.TIMER),
            notificationTitle = context.getString(R.string.alert_timer_tap_title, DurationParser.describe(seconds)),
            notificationBody = context.getString(R.string.alert_timer_tap_body, label),
        )
        return when (outcome) {
            // "Opened the timer app" — not "a timer is running". Firing an
            // intent is all this route can know, and for timer and calendar the
            // log row is the only record the recording existed at all, so it
            // must not imply more than happened.
            ActivityLauncher.Outcome.LAUNCHED -> Outcome(STATUS_SUCCESS, error = HANDED_OFF)
            ActivityLauncher.Outcome.NOTIFIED ->
                Outcome(STATUS_SUCCESS, error = AWAITING_TAP, announced = true)
            ActivityLauncher.Outcome.FAILED -> Outcome(STATUS_FAILED, error = "no app handled ACTION_SET_TIMER")
        }
    }

    private fun insertCalendarEvent(payload: WebhookPayload, decision: RoutingDecision): Outcome {
        val text = stripKeyword(payload.transcript, decision).trim()

        // Handing the whole phrase to an app that parses language gets a title,
        // a date and a time out of "lunch with Sinclair tomorrow at noon".
        // ACTION_INSERT cannot: it takes structured extras only, so the event
        // lands in the next default slot with nothing from what was said.
        if (settings.calendarMode == CalendarMode.SHARE_TO) {
            shareToCalendarApp(text, payload)?.let { return it }
            // Falls through to the standard insert when the named app is not
            // installed, so an install without it still routes somewhere.
        }

        val intent = Intent(Intent.ACTION_INSERT).apply {
            // Both together, and via setDataAndType: setData() clears the type
            // and setType() clears the data, so assigning them one after the
            // other silently leaves only whichever was set last.
            //
            // The type is needed at all because Intent.resolveActivity matches
            // on getType(), which is null when only the URI is set — so a
            // filter declaring vnd.android.cursor.dir/event need not match, and
            // the route could report "no calendar app" on a device that has one.
            setDataAndType(CalendarContract.Events.CONTENT_URI, CALENDAR_EVENT_MIME)
            putExtra(CalendarContract.Events.TITLE, text.take(80))
            putExtra(CalendarContract.Events.DESCRIPTION, payload.transcript.trim())
        }
        val outcome = ActivityLauncher.launchOrNotify(
            context = context,
            intent = intent,
            channelId = Notifications.actionChannel(ActionType.CALENDAR),
            notificationTitle = context.getString(R.string.alert_calendar_tap_title),
            notificationBody = quotable(payload.transcript),
        )
        return when (outcome) {
            ActivityLauncher.Outcome.FAILED ->
                Outcome(STATUS_FAILED, error = "no calendar app handled ACTION_INSERT")
            ActivityLauncher.Outcome.NOTIFIED -> Outcome(STATUS_SUCCESS, error = AWAITING_TAP, announced = true)
            // The calendar app opens with the event pre-filled; it is not saved
            // until the user saves it, and nothing reports back when they do.
            else -> Outcome(STATUS_SUCCESS, error = HANDED_OFF)
        }
    }

    /**
     * Sends the phrase as plain text to one named app, for its own parser.
     *
     * The package is set explicitly and that is not negotiable: `ACTION_SEND`
     * with `text/plain` is claimed by every messaging app on the phone, so an
     * implicit send would put a share sheet of chat apps in front of a private
     * voice note — one stray tap from sending a thought to a contact. Naming
     * the package also means no chooser at all, so nothing has to be set as a
     * default by hand.
     *
     * Returns null when the app is not installed, leaving the caller to fall
     * back to the standard insert rather than losing the recording.
     */
    private fun shareToCalendarApp(text: String, payload: WebhookPayload): Outcome? {
        val target = settings.calendarSharePackage.trim()
        if (target.isEmpty()) return null

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(target)
            // EXTRA_TEXT, one line, and no trailing full stop — see
            // SharePhrase: that one character turns a parsed 12:00 lunch into
            // an all-day event, and the transcriber adds it to every phrase.
            putExtra(Intent.EXTRA_TEXT, SharePhrase.forParsing(text))
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            Log.w(AppIdentity.LOG_TAG, "Calendar share target '$target' is not installed; using ACTION_INSERT")
            return null
        }

        val outcome = ActivityLauncher.launchOrNotify(
            context = context,
            intent = intent,
            channelId = Notifications.actionChannel(ActionType.CALENDAR),
            notificationTitle = context.getString(R.string.alert_calendar_tap_title),
            notificationBody = quotable(payload.transcript),
        )
        return when (outcome) {
            // Not a failure of this mode — the recording is still routable by
            // the standard insert, so the caller is left to try it.
            ActivityLauncher.Outcome.FAILED -> null
            ActivityLauncher.Outcome.NOTIFIED ->
                Outcome(STATUS_SUCCESS, error = AWAITING_TAP, announced = true)
            ActivityLauncher.Outcome.LAUNCHED -> Outcome(STATUS_SUCCESS, error = HANDED_OFF_PARSED)
        }
    }

    /**
     * A dictation: logged, and otherwise left alone.
     *
     * Nothing is written. The text is picked up from the log by the voice input
     * method when the keyboard's mic key asks for it, so the log row *is* the
     * delivery mechanism rather than a record of one — which is why this route
     * still logs even though it produces no file.
     *
     * The keyword is stripped for the same reason it is on a timer: nobody
     * wants the word "dictation" committed into their text field.
     */
    private fun dictate(payload: WebhookPayload, decision: RoutingDecision): Outcome {
        val text = stripKeyword(payload.transcript, decision).trim()
        if (text.isBlank()) {
            return Outcome(STATUS_FAILED, error = "nothing said after the keyword")
        }
        return Outcome(STATUS_SUCCESS, error = DICTATION_QUEUED)
    }

    private fun catchAll(
        payload: WebhookPayload,
        decision: RoutingDecision,
        recordedAt: LocalDateTime,
        audioPath: String?,
    ): Outcome {
        val store = vaultStore() ?: return vaultMissing(payload, settings.fallbackPath())
        val note = NoteContent.build(
            transcript = payload.transcript,
            routedBy = RoutingDecision.DEFAULT_ROUTE,
            tags = emptyList(),
            createdOn = LocalDate.now(),
            recordedAt = recordedAt,
            audioFileName = audioPath?.substringAfterLast('/'),
            trigger = payload.trigger,
        )
        val path = store.writeTextUnique(
            folderPath = settings.fallbackPathFor(recordedAt),
            baseName = NoteContent.fileName(payload.transcript, recordedAt),
            contents = note,
        )
        // The notification for this route is raised by announceRoute, on the
        // fallback channel, so its sound is the user's to choose like any other
        // route's. It still defaults to on.
        return Outcome(STATUS_FALLBACK, targetPath = path, folderName = settings.fallbackDirName)
    }

    /**
     * Names the folder that could not be reached. Under Storage Scopes the fix is
     * always "add this folder", so a message that does not say which folder is
     * one the user cannot act on.
     */
    private fun vaultMissing(payload: WebhookPayload, folder: String): Outcome {
        Notifications.alert(
            context,
            context.getString(R.string.alert_no_vault_title),
            context.getString(R.string.alert_no_vault_body, folder, quotable(payload.transcript)),
        )
        return Outcome(STATUS_FAILED, error = "no access to '$folder'")
    }

    /**
     * "Task buy milk" becomes "buy milk" — when the rule says so.
     *
     * Per rule rather than per action type, which is how it used to be decided.
     * Only ever removed from the start of the phrase, so a keyword used
     * mid-sentence stays as content.
     */
    /**
     * What may be repeated back in a notification.
     *
     * Off by default. The alternative to quoting is not silence — the
     * notification still says which route fired and that something arrived —
     * it is simply that the words are not put where a passer-by reads them.
     */
    private fun quotable(transcript: String): String =
        if (settings.detailedContentLogging) {
            transcript.trim().take(160)
        } else {
            context.getString(R.string.content_withheld)
        }

    private fun stripKeyword(transcript: String, decision: RoutingDecision): String =
        if (decision.rule?.stripKeyword == false) {
            transcript.trim()
        } else {
            KeywordStripper.strip(transcript, decision.matchedKeyword)
        }

    private companion object {
        const val CALENDAR_EVENT_MIME = "vnd.android.cursor.dir/event"

        /**
         * Recorded against a timer or calendar row so the log does not read as
         * a guarantee. Neither route can know whether the handing-off worked:
         * an intent is fired and nothing reports back.
         */
        /** Said plainly, because this route leaves nothing else behind. */
        const val DICTATION_QUEUED = "held for the keyboard — nothing written"

        const val HANDED_OFF = "opened the handling app — not confirmed saved"

        /**
         * Distinguished from [HANDED_OFF] because it says more: the phrase went
         * to a parser, so the date and time came from what was said rather than
         * from a default slot. Still not confirmed saved — that is a tap.
         */
        const val HANDED_OFF_PARSED = "sent the phrase to be parsed — not confirmed saved"
        const val AWAITING_TAP = "notification posted — needs a tap to fire"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FALLBACK = "FALLBACK"
        const val STATUS_FAILED = "FAILED"
    }
}
