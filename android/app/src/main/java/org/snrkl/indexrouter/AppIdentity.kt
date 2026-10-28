package org.snrkl.indexrouter

import android.content.Context

/**
 * The one place the app asks "what am I called?".
 *
 * The working name is a placeholder — the shipping name is decided later — so no
 * user-facing identity string may be written as a literal anywhere else. Display
 * name resolves from [R.string.app_name]; install identity from the Gradle
 * `indexrouter.applicationId` property, surfaced here via [BuildConfig].
 *
 * Log tags are the one deliberate exception to "derive everything": Android caps
 * tags at 23 chars and they must be constant for `logcat` filtering, so [LOG_TAG]
 * is a fixed internal identifier, never shown to the user.
 */
object AppIdentity {

    const val LOG_TAG: String = "IndexRouter"

    /** Display name, as shown in notifications, titles and any user-facing copy. */
    fun displayName(context: Context): String = context.getString(R.string.app_name)

    /** Install identity. Changing this makes it a different app to Android. */
    val applicationId: String = BuildConfig.APPLICATION_ID
}
