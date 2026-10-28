package org.snrkl.indexrouter.action

import java.time.LocalDateTime

/**
 * Splits a folder into dated subfolders, so it stays navigable.
 *
 * The inbox and the audio folder take a file per recording and never lose one:
 * left flat, they become thousands of entries that are slow to open on a phone
 * and impossible to skim. Filing by date is what the vault already does with its
 * own daily notes.
 *
 * Not applied to rule folders, whose whole point is that the user chose where
 * things go, nor to the task folder — tasks are read through queries that would
 * not notice the nesting, and a flat folder of open tasks is still browsable.
 *
 * No Android types: covered by JVM unit tests.
 */
object DateFiling {

    enum class Depth(val wireName: String) {
        NONE("none"),
        MONTH("month"),
        DAY("day"),
        ;

        companion object {
            fun fromWire(value: String?): Depth =
                entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) } ?: DAY
        }
    }

    /**
     * Appends the dated path to a folder: `_Inbox` becomes `_Inbox/2026/08/21`.
     * Dataview and wikilinks are unaffected — queries recurse, and audio embeds
     * resolve by name rather than path.
     */
    fun folderFor(basePath: String, at: LocalDateTime, depth: Depth): String = when (depth) {
        Depth.NONE -> basePath
        Depth.MONTH -> "%s/%04d/%02d".format(basePath, at.year, at.monthValue)
        Depth.DAY -> "%s/%04d/%02d/%02d".format(basePath, at.year, at.monthValue, at.dayOfMonth)
    }
}
