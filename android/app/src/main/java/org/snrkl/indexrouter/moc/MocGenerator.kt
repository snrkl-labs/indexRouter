package org.snrkl.indexrouter.moc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.snrkl.indexrouter.data.config.ConfigRepository
import org.snrkl.indexrouter.data.prefs.SettingsStore
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.vault.VaultStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds the whole Map of Content tree in one pass.
 *
 * On demand only — never from a webhook. The entire tree is regenerated together
 * so a rebuilt child cannot end up linking to a parent that still lists the old
 * shape; partial rebuilds are how cross-links go stale.
 */
class MocGenerator(
    private val settings: SettingsStore,
    private val config: ConfigRepository,
    private val vaultStore: () -> VaultStore?,
) {

    data class Result(
        val written: List<String> = emptyList(),
        val prunedBackups: Int = 0,
        val failure: String? = null,
    )

    suspend fun run(
        rebuildMaps: Boolean,
        refreshSamples: Boolean,
        now: LocalDateTime = LocalDateTime.now(),
    ): Result = withContext(Dispatchers.IO) {
        var result = Result()
        if (rebuildMaps) result = rebuild(now)
        if (refreshSamples) {
            val samples = includedFolders().mapNotNull { (name, _) -> writeProForma(name).written.firstOrNull() }
            result = result.copy(written = result.written + samples)
        }
        result
    }

    suspend fun rebuild(now: LocalDateTime = LocalDateTime.now()): Result = withContext(Dispatchers.IO) {
        val store = vaultStore() ?: return@withContext Result(failure = "no vault access")
        val stamp = DateTimeFormatter.ofPattern(MocContent.BACKUP_STAMP_PATTERN).format(now)

        val folders = includedFolders()
        val written = mutableListOf<String>()
        var pruned = 0
        val children = mutableListOf<Pair<String, String>>()

        folders.forEach { (name, path) ->
            val fileName = MocContent.mocFileName(name)
            val body = MocContent.childBody(
                folderName = name,
                folderPath = path,
                rootMocName = MocContent.mocFileName(baseName()),
                icon = MocContent.iconFor(
                    folderName = name,
                    taskDir = settings.taskDirName,
                    fallbackDir = settings.fallbackDirName,
                    audioDir = settings.audioDirName,
                ),
            )
            pruned += writeWithBackup(store, path, fileName, body, stamp)
            written += "$path/$fileName"
            // Counted for the root index; a folder listing is cheap and it tells
            // the user at a glance which routes are actually being used.
            // Counted recursively: the inbox and audio folders are filed by
            // date, so a single-level listing would report every one as empty.
            val items = store.listFilesRecursive(path).count { it.endsWith(".md") && !it.substringAfterLast('/').startsWith(name) }
            children += name to "$items item${if (items == 1) "" else "s"}"
        }

        val rootName = MocContent.mocFileName(baseName())
        val rootBody = MocContent.rootBody(baseName(), settings.baseDir, children)
        pruned += writeWithBackup(store, settings.baseDir, rootName, rootBody, stamp)
        written += "${settings.baseDir}/$rootName"

        Result(written = written, prunedBackups = pruned)
    }

    /**
     * Writes a folder's sample if it has none yet.
     *
     * Called when the app creates a folder, so the worked example is there at
     * the moment the folder first appears — which is when it is most use, and is
     * the one moment nobody thinks to go looking for a button. Existing samples
     * are left alone here; refreshing them is the rebuild's job.
     */
    suspend fun writeProFormaIfMissing(folderName: String): Result = withContext(Dispatchers.IO) {
        val store = vaultStore() ?: return@withContext Result(failure = "no vault access")
        val path = settings.rulePath(folderName)
        if (store.readText("$path/$folderName queries.md") != null) return@withContext Result()
        writeProForma(folderName)
    }

    /** Drops a ready-to-paste query block beside a folder's content. */
    suspend fun writeProForma(folderName: String): Result = withContext(Dispatchers.IO) {
        val store = vaultStore() ?: return@withContext Result(failure = "no vault access")
        val path = settings.rulePath(folderName)
        val fileName = "$folderName queries.md"
        return@withContext try {
            store.writeText(
                "$path/$fileName",
                MocContent.proForma(
                    folderName = folderName,
                    folderPath = path,
                    basePath = settings.baseDir,
                    isTaskFolder = folderName == settings.taskDirName,
                ),
            )
            Result(written = listOf("$path/$fileName"))
        } catch (t: Throwable) {
            Result(failure = t.message ?: "could not write the query file")
        }
    }

    /** Folders that appear in the map: reserved ones, plus rules that opt in. */
    suspend fun includedFolders(): List<Pair<String, String>> {
        val fromRules = config.rules()
            .filter { it.action == ActionType.FILE && it.includeInMoc }
            .mapNotNull { rule -> rule.folder?.takeIf { it.isNotBlank() } }
            .distinct()
            .map { it to settings.rulePath(it) }

        // Audio is deliberately absent: it holds recordings referenced from notes,
        // not content anyone would browse through an index.
        val reserved = listOf(
            settings.taskDirName to settings.taskPath(),
            settings.fallbackDirName to settings.fallbackPath(),
        )
        return (reserved + fromRules).distinctBy { it.second }
    }

    private fun baseName(): String =
        settings.baseDir.trim('/').substringAfterLast('/').ifBlank { "Vault" }

    /** Returns how many old backups were pruned. */
    private fun writeWithBackup(
        store: VaultStore,
        folderPath: String,
        fileName: String,
        body: String,
        stamp: String,
    ): Int {
        val target = "$folderPath/$fileName"
        val existing = store.readText(target)
        var pruned = 0

        if (existing != null) {
            val backupFolder = "$folderPath/.backups"
            store.writeText("$backupFolder/${MocContent.backupName(fileName, stamp)}", existing)
            MocContent.backupsToPrune(
                existing = store.listFiles(backupFolder),
                mocFileName = fileName,
                keep = settings.mocBackupRetention,
            ).forEach { stale ->
                if (store.delete("$backupFolder/$stale")) pruned++
            }
        }

        store.writeText(target, body)
        return pruned
    }
}
