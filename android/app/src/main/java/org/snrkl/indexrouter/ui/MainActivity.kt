package org.snrkl.indexrouter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.getSystemService
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.snrkl.indexrouter.IndexRouterApp
import org.snrkl.indexrouter.R
import org.snrkl.indexrouter.action.ActivityLauncher
import org.snrkl.indexrouter.ui.common.viewModelFactory
import org.snrkl.indexrouter.ui.log.LogScreen
import org.snrkl.indexrouter.ui.log.LogViewModel
import org.snrkl.indexrouter.ui.main.MainScreen
import org.snrkl.indexrouter.ui.main.MainViewModel
import org.snrkl.indexrouter.ui.nav.Destinations
import org.snrkl.indexrouter.ui.rules.RulesScreen
import org.snrkl.indexrouter.ui.rules.RulesViewModel
import org.snrkl.indexrouter.ui.settings.SettingsScreen
import org.snrkl.indexrouter.ui.settings.SettingsViewModel
import org.snrkl.indexrouter.ui.theme.IndexRouterTheme
import org.snrkl.indexrouter.vault.VaultPathResolver

/**
 * The app's one activity.
 *
 * It replaced three — main, rules and log — when the UI moved to Compose. The
 * system-picker launchers live here rather than inside a screen because an
 * activity result outlives the composition that asked for it, and the vault
 * grant is the one thing that must not be lost to a rotation halfway through.
 */
class MainActivity : ComponentActivity() {

    private val container get() = (application as IndexRouterApp).container

    /** Set by whichever screen launched the picker, consumed by its callback. */
    private var onVaultPicked: ((path: String?, treeUri: String?) -> Unit)? = null
    private var onBaseFolderPicked: ((String) -> Unit)? = null
    private var onRuleFolderPicked: ((String) -> Unit)? = null

    /** Shown over whatever screen asked for it. */
    private var fileAccessRequested by mutableStateOf(false)

    /**
     * The screen a tapped notification asked for, consumed once navigated to.
     *
     * Read here rather than as the graph's start destination because the
     * activity is usually **already running** when a notification is tapped, and
     * a running activity does not re-run `onCreate` — so a start destination set
     * there is simply ignored, and the tap lands on whatever screen was already
     * open. A notification that says "tap to see the log" and does not is the
     * quiet kind of wrong this app exists to avoid.
     */
    private var pendingDestination by mutableStateOf<String?>(null)

    private val pickVault = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        // Keep the SAF grant either way: it costs nothing and is the fallback for
        // a vault that is not on primary storage.
        container.vault.persistGrant(uri)

        // The picker is only a way to point at a folder; what gets stored is the
        // plain path, which is what Storage Scopes actually confines.
        val path = VaultPathResolver.toFilesystemPath(
            DocumentsContract.getTreeDocumentId(uri),
            Environment.getExternalStorageDirectory().absolutePath,
        )
        onVaultPicked?.invoke(path, uri.toString())
    }

    private val pickBaseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        val vaultRoot = container.settings.vaultRootPath
        val absolute = VaultPathResolver.toFilesystemPath(
            DocumentsContract.getTreeDocumentId(uri),
            Environment.getExternalStorageDirectory().absolutePath,
        )
        val relative = if (vaultRoot != null && absolute != null) {
            VaultPathResolver.toVaultRelative(absolute, vaultRoot)
        } else {
            null
        }

        when {
            relative == null -> toast(getString(R.string.base_folder_outside_vault, vaultRoot.orEmpty()))
            // The vault root itself would put the app's files loose among the
            // user's notes, which is what having a base folder avoids.
            relative.isBlank() -> toast(getString(R.string.base_folder_not_root))
            else -> onBaseFolderPicked?.invoke(relative.trim('/'))
        }
    }

    /**
     * Picks a folder for a rule, narrowed to one segment under the base folder.
     *
     * Anything else is either outside the app's own tree or a leftover full
     * path, and both used to end up creating folders in the wrong place.
     */
    private val pickRuleFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        val vaultRoot = container.settings.vaultRootPath
        if (vaultRoot == null) {
            toast(getString(R.string.rule_folder_needs_root))
            return@registerForActivityResult
        }
        val absolute = VaultPathResolver.toFilesystemPath(
            DocumentsContract.getTreeDocumentId(uri),
            Environment.getExternalStorageDirectory().absolutePath,
        )
        val relative = absolute?.let { VaultPathResolver.toVaultRelative(it, vaultRoot) }
        val base = container.settings.baseDir.trim('/')
        val inside = relative
            ?.takeIf { it == base || it.startsWith("$base/") }
            ?.removePrefix("$base/")
        if (inside == null) {
            toast(getString(R.string.rule_folder_outside_base, base))
            return@registerForActivityResult
        }
        onRuleFolderPicked?.invoke(inside.trim('/').substringAfterLast('/'))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A notification tapped in the shade names the screen it should open.
        pendingDestination = intent?.getStringExtra(Destinations.EXTRA_START_DESTINATION)

        setContent {
            IndexRouterTheme {
                AppNavigation()
            }
        }
    }

    /** The common case: a notification tapped while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDestination = intent.getStringExtra(Destinations.EXTRA_START_DESTINATION)
    }

    @Composable
    private fun AppNavigation() {
        val navController = rememberNavController()
        val application = application

        // Navigated on top of the main screen rather than replacing it as the
        // start destination, so Back from a notification-opened log returns to
        // the app rather than leaving it.
        LaunchedEffect(pendingDestination) {
            pendingDestination?.let { destination ->
                navController.navigate(destination) { launchSingleTop = true }
                pendingDestination = null
            }
        }

        NavHost(
            navController = navController,
            startDestination = Destinations.MAIN,
        ) {
            composable(Destinations.MAIN) {
                val vm: MainViewModel = viewModel(factory = viewModelFactory { MainViewModel(application) })
                LaunchedEffect(Unit) {
                    // Opening the app is the spec's "read the vault file" moment;
                    // a webhook never triggers a read.
                    vm.initialise()
                    // Self-heal: the receiver may have been killed while the app
                    // was closed, and this is the one moment to put it back.
                    vm.restartReceiverIfExpected()
                }
                MainScreen(
                    viewModel = vm,
                    onOpenRules = { navController.navigate(Destinations.RULES) },
                    onOpenLog = { navController.navigate(Destinations.LOG) },
                    onOpenSettings = { navController.navigate(Destinations.SETTINGS) },
                    onFixBackgroundLaunch = {
                        startActivity(ActivityLauncher.overlaySettingsIntent(this@MainActivity))
                    },
                    onCopy = ::copy,
                )
            }

            composable(Destinations.RULES) {
                val vm: RulesViewModel = viewModel(factory = viewModelFactory { RulesViewModel(application) })
                RulesScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenPermissions = { fileAccessRequested = true },
                    // The system picker is what guarantees the chosen folder is
                    // inside the granted tree; the dropdown only offers what is
                    // already there.
                    onChooseFolderFor = { onPicked ->
                        onRuleFolderPicked = onPicked
                        pickRuleFolder.launch(null)
                    },
                )
            }

            composable(Destinations.LOG) {
                val vm: LogViewModel = viewModel(factory = viewModelFactory { LogViewModel(application) })
                LogScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }

            composable(Destinations.SETTINGS) {
                val vm: SettingsViewModel =
                    viewModel(factory = viewModelFactory { SettingsViewModel(application) })
                SettingsScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onChooseVault = {
                        onVaultPicked = { path, treeUri ->
                            vm.applyVaultGrant(path, treeUri) {
                                // A freshly granted vault with no base folder
                                // chosen yet: ask for it rather than adopting a
                                // guess at a name in someone else's vault.
                                onBaseFolderPicked = { relative -> vm.setBaseDir(relative) }
                                pickBaseFolder.launch(null)
                            }
                            vm.refresh(hasAllFilesAccess())
                        }
                        pickVault.launch(null)
                    },
                    onChooseBaseFolder = {
                        onBaseFolderPicked = { relative -> vm.setBaseDir(relative) }
                        pickBaseFolder.launch(null)
                    },
                    onOpenFileAccess = { fileAccessRequested = true },
                    onOpenNotificationChannels = ::openNotificationChannels,
                    onOpenInputMethodSettings = ::openInputMethodSettings,
                    hasAllFilesAccess = ::hasAllFilesAccess,
                )
            }
        }

        if (fileAccessRequested) {
            FileAccessDialog()
        }
    }

    /**
     * How to grant file access, which differs by OS in a way that matters.
     *
     * On GrapheneOS the right answer is Storage Scopes, and the wrong answer is
     * "All files access" — the scopes option is only offered while the app holds
     * no storage permission, so granting all-files access removes it. Sending the user
     * straight to the all-files toggle is pointing at the one door that locks the
     * better one, so both are offered with the difference stated.
     */
    @Composable
    private fun FileAccessDialog() {
        AlertDialog(
            onDismissRequest = { fileAccessRequested = false },
            title = { Text(stringResource(R.string.file_access_title)) },
            text = { Text(stringResource(R.string.file_access_body)) },
            confirmButton = {
                TextButton(onClick = {
                    fileAccessRequested = false
                    openAppInfo()
                }) { Text(stringResource(R.string.file_access_scopes)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    fileAccessRequested = false
                    openAllFilesAccessSettings()
                }) { Text(stringResource(R.string.file_access_all_files)) }
            },
        )
    }

    private fun openAppInfo() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun openAllFilesAccessSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }.onFailure { openAppInfo() }
    }

    /**
     * Where the voice input method is enabled.
     *
     * Enabling an input method is a deliberate act the user performs in system
     * settings — an app cannot grant it to itself, and should not try — so this
     * opens the right screen rather than pretending to do it.
     */
    private fun openInputMethodSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            .onFailure { openAppInfo() }
    }

    /** The system screen where each route channel's sound is chosen. */
    private fun openNotificationChannels() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        runCatching { startActivity(intent) }.onFailure { openAppInfo() }
    }

    private fun copy(value: String, what: String) {
        getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText("indexrouter", value))
        toast(getString(if (what == "url") R.string.url_copied else R.string.secret_copied))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
}
