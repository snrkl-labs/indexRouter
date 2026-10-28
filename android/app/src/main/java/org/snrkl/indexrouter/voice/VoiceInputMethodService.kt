package org.snrkl.indexrouter.voice

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.snrkl.indexrouter.IndexRouterApp
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.data.prefs.SettingsStore
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.KeywordStripper
import java.util.concurrent.TimeUnit

/**
 * Hands the ring's most recent transcription to whatever text field is focused.
 *
 * **Why an input method and not a recogniser.** HeliBoard's mic key does not
 * fire `ACTION_RECOGNIZE_SPEECH` and does not bind a `RecognitionService`. Its
 * `KeyCode.VOICE_INPUT` calls `switchToShortcutIme()`, which reads
 * `InputMethodManager.shortcutInputMethodsAndSubtypes` and then
 * `switchInputMethod(id, subtype)` — so the mic key **switches the active input
 * method** to an enabled IME declaring a subtype with `mode="voice"`. Being one
 * of those IMEs is the only way to be on the end of that key. (Read from
 * HeliBoard's source, 2026-09-18: `LatinIME.onEvent` and
 * `RichInputMethodManager.switchToShortcutIme`.)
 *
 * This is why it costs nothing else on the phone: it is **not** the system
 * recogniser, so ordinary voice typing is untouched. The only thing that
 * changes is what the mic key reaches.
 *
 * **It never listens.** The ring already did that. This commits text and hands
 * the keyboard straight back, so in normal use the switch is invisible — the
 * words simply appear.
 *
 * The view is built in code rather than with Compose: an IME window has no
 * lifecycle or saved-state owner attached, which `ComposeView` requires and
 * crashes without.
 */
class VoiceInputMethodService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as IndexRouterApp).container
    private lateinit var settings: SettingsStore

    private var statusView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        settings = container.settings
    }

    /**
     * An input method must show something. This one has nothing to ask and
     * nothing to wait for, so it is a single line saying what happened — and on
     * the common path it is gone before it can be read, because the keyboard is
     * handed back the moment the text is committed.
     */
    override fun onCreateInputView(): View =
        TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            text = getString(R.string.voice_working)
            statusView = this
        }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        commitMostRecent()
    }

    private fun commitMostRecent() {
        statusView?.text = getString(R.string.voice_working)
        scope.launch {
            val cutoffMillis = TimeUnit.MINUTES.toMillis(settings.voiceCutoffMinutes.toLong())
            val entries = withContext(Dispatchers.IO) {
                container.database.log()
                    .recentTranscripts(ActionType.DICTATION.wireName, settings.voiceBufferSize)
                    // The log keeps the raw transcript, keyword and all, because
                    // that is what traceability means. The keyword is stripped
                    // here instead — committing "Dictation buy the tickets" into
                    // someone's email is exactly what this route exists to avoid.
                    .map { TranscriptBuffer.Entry(KeywordStripper.strip(it.transcript, it.matchedKeyword), it.receivedAt) }
            }

            when (val result = TranscriptBuffer.pick(entries, System.currentTimeMillis(), cutoffMillis)) {
                is TranscriptBuffer.Result.Commit -> {
                    currentInputConnection?.commitText(result.text, 1)
                    statusView?.text = getString(R.string.voice_committed, result.text)
                    // Straight back to the keyboard that asked, so the switch
                    // reads as "the text appeared" rather than as a keyboard
                    // change the user has to undo.
                    handBack()
                }

                // Nothing to commit. The reason is shown rather than the key
                // appearing to do nothing — but only briefly, because this is
                // now the active input method and it is not a keyboard: leaving
                // it up strands the user with no way to type. So it says why,
                // then hands back.
                TranscriptBuffer.Result.Empty -> {
                    statusView?.text = getString(R.string.voice_nothing_dictated)
                    handBackAfterReading()
                }

                is TranscriptBuffer.Result.TooOld -> {
                    statusView?.text = getString(
                        R.string.voice_too_old,
                        describe(result.ageMillis),
                        settings.voiceCutoffMinutes,
                    )
                    handBackAfterReading()
                }
            }
        }
    }

    private fun describe(ageMillis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ageMillis)
        return when {
            minutes < 1 -> getString(R.string.voice_age_seconds)
            minutes < 60 -> getString(R.string.voice_age_minutes, minutes)
            else -> getString(R.string.voice_age_hours, TimeUnit.MILLISECONDS.toHours(ageMillis))
        }
    }

    /**
     * Long enough to read one line, short enough not to feel stuck.
     *
     * This is not a keyboard, so it cannot simply stay: whoever tapped the mic
     * key still wants to type.
     */
    private fun handBackAfterReading() {
        scope.launch {
            delay(REASON_VISIBLE_MILLIS)
            handBack()
        }
    }

    private fun handBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            requestHideSelf(0)
        }
    }

    private companion object {
        const val REASON_VISIBLE_MILLIS = 2_500L
    }

    override fun onDestroy() {
        scope.cancel()
        statusView = null
        super.onDestroy()
    }
}
