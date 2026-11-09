package org.snrkl.indexrouter.action

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.snrkl.indexrouter.AppIdentity
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.Timing
import org.snrkl.indexrouter.data.config.ConfigRepository
import org.snrkl.indexrouter.data.db.DeliveryLedger
import org.snrkl.indexrouter.data.db.LogKeeper
import org.snrkl.indexrouter.data.db.RoutingLogDao
import org.snrkl.indexrouter.data.db.RoutingLogEntity
import org.snrkl.indexrouter.data.prefs.SettingsStore
import org.snrkl.indexrouter.data.prefs.VaultProfile
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.CalendarMode
import org.snrkl.indexrouter.routing.KeywordStripper
import org.snrkl.indexrouter.routing.RoutingClassifier
import org.snrkl.indexrouter.routing.RoutingDecision
import org.snrkl.indexrouter.routing.RuleGroup
import org.snrkl.indexrouter.routing.SharePhrase
import org.snrkl.indexrouter.routing.TriggerHeader
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
 *
 * Rule two, since there are two vaults: **never fail over**. A gesture's
 * recording is written to that gesture's vault or nowhere. If the vault cannot
 * be reached, nothing is written to the other one under any circumstance —
 * a note filed in the wrong vault is worse than a lost one.
 * Only this gesture's [VaultStore] is ever obtained, so the other vault is not
 * so much avoided as unreachable from here.
 */
class RoutingPipeline(
    private val context: Context,
    private val config: ConfigRepository,
    private val logDao: RoutingLogDao,
    private val ledger: DeliveryLedger,
    private val vaultStore: (RuleGroup) -> VaultStore?,
    private val settings: SettingsStore,
    /** Given a gesture and a folder name, drops a query sample in it if it has none yet. */
    private val onFolderUsed: suspend (RuleGroup, String) -> Unit = { _, _ -> },
) {

    private val debug = DebugStream(context, settings)
    private val logKeeper = LogKeeper(logDao) { settings.logRetentionHours }

    /**
     * Handles one delivery and says what to answer.
     *
     * The answer is the point of doing the work before answering: 503 makes
     * Pebble resend the delivery, which is how a recording survives a vault
     * that is briefly unreachable without this app storing it anywhere. See
     * [route] for exactly when that is allowed.
     *
     * @return the HTTP status for the Pebble app.
     */
    suspend fun handle(payload: WebhookPayload): Int = withContext(Dispatchers.IO) {
        val receivedAt = System.currentTimeMillis()
        debug.event(
            context.getString(R.string.debug_stage_received),
            context.getString(
                R.string.debug_received_detail,
                payload.trigger ?: "—",
                payload.audio?.bytes?.size ?: 0,
                quotable(payload.transcript),
            ),
            gesture = gestureOf(payload),
        )

        val group = when (val trigger = TriggerHeader.parse(payload.trigger)) {
            is TriggerHeader.Gesture -> trigger.group
            TriggerHeader.TestEvent -> {
                // Answered so the Pebble app shows its test as delivered; written
                // to the log so it can be seen to have arrived; nothing else.
                insertRow(payload, receivedAt, STATUS_TEST, error = "test event — nothing routed")
                return@withContext HTTP_OK
            }
            else -> {
                // Never guess a vault. Refused outright, and Pebble does not
                // resend a 4xx, so this is the one notification it gets.
                val shown = (trigger as? TriggerHeader.Unknown)?.value ?: "none"
                Notifications.alert(
                    context,
                    context.getString(R.string.alert_trigger_refused_title),
                    context.getString(R.string.alert_trigger_refused_body, shown),
                    gesture = gestureOf(payload),
                )
                insertRow(payload, receivedAt, STATUS_REJECTED, error = "no usable X-Index-Trigger ($shown)")
                return@withContext HTTP_BAD_REQUEST
            }
        }

        val attempt = when (val decision = ledger.begin(payload.deliveryId)) {
            DeliveryLedger.Decision.AlreadyDone -> {
                debug.event(context.getString(R.string.debug_stage_logged), "repeat delivery — already filed", gesture = gestureOf(payload))
                return@withContext HTTP_OK
            }
            // The same id is being handled by another request this moment.
            // Asking Pebble to come back is safe: the first one either
            // finishes, making the retry a no-op, or leaves nothing behind.
            DeliveryLedger.Decision.InProgress -> return@withContext HTTP_UNAVAILABLE
            is DeliveryLedger.Decision.Process -> decision
        }

        val outcome = try {
            route(payload, group, receivedAt, attempt)
        } catch (t: Throwable) {
            // Anything unforeseen is final: a retry of a bug is the same bug.
            Log.e(AppIdentity.LOG_TAG, "Routing failed", t)
            ledger.finish(payload.deliveryId, retry = false, logRowId = attempt.previous?.logRowId, notified = true)
            HTTP_OK
        }
        runCatching { logKeeper.prune() }
        runCatching { ledger.prune() }
        outcome
    }

    private suspend fun route(
        payload: WebhookPayload,
        group: RuleGroup,
        receivedAt: Long,
        attempt: DeliveryLedger.Decision.Process,
    ): Int {
        val recordedAt = NoteContent.toLocalDateTime(payload.recordedAtMillis) ?: LocalDateTime.now()
        val timing = Timing.Run("webhook")
        val profile = settings.profile(group)
        val store = if (profile.isAssigned) vaultStore(group) else null
        val alreadyNotified = attempt.previous?.notified == true
        val lastAttempt = attempt.attempt >= DeliveryLedger.MAX_ATTEMPTS

        val decision = timing.stage("classify") {
            config.ensureInitialised()
            RoutingClassifier(config.rules()).classify(payload.transcript, group)
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
            gesture = gestureOf(payload),
        )

        val action = decision.rule?.action
        // The default route counts: an unmatched recording is a note in the inbox.
        val needsVault = action == null || action == ActionType.FILE || action == ActionType.TASK
        // A dictation keeps nothing, recording included; it is the one route
        // whose point is leaving no trace. A timer keeps nothing either: the
        // log row is its record, and a recording of "ten minutes" filed with
        // no note to hang from is clutter, not evidence.
        val keepsAudio = payload.audio != null && action !in ROUTES_WITHOUT_RECORDING
        val audioFolder = profile.audioPathFor(recordedAt)
        val noteFolder = when (action) {
            ActionType.FILE -> profile.rulePath(decision.rule?.folder)
            ActionType.TASK -> profile.taskPath()
            null -> profile.fallbackPathFor(recordedAt)
            else -> null
        }

        // Pre-flight: before anything is written, every folder this delivery
        // needs is probed with a real write. A vault route that cannot be
        // completed writes nothing and asks Pebble to resend, so a retry never
        // finds half a delivery behind it.
        val unreachable: String? = timing.stage("preflight") {
            when {
                !needsVault -> null
                store == null -> UNASSIGNED
                else -> listOfNotNull(noteFolder, audioFolder.takeIf { keepsAudio })
                    .firstOrNull { !store.access(it).usable }
            }
        }
        var notified = false
        if (needsVault && unreachable != null) {
            notified = alertUnreachable(group, unreachable, payload, alreadyNotified, lastAttempt)
            return finishRow(
                payload, group, decision, receivedAt, timing, attempt,
                Outcome(STATUS_FAILED, error = unreachableError(group, unreachable, lastAttempt)),
                audioPath = null, retry = !lastAttempt, notified = notified,
            )
        }

        // Recording first, so a note that then fails to write is still
        // recoverable by ear.
        val audioPath = timing.stage("audio") {
            if (keepsAudio && store != null) persistAudio(store, profile, payload, recordedAt) else null
        }
        if (keepsAudio && audioPath == null && !needsVault) {
            // A common route still fires when the gesture's vault is down: a
            // timer does not need a vault. Only the recording is lost, and
            // that is said out loud.
            notified = true
            Notifications.alert(
                context,
                context.getString(R.string.alert_audio_not_kept_title),
                context.getString(
                    R.string.alert_audio_not_kept_body,
                    settings.groupName(group),
                    action?.displayName.orEmpty(),
                ),
                gesture = gestureOf(payload),
            )
        }
        debug.event(
            context.getString(R.string.debug_stage_audio),
            audioPath ?: context.getString(R.string.debug_audio_none),
            gesture = gestureOf(payload),
        )

        var result = try {
            timing.stage("dispatch") {
                when (action) {
                    ActionType.FILE -> fileNote(store!!, profile, payload, decision, recordedAt, audioPath)
                    ActionType.TASK -> insertTask(store!!, profile, payload, decision, recordedAt, audioPath)
                    ActionType.TIMER -> startTimer(payload, decision)
                    ActionType.CALENDAR -> insertCalendarEvent(payload, decision)
                    ActionType.DICTATION -> dictate(payload, decision)
                    null -> catchAll(store!!, profile, payload, recordedAt, audioPath)
                }
            }
        } catch (t: Throwable) {
            Log.e(AppIdentity.LOG_TAG, "Routing failed", t)
            // Did the vault go away mid-write, or is this a bug? Only the first
            // is worth a resend, and only if nothing else has happened yet.
            val vanished = needsVault && store != null && noteFolder != null && !store.access(noteFolder).usable
            if (vanished && !lastAttempt) {
                // Undo the recording so the resend does not leave a second
                // copy under a collision suffix.
                audioPath?.let { runCatching { store!!.delete(it) } }
                notified = alertUnreachable(group, noteFolder!!, payload, alreadyNotified, lastAttempt = false)
                return finishRow(
                    payload, group, decision, receivedAt, timing, attempt,
                    Outcome(STATUS_FAILED, error = unreachableError(group, noteFolder, lastAttempt = false)),
                    audioPath = null, retry = true, notified = notified,
                )
            }
            if (!alreadyNotified) {
                Notifications.alert(
                    context,
                    context.getString(R.string.alert_route_failed_title),
                    context.getString(R.string.alert_route_failed_body, quotable(payload.transcript), t.message.orEmpty()),
                    gesture = gestureOf(payload),
                )
            }
            notified = true
            Outcome(status = STATUS_FAILED, error = t.message)
        }
        if (keepsAudio && audioPath == null && !needsVault && result.status == STATUS_SUCCESS) {
            result = result.copy(error = listOfNotNull(result.error, "recording not kept: vault unreachable").joinToString("; "))
        }

        debug.event(
            context.getString(R.string.debug_stage_dispatched),
            context.getString(
                R.string.debug_dispatched_detail,
                action?.displayName ?: context.getString(R.string.route_default_name),
                result.status,
                result.targetPath?.let(::quotablePath) ?: result.error ?: "—",
            ),
            gesture = gestureOf(payload),
        )

        announceRoute(payload, decision, result)

        timing.stage("sample") {
            result.folderName?.let { runCatching { onFolderUsed(group, it) } }
        }
        // A vault that was written just now is reachable; if its common copy
        // fell behind while it was not, this is the moment to catch it up.
        if (needsVault && result.status != STATUS_FAILED) runCatching { config.catchUpStaleVaults() }

        return finishRow(
            payload, group, decision, receivedAt, timing, attempt, result,
            audioPath = audioPath, retry = false, notified = notified,
        )
    }

    /**
     * Says a gesture's vault could not be used — once per delivery, not once
     * per resend. Returns whether a notification was raised.
     */
    private fun alertUnreachable(
        group: RuleGroup,
        folder: String,
        payload: WebhookPayload,
        alreadyNotified: Boolean,
        lastAttempt: Boolean,
    ): Boolean {
        val name = settings.groupName(group)
        when {
            lastAttempt -> Notifications.alert(
                context,
                context.getString(R.string.alert_group_gave_up_title, name),
                context.getString(R.string.alert_group_gave_up_body, name, quotable(payload.transcript)),
                gesture = gestureOf(payload),
            )
            alreadyNotified -> return false
            folder == UNASSIGNED -> Notifications.alert(
                context,
                context.getString(R.string.alert_group_unassigned_title, name),
                context.getString(R.string.alert_group_unassigned_body, name),
                gesture = gestureOf(payload),
            )
            else -> Notifications.alert(
                context,
                context.getString(R.string.alert_group_unreachable_title, name),
                context.getString(R.string.alert_group_unreachable_body, name, folder, quotable(payload.transcript)),
                gesture = gestureOf(payload),
            )
        }
        return true
    }

    private fun unreachableError(group: RuleGroup, folder: String, lastAttempt: Boolean): String {
        val what = if (folder == UNASSIGNED) {
            "no vault set for ${group.wireName}"
        } else {
            "no access to '$folder'"
        }
        return if (lastAttempt) "$what — not filed, Pebble's last attempt" else "$what — Pebble will resend"
    }

    /**
     * Writes or updates the delivery's one log row, closes the ledger entry,
     * and returns what to answer. A resend updates the row the first attempt
     * wrote rather than adding another.
     */
    private suspend fun finishRow(
        payload: WebhookPayload,
        group: RuleGroup,
        decision: RoutingDecision,
        receivedAt: Long,
        timing: Timing.Run,
        attempt: DeliveryLedger.Decision.Process,
        result: Outcome,
        audioPath: String?,
        retry: Boolean,
        notified: Boolean,
    ): Int {
        val durationMs = timing.finish()
        val row = RoutingLogEntity(
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
            ruleGroup = (decision.rule?.group ?: group).wireName,
            vaultGroup = group.wireName,
            deliveryId = payload.deliveryId,
        )
        val previousRow = attempt.previous?.logRowId?.let { logDao.byId(it) }
        val rowId = if (previousRow != null) {
            logDao.update(row.copy(id = previousRow.id))
            previousRow.id
        } else {
            logDao.insert(row)
        }
        ledger.finish(payload.deliveryId, retry = retry, logRowId = rowId, notified = notified)
        debug.event(
            context.getString(R.string.debug_stage_logged),
            context.getString(R.string.debug_logged_detail, result.status, durationMs),
            gesture = gestureOf(payload),
        )
        return if (retry) HTTP_UNAVAILABLE else HTTP_OK
    }

    /** A row for a delivery that was never routed: a test event or a refusal. */
    private suspend fun insertRow(payload: WebhookPayload, receivedAt: Long, status: String, error: String) {
        logDao.insert(
            RoutingLogEntity(
                receivedAt = receivedAt,
                recordedAt = payload.recordedAtMillis,
                client = payload.client,
                trigger = payload.trigger,
                userAgent = payload.userAgent,
                transcript = payload.transcript,
                outcome = status,
                audioBytes = payload.audio?.bytes?.size ?: 0,
                errorMessage = error,
                deliveryId = payload.deliveryId,
            ),
        )
    }

    /**
     * Tells the user a route fired, if they asked to be told.
     *
     * Each action type has its own notification channel, so the sound is theirs
     * to choose in Android's settings; this only decides whether anything is
     * posted. Skipped when the route already raised its own notification.
     */
    private fun announceRoute(
        payload: WebhookPayload,
        decision: RoutingDecision,
        result: Outcome,
    ) {
        if (result.announced || result.status == STATUS_FAILED) return
        // What was said stays out of the notification unless asked for: a
        // notification carrying it puts the words on the lock screen.
        val transcript = quotable(payload.transcript)

        val action = decision.rule?.action
        if (action == null) {
            if (!settings.notifyFallback) return
            Notifications.route(
                context,
                Notifications.CHANNEL_FALLBACK,
                context.getString(R.string.alert_no_match_title),
                context.getString(R.string.alert_no_match_body, transcript),
                gesture = gestureOf(payload),
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
                result.targetPath?.let(::quotablePath) ?: context.getString(R.string.route_notify_no_file),
            ),
            gesture = gestureOf(payload),
        )
    }

    private data class Outcome(
        val status: String,
        val targetPath: String? = null,
        val error: String? = null,
        /**
         * The logical folder this went to — a rule's folder or a reserved name —
         * not the path it landed at. With date filing those differ.
         */
        val folderName: String? = null,
        /** The route already put a notification in front of the user. */
        val announced: Boolean = false,
    )

    private fun persistAudio(
        store: VaultStore,
        profile: VaultProfile,
        payload: WebhookPayload,
        recordedAt: LocalDateTime,
    ): String? {
        val clip = payload.audio ?: return null
        return runCatching {
            // Prefix with a sortable stamp: the Pebble app's own filenames are
            // opaque UUIDs and sort meaninglessly in a folder listing.
            val stamp = NoteContent.fileName("", recordedAt)
            val extension = clip.fileName.substringAfterLast('.', "m4a")
            val baseName = "$stamp ${clip.fileName.substringBeforeLast('.')}".trim()
            store.writeBytesUnique(
                folderPath = profile.audioPathFor(recordedAt),
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
        store: VaultStore,
        profile: VaultProfile,
        payload: WebhookPayload,
        decision: RoutingDecision,
        recordedAt: LocalDateTime,
        audioPath: String?,
    ): Outcome {
        val folder = profile.rulePath(decision.rule?.folder)
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
            folderName = decision.rule?.folder?.takeIf { it.isNotBlank() } ?: profile.fallbackDirName,
        )
    }

    /**
     * Writes the task as its own file under the reserved task folder — a single
     * atomic write with nothing to merge, where inserting into the live daily
     * note lost races against Obsidian's mobile sync.
     */
    private fun insertTask(
        store: VaultStore,
        profile: VaultProfile,
        payload: WebhookPayload,
        decision: RoutingDecision,
        recordedAt: LocalDateTime,
        audioPath: String?,
    ): Outcome {
        val today = LocalDate.now()
        val text = stripKeyword(payload.transcript, decision)
        // A rule's own tags win; the vault's setting is the fallback.
        val tags = decision.rule?.tags?.takeIf { it.isNotEmpty() } ?: profile.taskTags
        val line = TaskLineFormatter.format(
            transcript = text,
            createdOn = today,
            tags = tags,
            includeCreatedDate = profile.taskCreatedDate,
        )
        val note = NoteContent.build(
            transcript = line,
            routedBy = decision.routedBy,
            tags = tags,
            createdOn = today,
            recordedAt = recordedAt,
            audioFileName = audioPath?.substringAfterLast('/'),
            trigger = payload.trigger,
        )
        val path = store.writeTextUnique(
            folderPath = profile.taskPath(),
            baseName = NoteContent.fileName(text, recordedAt),
            contents = note,
        )
        return Outcome(STATUS_SUCCESS, targetPath = path, folderName = profile.taskDirName)
    }

    private fun startTimer(payload: WebhookPayload, decision: RoutingDecision): Outcome {
        val seconds = DurationParser.parseSeconds(payload.transcript)
            ?: run {
                Notifications.alert(
                    context,
                    context.getString(R.string.alert_timer_no_length_title),
                    context.getString(R.string.alert_timer_no_length_body, quotable(payload.transcript)),
                    gesture = gestureOf(payload),
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
            gesture = gestureOf(payload),
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
            gesture = gestureOf(payload),
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
            gesture = gestureOf(payload),
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
        store: VaultStore,
        profile: VaultProfile,
        payload: WebhookPayload,
        recordedAt: LocalDateTime,
        audioPath: String?,
    ): Outcome {
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
            folderPath = profile.fallbackPathFor(recordedAt),
            baseName = NoteContent.fileName(payload.transcript, recordedAt),
            contents = note,
        )
        return Outcome(STATUS_FALLBACK, targetPath = path, folderName = profile.fallbackDirName)
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

    /**
     * The name of the gesture a delivery came from, for a notification's
     * header. Null for a delivery with no usable trigger.
     */
    private fun gestureOf(payload: WebhookPayload): String? =
        (TriggerHeader.parse(payload.trigger) as? TriggerHeader.Gesture)?.let { settings.groupName(it.group) }

    /**
     * Where a note went, as a notification may show it. The file name is made
     * from the opening words of what was said, so unless detailed content
     * logging is on, only the folder is shown.
     */
    private fun quotablePath(path: String): String = when {
        settings.detailedContentLogging -> path
        '/' in path -> context.getString(R.string.content_withheld_path, path.substringBeforeLast('/'))
        else -> context.getString(R.string.content_withheld_file)
    }

    private fun stripKeyword(transcript: String, decision: RoutingDecision): String =
        if (decision.rule?.stripKeyword == false) {
            transcript.trim()
        } else {
            KeywordStripper.strip(transcript, decision.matchedKeyword)
        }

    companion object {
        /** Routes that never save the recording. Calendar still does, for now. */
        val ROUTES_WITHOUT_RECORDING = setOf(ActionType.DICTATION, ActionType.TIMER)

        /** Stands in for a folder name when the gesture has no vault at all. */
        private const val UNASSIGNED = "\u0000unassigned"

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

        /** Pebble's "Send test event": logged as such, nothing routed. */
        const val STATUS_TEST = "TEST"

        /** No usable `X-Index-Trigger`: refused, nothing routed. */
        const val STATUS_REJECTED = "REJECTED"

        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAVAILABLE = 503
    }
}
