@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package dev.infusion.tv.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.infusion.feature.vertical.music.MusicQueuePhase
import dev.infusion.feature.vertical.music.MusicQueueStateHolder
import dev.infusion.tv.ui.shell.TvGlobalOverlayAccess
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

@Composable
fun MusicNowPlayingChip(
    holder: MusicQueueStateHolder,
    onOpen: () -> Unit,
    access: TvGlobalOverlayAccess? = null,
) {
    val state by holder.state.collectAsStateWithLifecycle()
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val armed = access?.overlayArmed == true
    val available = state.hasQueue && access?.nowPlayingVisible != true

    LaunchedEffect(access, requester, available) {
        access?.attachOverlay(requester, available = available)
    }
    DisposableEffect(access, requester) {
        onDispose { access?.detachOverlay(requester) }
    }

    LaunchedEffect(armed) {
        if (!armed || access == null) return@LaunchedEffect
        repeat(3) {
            if (requester.requestFocus()) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
        }
        access.abandonEntry()
    }

    if (access?.nowPlayingVisible == true) {

        LaunchedEffect(Unit) { access.abandonEntry() }
        return
    }
    if (!state.hasQueue) {

        LaunchedEffect(Unit) { if (access?.overlayArmed == true) access.returnToOrigin() }
        return
    }

    val status = when (state.phase) {
        MusicQueuePhase.PLAYING -> "Playing"
        MusicQueuePhase.PAUSED -> "Paused"
        MusicQueuePhase.PREPARING -> "Loading"
        MusicQueuePhase.ENDED -> "End of queue"
        MusicQueuePhase.ERROR -> "Needs attention"

        MusicQueuePhase.READY -> "Queued"
        MusicQueuePhase.IDLE -> ""
    }
    val label = buildString {
        append("♪ ")
        append(state.current?.title ?: state.contextLabel ?: "Now playing")
        if (status.isNotEmpty()) append(" · ").append(status)
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        InfusionTvButton(
            onClick = onOpen,
            modifier = Modifier
                .padding(end = 32.dp, bottom = 24.dp)
                .focusRequester(requester)

                .focusProperties { canFocus = armed }
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> access?.returnToOrigin() ?: false
                        Key.Back, Key.Escape -> access?.returnToOrigin() ?: false

                        Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                }
                .testTag("music-now-playing-chip"),
        ) {
            Text(
                text = label,
                style = InfusionTvScaleCalibration.Type.Emphasis,
                maxLines = 1,
            )
        }
    }
}
