package dev.infusion.tv.ui.update

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.infusion.core.update.UpdatePresentation
import dev.infusion.core.update.UpdateState

object TvUpdateDialogKeys {
    const val Dialog = "update:dialog"
    const val Title = "update:title"
    const val Message = "update:message"
    const val Progress = "update:progress"
    const val Update = "update:action:update"
    const val Dismiss = "update:action:dismiss"
    const val Retry = "update:action:retry"
    const val OpenSettings = "update:action:settings"
}

@Composable
fun TvUpdateDialogHost(
    state: UpdateState,
    playbackLive: Boolean,
    onUpdate: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!UpdatePresentation.isDialogVisible(state, playbackLive)) return
    TvUpdateDialog(
        state = state,
        onUpdate = onUpdate,
        onRetry = onRetry,
        onDismiss = onDismiss,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
private fun TvUpdateDialog(
    state: UpdateState,
    onUpdate: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(620.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                .testTag(TvUpdateDialogKeys.Dialog),
            shape = MaterialTheme.shapes.medium,
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = title(state),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag(TvUpdateDialogKeys.Title),
                )
                val message = message(state)
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(
                            if (state is UpdateState.Downloading) {
                                TvUpdateDialogKeys.Progress
                            } else {
                                TvUpdateDialogKeys.Message
                            },
                        ),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (state) {
                        is UpdateState.Available -> {
                            TvActionButton(TvUpdateDialogKeys.Update, "Update", primary = true, onSelect = onUpdate)
                            TvActionButton(TvUpdateDialogKeys.Dismiss, "Dismiss", primary = false, onSelect = onDismiss)
                        }
                        is UpdateState.Failed ->
                            if (state.kind.retryable) {
                                TvActionButton(TvUpdateDialogKeys.Retry, "Retry", primary = true, onSelect = onRetry)
                                TvActionButton(TvUpdateDialogKeys.Dismiss, "Dismiss", primary = false, onSelect = onDismiss)
                            } else {
                                TvActionButton(TvUpdateDialogKeys.Dismiss, "Dismiss", primary = true, onSelect = onDismiss)
                            }
                        is UpdateState.NeedsPermission -> {
                            TvActionButton(
                                TvUpdateDialogKeys.OpenSettings,
                                "Open settings",
                                primary = true,
                                onSelect = onOpenSettings,
                            )
                            TvActionButton(TvUpdateDialogKeys.Dismiss, "Dismiss", primary = false, onSelect = onDismiss)
                        }

                        is UpdateState.Downloading,
                        is UpdateState.Verifying,
                        -> TvActionButton(TvUpdateDialogKeys.Dismiss, "Cancel", primary = true, onSelect = onDismiss)

                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun TvActionButton(
    key: String,
    label: String,
    primary: Boolean,
    onSelect: () -> Unit,
) {

    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        if (primary) runCatching { requester.requestFocus() }
    }
    Button(
        onClick = onSelect,
        modifier = Modifier
            .testTag(key)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused },
        colors = ButtonDefaults.colors(
            containerColor = if (primary) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            focusedContainerColor = if (primary) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (primary) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            focusedContentColor = if (primary) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        ),
    ) {
        Text(if (focused) "▶ $label" else label, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun title(state: UpdateState): String = when (state) {
    is UpdateState.Available -> "INFUSION ${state.candidate.versionName} is available"
    is UpdateState.Downloading,
    is UpdateState.Verifying,
    is UpdateState.ReadyToInstall,
    -> "Updating INFUSION"
    is UpdateState.NeedsPermission -> "Permission needed"
    is UpdateState.Failed -> "Update problem"
    else -> "App update"
}

internal fun message(state: UpdateState): String? = when (state) {
    is UpdateState.Available ->
        "A newer version is ready to download. Your profiles, settings and watch history are kept."
    is UpdateState.Downloading ->
        if (state.totalBytes > 0L) {
            "Downloading… ${(state.bytesRead * 100 / state.totalBytes).coerceIn(0L, 100L)}%"
        } else {
            "Downloading…"
        }
    is UpdateState.Verifying -> "Verifying the update…"
    is UpdateState.ReadyToInstall -> "Confirm the install in the system dialog."
    is UpdateState.NeedsPermission -> "Allow INFUSION to install apps, then come back."
    is UpdateState.Failed -> state.kind.message
    else -> null
}
