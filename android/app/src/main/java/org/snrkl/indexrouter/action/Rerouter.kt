package org.snrkl.indexrouter.action

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.snrkl.indexrouter.AppIdentity
import org.snrkl.indexrouter.data.db.RoutingLogDao
import org.snrkl.indexrouter.data.db.RoutingLogEntity
import org.snrkl.indexrouter.data.prefs.SettingsStore
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.vault.VaultStore
import java.time.LocalDate

/**
 * Manual reclassification of something already routed.
 *
 * The classifier is deliberately **not** re-run: the user's choice is the authority,
 * so this goes straight to the output chain for the chosen destination. It is
 * only tractable because every action now writes its own file — moving a file is
 * a rename, where the old daily-note insertion would have meant finding and
 * editing a fragment inside a file owned by something else.
 *
 * Timer and calendar are not re-route targets: they produce no file, so there is
 * nothing to move, and firing a timer for a recording made hours ago would be
 * worse than useless.
 */
class Rerouter(
    private val settings: SettingsStore,
    private val vaultStore: () -> VaultStore?,
    private val logDao: RoutingLogDao,
) {

    sealed interface Target {
        data object Task : Target
        data object Fallback : Target
        data class Folder(val name: String) : Target
    }

    sealed interface Result {
        data class Moved(val from: String, val to: String) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun reroute(entry: RoutingLogEntity, target: Target): Result = withContext(Dispatchers.IO) {
        val store = vaultStore() ?: return@withContext Result.Failed("no vault access")
        val from = entry.targetPath
            ?: return@withContext Result.Failed("this item has no file to move")

        val contents = store.readText(from)
            ?: return@withContext Result.Failed("the file is no longer at $from")

        val destinationFolder = when (target) {
            is Target.Task -> settings.taskPath()
            is Target.Fallback -> settings.fallbackPath()
            is Target.Folder -> settings.rulePath(target.name)
        }
        if (from.substringBeforeLast('/') == destinationFolder) {
            return@withContext Result.Failed("already in ${destinationFolder.substringAfterLast('/')}")
        }

        val rewritten = NoteRewriter.rewriteForReroute(
            contents = contents,
            becomingTask = target is Target.Task,
            createdOn = LocalDate.now(),
            tags = settings.taskTags,
        )

        val to = try {
            store.writeTextUnique(
                folderPath = destinationFolder,
                baseName = from.substringAfterLast('/').removeSuffix(".md"),
                contents = rewritten,
            )
        } catch (t: Throwable) {
            Log.e(AppIdentity.LOG_TAG, "Re-route write failed", t)
            return@withContext Result.Failed(t.message ?: "could not write to $destinationFolder")
        }

        // Only remove the original once the copy is safely written: a failure
        // here leaves a duplicate, which is recoverable. The reverse is not.
        if (!store.delete(from)) {
            Log.w(AppIdentity.LOG_TAG, "Re-routed copy written but original remains at $from")
        }

        logDao.markRerouted(
            id = entry.id,
            action = when (target) {
                is Target.Task -> ActionType.TASK.wireName
                else -> ActionType.FILE.wireName
            },
            targetPath = to,
        )
        Result.Moved(from, to)
    }
}
