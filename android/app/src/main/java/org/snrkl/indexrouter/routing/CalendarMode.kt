package org.snrkl.indexrouter.routing

/**
 * How a calendar route hands its recording over.
 *
 * Two mechanisms, because they are good at different things:
 *
 *  - [INSERT] fires the standard `ACTION_INSERT` with a title and description.
 *    Any calendar app can take it and nothing is coupled to a particular one —
 *    but the intent carries only structured extras, so the event lands in the
 *    next default slot with no date or time from what was said.
 *  - [SHARE_TO] sends the whole phrase as plain text to one named app, whose own
 *    language parser turns it into a title, a date and a time. That is the
 *    difference between "lunch with Sinclair tomorrow at noon" arriving as a
 *    title and arriving as an appointment.
 *
 * [SHARE_TO] must name its target. `ACTION_SEND` with `text/plain` is claimed by
 * every messaging app on the phone, so firing it implicitly would put a share
 * sheet full of chat apps in front of a **private voice note** — one stray tap
 * from sending a thought to a contact. That is a safety property, not a
 * convenience one, and it is why the package is not optional here.
 */
enum class CalendarMode(val wireName: String, val displayName: String) {
    INSERT("insert", "New event (any calendar app)"),
    SHARE_TO("share-to", "Send the phrase to one app to parse"),
    ;

    companion object {
        /**
         * Sending the phrase is the default because the parsing is the whole
         * value: a spoken "tomorrow at noon" that arrives as an appointment
         * rather than as text needing two more edits. Falls back to [INSERT]
         * when the named app is not installed, so an install without it still
         * routes.
         */
        val DEFAULT = SHARE_TO

        fun fromWire(value: String?): CalendarMode =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}
