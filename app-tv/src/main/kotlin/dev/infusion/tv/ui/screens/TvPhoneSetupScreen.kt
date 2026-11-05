package dev.infusion.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.infusion.feature.configweb.ConfigDraftStatus
import dev.infusion.feature.configweb.ConfigDraftSummary
import dev.infusion.feature.configweb.ConfigWebTvState
import dev.infusion.feature.pairing.QrMatrix
import dev.infusion.tv.configweb.ConfigWebSessionStateHolder
import dev.infusion.tv.configweb.PhoneSetupUiState
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

internal const val PhoneSetupConfirmKey = "pairing:confirm"
internal const val PhoneSetupCancelKey = "pairing:cancel"
internal const val PhoneSetupRetryKey = "pairing:retry"
internal const val PhoneSetupDismissKey = "pairing:dismiss"
internal const val PhoneSetupStartNewKey = "pairing:start-new"

@Composable
fun TvPhoneSetupScreen(
    stateHolder: ConfigWebSessionStateHolder,
    onBack: () -> Unit,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val tvState by stateHolder.tvState.collectAsStateWithLifecycle()

    var dismissedResult by remember { mutableStateOf<ConfigDraftStatus?>(null) }

    DisposableEffect(stateHolder) {
        onDispose { stateHolder.stop(reason = "screen_left") }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, stateHolder) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stateHolder.stop(reason = "app_backgrounded")
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Manage from phone", style = InfusionTvScaleCalibration.Type.Headline)

        when (val current = state) {
            is PhoneSetupUiState.Idle -> {
                Text(
                    "Open a session, then scan the code with your phone or type the code " +
                        "below it. Session settings and add-ons from the page on your phone " +
                        "change only after you confirm here.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfusionTvButton(onClick = { stateHolder.start() }) {
                        Text("Start session")
                    }
                    InfusionTvButton(onClick = onBack) { Text("Back") }
                }
            }

            is PhoneSetupUiState.Starting -> Text("Opening the session…")

            is PhoneSetupUiState.NoNetwork -> {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfusionTvButton(onClick = { stateHolder.start() }) { Text("Try again") }
                    InfusionTvButton(onClick = onBack) { Text("Back") }
                }
            }

            is PhoneSetupUiState.Unavailable -> {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfusionTvButton(onClick = { stateHolder.start() }) { Text("Try again") }
                    InfusionTvButton(onClick = onBack) { Text("Back") }
                }
            }

            is PhoneSetupUiState.Active -> {
                ActiveSessionContent(
                    session = current,
                    tvState = tvState,
                    onStop = {
                        stateHolder.stop(reason = "stop")
                        onBack()
                    },
                    onBack = {
                        stateHolder.stop(reason = "back")
                        onBack()
                    },
                )
            }

            is PhoneSetupUiState.Ended -> {

                val requesters = rememberFocusRequesters()
                LaunchedEffect(Unit) {
                    repeat(30) {
                        withFrameNanos { }
                        if (requesters[PhoneSetupStartNewKey]?.requestFocus() == true) {
                            return@LaunchedEffect
                        }
                    }
                }
                Text("This session has ended. Scan a new code to continue.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfusionTvButton(
                        onClick = { stateHolder.start() },
                        modifier = stableFocusModifier(PhoneSetupStartNewKey, requesters) {},
                    ) {
                        Text("Start new session")
                    }
                    InfusionTvButton(onClick = onBack) { Text("Back") }
                }
            }
        }
    }

    tvState.pending?.let { pending ->
        ChangeSummaryDialog(
            summary = pending.summary,
            onConfirm = { stateHolder.confirm(pending.draftId) },
            onCancel = { stateHolder.reject(pending.draftId) },
        )
    }

    tvState.lastResult?.takeIf { it != dismissedResult }?.let { result ->
        ApplyResultDialog(
            result = result,
            onRetry = { stateHolder.retry(result.draftId) },

            onDismiss = { dismissedResult = result },
        )
    }
}

@Composable
private fun ActiveSessionContent(
    session: PhoneSetupUiState.Active,
    tvState: ConfigWebTvState,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    val qrBitmap = remember(session.qr) { session.qr.toDisplayBitmap(scale = 4) }
    val remaining = tvState.session?.remainingMs ?: 0L

    Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Top) {
        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = "Session QR code",
            modifier = Modifier.size(280.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Scan the code with your phone", style = InfusionTvScaleCalibration.Type.Emphasis)
            Text("Open this address on your phone", style = InfusionTvScaleCalibration.Type.Emphasis)
            Text(session.addressLabel, style = InfusionTvScaleCalibration.Type.Body)
            Text("Or type this session code", style = InfusionTvScaleCalibration.Type.Emphasis)
            Text(session.manualCode, style = InfusionTvScaleCalibration.Type.Body)
            Text(
                "Time remaining ${formatRemaining(remaining)}",
                style = InfusionTvScaleCalibration.Type.Emphasis,
            )
        }
    }
    Text(
        "Nothing on this TV changes until you confirm a summary here.",
        style = InfusionTvScaleCalibration.Type.Body,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InfusionTvButton(onClick = onStop) { Text("Stop") }
        InfusionTvButton(onClick = onBack) { Text("Back") }
    }
}

internal fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) / 1000L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun ChangeSummaryDialog(
    summary: ConfigDraftSummary,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val requesters = rememberFocusRequesters()
    LaunchedEffect(Unit) {
        repeat(30) {
            withFrameNanos { }
            if (requesters[PhoneSetupConfirmKey]?.requestFocus() == true) return@LaunchedEffect
        }
    }

    BackHandler(enabled = true, onBack = onCancel)

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width(760.dp), shape = MaterialTheme.shapes.medium) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Apply these changes?", style = InfusionTvScaleCalibration.Type.Headline)
                Text(
                    if (summary.operationCount == 1) "1 change from your phone" else
                        "${summary.operationCount} changes from your phone",
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                )
                summary.families.forEach { family ->
                    Text(
                        "${family.family.label} — ${family.count} " +
                            if (family.count == 1) "change" else "changes",
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                    )
                    family.labels.forEach { label ->
                        Text("· $label", style = InfusionTvScaleCalibration.Type.Body)
                    }
                }
                Text(
                    "Changes are applied one family at a time. If one fails, the rest are " +
                        "reported, never rolled back.",
                    style = InfusionTvScaleCalibration.Type.Body,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfusionTvButton(
                        onClick = onConfirm,
                        modifier = stableFocusModifier(PhoneSetupConfirmKey, requesters) {},
                    ) {
                        Text("Confirm")
                    }
                    InfusionTvButton(
                        onClick = onCancel,
                        modifier = stableFocusModifier(PhoneSetupCancelKey, requesters) {},
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun ApplyResultDialog(
    result: ConfigDraftStatus,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val requesters = rememberFocusRequesters()
    val retryable = result.counts.failed + result.counts.notAttempted > 0
    val firstKey = if (retryable) PhoneSetupRetryKey else PhoneSetupDismissKey
    LaunchedEffect(firstKey) {
        repeat(30) {
            withFrameNanos { }
            if (requesters[firstKey]?.requestFocus() == true) return@LaunchedEffect
        }
    }
    BackHandler(enabled = true, onBack = onDismiss)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width(760.dp), shape = MaterialTheme.shapes.medium) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(resultHeadline(result), style = InfusionTvScaleCalibration.Type.Headline)
                Text(
                    "${result.counts.applied} applied, ${result.counts.failed} failed, " +
                        "${result.counts.notAttempted} not applied",
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                )
                result.operations.forEach { operation ->
                    val outcome = when (operation.outcome) {
                        "applied" -> "Applied"
                        "failed" -> "Failed"
                        "not_attempted" -> "Not applied"
                        else -> "Pending"
                    }
                    Text(
                        "$outcome — ${operation.family.label} · ${operation.rowKey}" +
                            (operation.reason?.let { " ($it)" } ?: ""),
                        style = InfusionTvScaleCalibration.Type.Body,
                    )
                }
                Text(
                    "Retry re-attempts only the changes that were not applied. " +
                        "Nothing that was applied is replayed, and nothing is rolled back.",
                    style = InfusionTvScaleCalibration.Type.Body,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (retryable) {
                        InfusionTvButton(
                            onClick = onRetry,
                            modifier = stableFocusModifier(PhoneSetupRetryKey, requesters) {},
                        ) {
                            Text("Retry not applied")
                        }
                    }
                    InfusionTvButton(
                        onClick = onDismiss,
                        modifier = stableFocusModifier(PhoneSetupDismissKey, requesters) {},
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

private fun resultHeadline(result: ConfigDraftStatus): String = when {
    result.counts.failed + result.counts.notAttempted == 0 -> "Changes applied"
    result.counts.applied == 0 -> "No changes were applied"
    else -> "Some changes were not applied"
}

private fun QrMatrix.toDisplayBitmap(scale: Int): android.graphics.Bitmap {
    val scaled = size * scale
    val pixels = IntArray(scaled * scaled)
    for (y in 0 until scaled) {
        for (x in 0 until scaled) {
            val dark = get(x / scale, y / scale)
            pixels[y * scaled + x] = if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return android.graphics.Bitmap.createBitmap(pixels, scaled, scaled, android.graphics.Bitmap.Config.ARGB_8888)
}
