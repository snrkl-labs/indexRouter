package org.snrkl.indexrouter.data.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.snrkl.indexrouter.routing.ActionType
import org.snrkl.indexrouter.routing.RuleGroup

/**
 * Reads and writes the vault config file.
 *
 * The file is a Markdown note. Its frontmatter carries the format version and
 * nothing else; the settings and routing table live in a fenced `yaml` block in
 * the body.
 *
 * That split is deliberate. Obsidian renders frontmatter through its properties
 * editor — a table of typed rows, no comments, awkward for nested lists — which
 * is the least hand-editable surface it has, and hand-editing this file is the
 * whole reason it sits in the vault. A fenced block is shown as plain
 * monospaced text and edits like a text file. The version stays in the
 * frontmatter so a reader, human or machine, sees it before parsing anything.
 *
 * Files written by earlier builds put everything in the frontmatter. Those are
 * still read (see [decode]) and converted the next time the app writes.
 *
 * No YAML library round-trips comments, so a rewrite drops anything the user adds
 * outside the recognised keys. The caller keeps a .bak of the previous file for
 * exactly that reason — see ConfigRepository.
 *
 * No Android types: covered by JVM unit tests.
 */
object ConfigCodec {

    private const val FENCE = "---"

    /** The body block's opening fence. `yaml` so Obsidian syntax-highlights it. */
    private const val BLOCK_OPEN = "```yaml"
    private const val BLOCK_CLOSE = "```"

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            // Tolerate keys we do not know: a hand-edit typo should not cost the
            // whole config, and the fallback path is a loud, user-visible event.
            strictMode = false,
            encodeDefaults = true,
        ),
    )

    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * The file predates the folder-schema change, so its `folder` values are
     * vault-root paths whose meaning has since narrowed. Reinterpreting them
     * would write to the wrong place silently, so the caller starts fresh from
     * bundled defaults and says so out loud instead.
     */
    class UnsupportedVersionException(val found: String) :
        Exception("Config file is version $found; this app writes version ${ConfigVersion.CURRENT}.")

    /**
     * Reads either layout: the current fenced body block, or the all-in-the-
     * frontmatter shape written up to `00.03.00`.
     *
     * The version is read from the raw text rather than from the decoded value,
     * because a file with no version key at all decodes with the default and
     * would otherwise pass itself off as current.
     */
    fun decode(fileContents: String): ConfigDocument {
        val text = fileContents.replace("\r\n", "\n").trimStart('﻿', '\n', ' ')
        val frontmatter = extractFrontmatter(text)
        val block = extractYamlBlock(text)

        if (frontmatter == null && block == null) {
            throw ParseException(
                "No config found — the file needs a ```yaml block holding `settings:` and `routing:`.",
            )
        }

        // The version can sit in the frontmatter (where this build puts it) or
        // inside the block (which a file with no frontmatter must use).
        val declared = declaredVersion(frontmatter) ?: declaredVersion(block)
        val version = ConfigVersion.parse(declared)
        if (version == null || version < ConfigVersion.OLDEST_READABLE) {
            throw UnsupportedVersionException(declared?.trim() ?: "unversioned")
        }

        // Up to 00.03.00 the settings and routing lived in the frontmatter. The
        // keys are identical either way, so the same parse reads both — the
        // version key is simply ignored where it sits alongside them.
        val source = block ?: frontmatter!!
        val fromBlock = block != null

        if (version >= ConfigVersion.PER_VAULT) {
            val payload = decodeAs(ConfigPayload.serializer(), source, fromBlock)
            val common = decodeAs(CommonProbe.serializer(), source, fromBlock).common
            return ConfigDocument(
                version = ConfigVersion.CURRENT.text,
                layout = payload.settings,
                routing = payload.routing,
                common = common,
            )
        }

        // One vault, one list. The rules that need no vault become the common
        // copy; the rest are this vault's own. Device settings are handed back
        // separately so the caller can take them in once.
        val legacy = decodeAs(LegacyPayload.serializer(), source, fromBlock)
        val (vaultRules, commonRules) = legacy.routing.partition { isVaultAction(it.action) }
        return ConfigDocument(
            version = ConfigVersion.CURRENT.text,
            layout = legacy.settings.toLayout(),
            routing = vaultRules,
            common = commonRules,
            legacySettings = legacy.settings,
        )
    }

    /**
     * Whether a rule writes into a vault. An action this build does not know is
     * kept with the vault's own rules, where the loader reports and skips it,
     * rather than being copied into every vault as a common rule.
     */
    private fun isVaultAction(wire: String): Boolean {
        val action = ActionType.fromWire(wire) ?: return true
        return !RuleGroup.COMMON.allows(action)
    }

    fun encode(document: ConfigDocument): String {
        val payload = yaml.encodeToString(ConfigPayload.serializer(), document.payload()).trim()
        return buildString {
            appendLine(FENCE)
            // Quoted: bare 00.04.00 is not a YAML number and an unquoted value
            // with leading zeroes is the kind of thing a parser is entitled to
            // have opinions about.
            appendLine("version: \"${ConfigVersion.CURRENT}\"")
            appendLine(FENCE)
            appendLine()
            appendLine(BODY_BANNER.trimIndent().trim())
            appendLine()
            appendLine(BLOCK_OPEN)
            appendLine(payload)
            appendLine(BLOCK_CLOSE)
            appendLine()
            appendLine(BODY_FOOTER.trimIndent().trim())
            appendLine()
        }
    }

    private fun <T> decodeAs(
        serializer: kotlinx.serialization.KSerializer<T>,
        source: String,
        fromBlock: Boolean,
    ): T = try {
        yaml.decodeFromString(serializer, source)
    } catch (t: Throwable) {
        val where = if (fromBlock) "the yaml block" else "the config frontmatter"
        throw ParseException(t.message ?: "Could not parse $where.", t)
    }

    /** Content between the leading `---` fence and the next one. */
    private fun extractFrontmatter(text: String): String? {
        if (!text.startsWith(FENCE)) return null
        val body = text.removePrefix(FENCE).removePrefix("\n")
        val end = body.indexOf("\n$FENCE")
        return if (end < 0) null else body.substring(0, end)
    }

    /**
     * The first ```yaml block in the file.
     *
     * First rather than last: the block the app writes comes before the
     * explanatory footer, so a second block someone pastes in below cannot
     * quietly become the configuration.
     */
    private fun extractYamlBlock(text: String): String? {
        val open = Regex("(?m)^```\\s*(yaml|yml)\\s*$").find(text) ?: return null
        val afterOpen = open.range.last + 1
        val close = Regex("(?m)^```\\s*$").find(text, afterOpen) ?: return null
        return text.substring(afterOpen, close.range.first).trim('\n').ifBlank { null }
    }

    private fun declaredVersion(source: String?): String? =
        source?.let { Regex("(?m)^\\s*version:\\s*(\\S+)\\s*$").find(it)?.groupValues?.get(1) }

    private val BODY_BANNER = """
        # Routing config

        Edit the block below, then use **Reload from vault** in the app to pull the
        changes in. It holds how this vault is filed and the rules that write into
        it, in plain YAML, so it can be read and edited here as text rather than
        through the properties editor.

        **In-app edits overwrite this file**, so a hand-edit made here is lost if
        the app writes before you reload it — the previous contents are kept in the
        `.backups` folder alongside. Comments inside the block are not preserved on
        rewrite.
    """

    private val BODY_FOOTER = """
        ## What the fields mean

        - `base-dir` — the folder the app writes inside, in this vault. It never writes outside it.
        - `task-dir`, `fallback-dir`, `audio-dir` — reserved folders beneath it, always present.
        - `date-filing` — `none`, `month` or `day`: how deeply the inbox and recordings are filed by date.
        - `routing` — the file and task rules that write into this vault, in evaluation
          order. `priority` is lower-wins. Each rule's `folder` is a single name resolved
          under `base-dir`, not a path. A recording that matches none of them goes to
          `fallback-dir`.
        - `common` — rules that need no vault: timer, calendar and dictation. They are
          evaluated alongside the rules above, by priority. Kept here so a reinstall
          restores them.
        - `gesture` — on any rule: `any`, `single-click-hold` or `double-click-hold`.
          A rule limited to one gesture is skipped for recordings from the other.

        This file is also the config backup: after a reinstall, grant the vault folder
        and reload to restore everything above. The app's own settings (port,
        notifications, privacy) and the webhook shared secret are deliberately not
        stored here.
    """
}
