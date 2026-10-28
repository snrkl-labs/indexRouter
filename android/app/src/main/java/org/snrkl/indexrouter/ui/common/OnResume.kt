package org.snrkl.indexrouter.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

/**
 * Runs [block] every time the screen comes back to the foreground.
 *
 * Load-bearing here, not a nicety. Almost everything this app asks the user to
 * do happens in *Android's* settings — Storage Scopes, all-files access,
 * "Display over other apps", enabling the input method — so the normal shape of
 * a fix is "leave the app, change something, come back". Reading state only
 * once on entry means the app then reports the state from before the fix, which
 * is indistinguishable from the fix not having worked.
 *
 * The view-based screens did this in `onResume`; a `LaunchedEffect(Unit)` does
 * not, and losing it was a regression in the move to Compose.
 */
@Composable
internal fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(block)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) current()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
