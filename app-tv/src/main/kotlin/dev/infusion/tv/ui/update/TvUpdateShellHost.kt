package dev.infusion.tv.ui.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infusion.core.update.UpdateState
import dev.infusion.tv.di.AppContainer
import dev.infusion.tv.ui.shell.LocalTvGlobalOverlayAccess
import dev.infusion.tv.updater.openUnknownSourcesSettings
import dev.infusion.tv.updater.suppressesUpdatePresentation

@Composable
fun TvUpdateShellHost(container: AppContainer) {
    if (LocalTvGlobalOverlayAccess.current == null) return

    val controller = container.updateController
    val state by controller.state.collectAsStateWithLifecycle()
    val musicState by container.musicQueueStateHolder.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentState by rememberUpdatedState(state)

    LaunchedEffect(controller) {
        controller.startupCheckOnce()
    }

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (currentState !is UpdateState.NeedsPermission) return@LifecycleEventObserver
            if (context.packageManager.canRequestPackageInstalls()) {
                controller.onPermissionGranted()
            } else {
                controller.onDismiss()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    TvUpdateDialogHost(
        state = state,
        playbackLive = musicState.phase.suppressesUpdatePresentation(),
        onUpdate = controller::onUpdateConfirmed,
        onRetry = controller::onRetry,
        onDismiss = controller::onDismiss,
        onOpenSettings = { openUnknownSourcesSettings(context) },
    )
}
