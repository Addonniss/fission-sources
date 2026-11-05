@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package dev.infusion.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusTarget
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import dev.infusion.core.model.ArtworkLocator
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.artworkimage.MediaArtworkImage
import dev.infusion.core.domain.music.MusicLyrics
import dev.infusion.core.playback.api.PlaybackControlsAutoHide
import dev.infusion.feature.vertical.music.MusicLyricsPhase
import dev.infusion.feature.vertical.music.MusicLyricsState
import dev.infusion.feature.vertical.music.MusicQueuePhase
import dev.infusion.feature.vertical.music.MusicQueueStateHolder
import dev.infusion.feature.vertical.music.MusicRepeatMode
import dev.infusion.tv.music.MusicNowPlayingPresentation
import dev.infusion.tv.music.MusicTransportGlyphs
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.requestFocusEventually
import dev.infusion.tv.ui.shell.LocalTvGlobalOverlayAccess
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TvNowPlayingScreen(
    holder: MusicQueueStateHolder,
    presentation: MusicNowPlayingPresentation,
    onBack: () -> Unit,

    autoHide: PlaybackControlsAutoHide = PlaybackControlsAutoHide.DEFAULT,
) {
    val state by holder.state.collectAsStateWithLifecycle()
    val lyricsState by holder.lyrics.collectAsStateWithLifecycle()
    val requesters: FocusKeyRegistry = rememberFocusRequesters()
    val focusScope = rememberCoroutineScope()
    var lyricsFocused by remember { mutableStateOf(false) }
    val overlayAccess = LocalTvGlobalOverlayAccess.current

    val warningColor = androidx.tv.material3.MaterialTheme.colorScheme.error

    DisposableEffect(overlayAccess) {
        overlayAccess?.nowPlayingVisible = true
        onDispose { overlayAccess?.nowPlayingVisible = false }
    }

    val lyricsVisible = presentation.lyricsVisible
    val backdropEnabled = presentation.backdropEnabled

    var chromeVisible by remember { mutableStateOf(true) }
    var chromeInteraction by remember { mutableIntStateOf(0) }
    val hiddenChromeFocus = remember { FocusRequester() }

    var queueOpen by remember { mutableStateOf(false) }

    var queueClosedAtMs by remember { mutableLongStateOf(0L) }
    val menuHost = dev.infusion.tv.ui.presentation.LocalCardContextMenuHost.current
    val menuOpen = menuHost?.anyOpen == true

    fun closeQueueOverlay() {
        queueOpen = false
        queueClosedAtMs = android.os.SystemClock.uptimeMillis()
        chromeVisible = true
        chromeInteraction++

        focusScope.launch {
            withFrameNanos { }
            requesters.requestFocusEventually(KEY_QUEUE)
        }
    }

    LaunchedEffect(chromeVisible, autoHide, chromeInteraction, lyricsFocused, queueOpen, menuOpen) {
        if (!chromeVisible) return@LaunchedEffect
        if (lyricsFocused || queueOpen || menuOpen) return@LaunchedEffect
        val timeout = autoHide.timeoutMs ?: return@LaunchedEffect
        delay(timeout)
        chromeVisible = false
    }

    LaunchedEffect(chromeVisible) {
        if (!chromeVisible) {
            withFrameNanos { }
            runCatching { hiddenChromeFocus.requestFocus() }
        }
    }

    val controlKeys = listOf(
        KEY_PREVIOUS,
        KEY_PLAY_PAUSE,
        KEY_NEXT,
        KEY_STOP,
        KEY_SHUFFLE,
        KEY_REPEAT,
        KEY_QUEUE,
        KEY_LYRICS,
        KEY_BACKDROP,
    )

    fun focusKey(key: String): Boolean {
        chromeInteraction++
        requesters.arm(key)
        if (requesters.request(key)) requesters.disarm()
        return true
    }

    fun step(key: String, delta: Int): Boolean {
        val index = controlKeys.indexOf(key).coerceAtLeast(0)
        focusKey(controlKeys[(index + delta).mod(controlKeys.size)])
        return true
    }

    LaunchedEffect(Unit) {
        repeat(10) {
            if (requesters.request(KEY_PLAY_PAUSE)) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    LaunchedEffect(state.current?.occurrenceId, lyricsVisible) {
        if (lyricsVisible) holder.ensureLyrics()
    }

    val backHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
            when {

                queueOpen -> {
                    closeQueueOverlay()
                    true
                }
                lyricsFocused -> {
                    lyricsFocused = false
                    focusKey(KEY_LYRICS)
                    true
                }
                else -> {

                    if (android.os.SystemClock.uptimeMillis() - queueClosedAtMs < QUEUE_BACK_TAIL_MS) {
                        true
                    } else {
                        onBack()
                        true
                    }
                }
            }
        } else {
            false
        }
    }

    val chromeKeyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else if (event.key == Key.Back || event.key == Key.Escape) {
            false
        } else if (!chromeVisible) {
            chromeVisible = true
            true
        } else {
            chromeInteraction++
            false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))

            .focusRequester(hiddenChromeFocus)
            .then(if (chromeVisible) Modifier else Modifier.focusTarget())
            .onPreviewKeyEvent(chromeKeyHandler),
    ) {

        if (backdropEnabled) {
            val backdropId = state.current?.albumArtistNativeId
            if (backdropId != null) {
                MediaArtworkImage(
                    ref = MediaArtworkRef.catalogTyped(
                        nativeItemId = backdropId,
                        type = ArtworkLocator.CATALOG_BACKDROP_TYPE,
                        policy = ArtworkPolicy.BACKDROP,
                    ),
                    targetSize = IntSize(1920, 1080),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().testTag("now-playing-backdrop"),

                    retainsPreviousSuccess = false,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(InfusionTvContour.NowPlayingBackdropScrim)
                    .testTag("now-playing-backdrop-scrim"),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent(backHandler)
                .padding(horizontal = 48.dp, vertical = 18.dp)
                .testTag("now-playing"),
        ) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {

                Column(modifier = Modifier.width(NowPlayingInformationWidth)) {

                    val albumId = state.current?.albumNativeId
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(InfusionTvContour.ArtworkShape)
                            .background(Color.White.copy(alpha = 0.06f)),
                    ) {
                        if (albumId != null) {
                            MediaArtworkImage(
                                ref = MediaArtworkRef.catalogTyped(
                                    nativeItemId = albumId,
                                    type = ArtworkLocator.DEFAULT_CATALOG_TYPE,
                                    policy = ArtworkPolicy.POSTER,
                                ),
                                targetSize = IntSize(640, 640),
                                contentDescription = "Album artwork",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = state.current?.title ?: "Nothing playing",
                        style = InfusionTvScaleCalibration.Type.Display,
                        color = Color.White,
                        modifier = Modifier.testTag("now-playing-title"),
                    )
                    state.current?.secondaryLine?.let { secondary ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = secondary,
                            style = InfusionTvScaleCalibration.Type.Headline,
                            color = Color.White.copy(alpha = 0.92f),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.contextLabel?.let { label ->
                            Text(
                                text = label,
                                style = InfusionTvScaleCalibration.Type.Headline,
                                color = Color.White.copy(alpha = 0.72f),
                            )
                        }
                        val total = state.total
                        if (total != null && state.current != null) {
                            Text(
                                text = "${state.orderPosition + 1} of $total",
                                style = InfusionTvScaleCalibration.Type.Headline,
                                color = Color.White.copy(alpha = 0.72f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    val error = state.error
                    if (error != null) {
                        Text(
                            text = error.message,
                            style = InfusionTvScaleCalibration.Type.Emphasis,
                            color = warningColor,
                            modifier = Modifier.testTag("now-playing-error"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (error.canRetry) {
                                NowPlayingAction(
                                    label = "Retry",
                                    focusKey = KEY_ERROR_RETRY,
                                    requesters = requesters,
                                    onClick = { holder.retry() },
                                )
                            }
                            if (error.canSkip) {
                                NowPlayingAction(
                                    label = "Skip",
                                    focusKey = KEY_ERROR_SKIP,
                                    requesters = requesters,
                                    onClick = { holder.skip() },
                                )
                            }
                        }
                    } else if (state.endOfQueue) {
                        Text(
                            text = "End of queue",
                            style = InfusionTvScaleCalibration.Type.Emphasis,
                            modifier = Modifier.testTag("now-playing-end"),
                        )
                    } else if (state.sourceChanged) {
                        Text(
                            text = "The source changed since this queue started.",
                            style = InfusionTvScaleCalibration.Type.Emphasis,
                            color = warningColor,
                        )
                    }
                }

                Spacer(Modifier.width(32.dp))

                Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                    if (lyricsVisible) {
                        LyricsPanel(
                            state = lyricsState,
                            positionMs = state.positionMs,
                            occurrenceId = state.current?.occurrenceId,
                            requesters = requesters,
                            onFocusEntered = { lyricsFocused = true },
                            onRetry = { holder.retryLyrics() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (chromeVisible) 1f else 0f)
                    .testTag("now-playing-chrome"),
            ) {
                Spacer(Modifier.height(8.dp))

                SeekRow(
                    state = state,
                    requesters = requesters,
                    onSeek = { target -> holder.seekTo(target) },
                    onTogglePlayPause = { holder.togglePlayPause() },
                    onDown = { focusKey(KEY_PLAY_PAUSE) },
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {

                    NowPlayingIconControl(
                        glyph = MusicTransportGlyphs.Previous,
                        contentDescription = "Previous",
                        focusKey = KEY_PREVIOUS,
                        enabled = state.canPrevious,
                        requesters = requesters,
                        onLeft = { step(KEY_PREVIOUS, -1) },
                        onRight = { step(KEY_PREVIOUS, +1) },
                        onUp = { focusKey(KEY_PROGRESS) },
                        onDown = { true },
                        onClick = { holder.previous() },
                    )
                    NowPlayingIconControl(

                        glyph = if (state.phase == MusicQueuePhase.PLAYING) {
                            TvPlayerGlyphs.Pause
                        } else {
                            TvPlayerGlyphs.Play
                        },
                        contentDescription = when (state.phase) {
                            MusicQueuePhase.PLAYING -> "Pause"
                            MusicQueuePhase.PAUSED -> "Resume"
                            MusicQueuePhase.ENDED -> "Replay"
                            else -> "Play"
                        },
                        focusKey = KEY_PLAY_PAUSE,
                        enabled = true,
                        requesters = requesters,
                        onLeft = { step(KEY_PLAY_PAUSE, -1) },
                        onRight = { step(KEY_PLAY_PAUSE, +1) },
                        onUp = { focusKey(KEY_PROGRESS) },
                        onDown = { true },
                        onClick = { holder.togglePlayPause() },
                    )
                    NowPlayingIconControl(
                        glyph = MusicTransportGlyphs.Next,
                        contentDescription = "Next",
                        focusKey = KEY_NEXT,
                        enabled = state.canNext,
                        requesters = requesters,
                        onLeft = { step(KEY_NEXT, -1) },
                        onRight = { step(KEY_NEXT, +1) },
                        onUp = { focusKey(KEY_PROGRESS) },
                        onDown = { true },
                        onClick = { holder.next() },
                    )
                    NowPlayingIconControl(
                        glyph = MusicTransportGlyphs.Stop,
                        contentDescription = "Stop",
                        focusKey = KEY_STOP,
                        enabled = state.hasQueue,
                        requesters = requesters,
                        onLeft = { step(KEY_STOP, -1) },
                        onRight = { step(KEY_STOP, +1) },
                        onUp = { focusKey(KEY_PROGRESS) },
                        onDown = { true },
                        onClick = {
                            holder.stop()
                            onBack()
                        },
                    )
                    NowPlayingIconControl(

                        glyph = if (state.shuffleEnabled) {
                            MusicTransportGlyphs.ShuffleOn
                        } else {
                            MusicTransportGlyphs.Shuffle
                        },
                        contentDescription = if (state.shuffleEnabled) "Shuffle on" else "Shuffle off",
                        focusKey = KEY_SHUFFLE,

                        enabled = state.shuffleAvailable,
                        selected = state.shuffleEnabled,
                        requesters = requesters,
                        onLeft = { step(KEY_SHUFFLE, -1) },
                        onRight = { step(KEY_SHUFFLE, +1) },
                        onUp = { focusKey(KEY_PROGRESS) },
                        onDown = { true },
                        onClick = { holder.toggleShuffle() },
                    )

                    NowPlayingControl(
                        label = when (state.repeat) {
                            MusicRepeatMode.OFF -> "Repeat: off"
                            MusicRepeatMode.ALL -> "Repeat: all"
                            MusicRepeatMode.ONE -> "Repeat: one"
                        },
                        focusKey = KEY_REPEAT,
                        enabled = true,
                        requesters = requesters,
                        onLeft = { step(KEY_REPEAT, -1) },
                        onRight = { step(KEY_REPEAT, +1) },
                        onUp = { focusKey(KEY_PROGRESS) },
                        onDown = { true },
                        onClick = { holder.cycleRepeat() },
                    )

                    NowPlayingIconControl(
                        glyph = MusicTransportGlyphs.Queue,
                        contentDescription = "Queue",
                        focusKey = KEY_QUEUE,
                        enabled = state.hasQueue && state.window != null,
                        requesters = requesters,
                        onLeft = { step(KEY_QUEUE, -1) },
                        onRight = { step(KEY_QUEUE, +1) },
                        onUp = { focusKey(KEY_PROGRESS) },
                        onDown = { true },
                        onClick = {

                            holder.revealWindow()
                            queueOpen = true
                        },
                    )

                    NowPlayingControl(
                        label = if (lyricsVisible) "Lyrics: on" else "Lyrics: off",
                        focusKey = KEY_LYRICS,
                        selected = lyricsVisible,
                        enabled = true,
                        requesters = requesters,
                        onLeft = { step(KEY_LYRICS, -1) },
                        onRight = { step(KEY_LYRICS, +1) },
                        onUp = { focusKey(KEY_PROGRESS) },
                        onDown = {
                            if (lyricsVisible) {
                                lyricsFocused = true
                                requesters.arm(lyricLineKey(0))
                                requesters.request(lyricLineKey(0))
                            }
                            true
                        },
                        onClick = {
                            presentation.lyricsVisible = !presentation.lyricsVisible
                            if (presentation.lyricsVisible) holder.ensureLyrics()
                        },
                    )
                    NowPlayingControl(
                        label = if (backdropEnabled) "Backdrop: on" else "Backdrop: off",
                        focusKey = KEY_BACKDROP,
                        selected = backdropEnabled,
                        enabled = true,
                        requesters = requesters,
                        onLeft = { step(KEY_BACKDROP, -1) },
                        onRight = { true },
                        onUp = { focusKey(KEY_PROGRESS) },
                        onDown = { true },
                        onClick = { presentation.backdropEnabled = !presentation.backdropEnabled },
                    )
                }
            }

        }
    }

    val queueWindow = state.window
    if (queueOpen && queueWindow != null) {
        dev.infusion.tv.music.MusicQueueOverlay(
            window = queueWindow,
            currentSlot = state.currentSlot,
            contextLabel = state.contextLabel,
            onReveal = { from, to -> holder.revealWindow(from, to) },
            onPlay = { slot -> holder.playOccurrence(slot) },
            onDismiss = { closeQueueOverlay() },
        )
    }

    LaunchedEffect(state.hasQueue) {
        if (!state.hasQueue && queueOpen) {
            queueOpen = false
            onBack()
        }
    }
}

private const val KEY_PROGRESS = "music-now:progress"
private const val KEY_PREVIOUS = "music-now:previous"
private const val KEY_PLAY_PAUSE = "music-now:play-pause"
private const val KEY_NEXT = "music-now:next"
private const val KEY_STOP = "music-now:stop"
private const val KEY_SHUFFLE = "music-now:shuffle"
private const val KEY_REPEAT = "music-now:repeat"
private const val KEY_QUEUE = "music-now:queue"
private const val KEY_LYRICS = "music-now:lyrics"
private const val KEY_BACKDROP = "music-now:backdrop"
private const val KEY_ERROR_RETRY = "music-now:retry"
private const val KEY_ERROR_SKIP = "music-now:skip"

private const val QUEUE_BACK_TAIL_MS = 350L
private const val SEEK_STEP_MS = 10_000L

private val SeekBarHeight = 16.dp

private val SeekThumbSize = 12.dp

private val NowPlayingInformationWidth = 248.dp

private val NowPlayingIconGlyphSize = 26.dp

private fun lyricLineKey(index: Int): String = "music-now:lyric:$index"

@Composable
private fun SeekRow(
    state: dev.infusion.feature.vertical.music.MusicQueueState,
    requesters: FocusKeyRegistry,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onDown: () -> Unit,
) {
    val duration = state.durationMs ?: 0L
    val seekable = state.canSeek && duration > 0L

    val accent = LocalInfusionAccent.current
    var focused by remember { mutableStateOf(false) }
    val fraction = if (duration > 0) {
        (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val barFill = when {
        focused -> accent
        seekable -> Color.White.copy(alpha = 1f)
        else -> Color.White.copy(alpha = 0.35f)
    }
    val barTrack = when {
        focused -> accent.copy(alpha = 0.30f)
        seekable -> Color.White.copy(alpha = 0.24f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .nowPlayingFocus(KEY_PROGRESS, requesters)

            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (seekable) onSeek((state.positionMs - SEEK_STEP_MS).coerceAtLeast(0L))
                        true
                    }
                    Key.DirectionRight -> {
                        if (seekable) onSeek((state.positionMs + SEEK_STEP_MS).coerceAtMost(duration))
                        true
                    }
                    Key.DirectionCenter -> {
                        onTogglePlayPause()
                        true
                    }
                    Key.DirectionDown -> {
                        onDown()
                        true
                    }
                    Key.DirectionUp -> true
                    else -> false
                }
            }
            .padding(8.dp)
            .testTag("now-playing-progress"),
    ) {
        Column {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SeekBarHeight),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .align(Alignment.CenterStart)

                        .background(barTrack),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .align(Alignment.CenterStart)
                        .background(barFill),
                )
                if (seekable) {

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = (maxWidth * fraction - SeekThumbSize / 2).coerceAtLeast(0.dp))
                            .size(SeekThumbSize)
                            .clip(CircleShape)
                            .background(if (focused) accent else Color.White)
                            .then(
                                if (focused) {

                                    Modifier.border(2.dp, Color.Black.copy(alpha = 0.6f), CircleShape)
                                } else {
                                    Modifier
                                },
                            )
                            .testTag("now-playing-progress-thumb"),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {

                Text(
                    text = formatTime(state.positionMs),
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                    color = Color.White.copy(alpha = if (seekable) 0.86f else 0.45f),
                )
                Text(
                    text = if (duration > 0) formatTime(duration) else "--:--",
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                    color = Color.White.copy(alpha = if (seekable) 0.86f else 0.45f),
                )
            }
        }
    }
}

@Composable
private fun NowPlayingIconControl(
    glyph: ImageVector,
    contentDescription: String,
    focusKey: String,
    enabled: Boolean,
    requesters: FocusKeyRegistry,
    onLeft: () -> Boolean,
    onRight: () -> Boolean,
    onUp: () -> Boolean,
    onDown: () -> Boolean,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    InfusionTvButton(
        onClick = onClick,
        enabled = enabled,
        selected = selected,
        interactionSource = interactionSource,
        modifier = Modifier
            .nowPlayingFocus(focusKey, requesters)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onLeft()
                    Key.DirectionRight -> onRight()
                    Key.DirectionUp -> onUp()
                    Key.DirectionDown -> onDown()
                    else -> false
                }
            },

        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = contentDescription,
            tint = if (selected && !focused) LocalInfusionAccent.current else LocalContentColor.current,
            modifier = Modifier.size(NowPlayingIconGlyphSize),
        )
    }
}

@Composable
private fun NowPlayingControl(
    label: String,
    focusKey: String,
    enabled: Boolean,
    requesters: FocusKeyRegistry,
    onLeft: () -> Boolean,
    onRight: () -> Boolean,
    onUp: () -> Boolean,
    onDown: () -> Boolean,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    InfusionTvButton(
        onClick = onClick,
        enabled = enabled,
        selected = selected,
        modifier = Modifier
            .nowPlayingFocus(focusKey, requesters)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onLeft()
                    Key.DirectionRight -> onRight()
                    Key.DirectionUp -> onUp()
                    Key.DirectionDown -> onDown()
                    else -> false
                }
            },
    ) {
        Text(label, style = InfusionTvScaleCalibration.Type.Emphasis, maxLines = 1)
    }
}

@Composable
private fun NowPlayingAction(
    label: String,
    focusKey: String,
    requesters: FocusKeyRegistry,
    onClick: () -> Unit,
) {
    InfusionTvButton(
        onClick = onClick,
        modifier = Modifier
            .nowPlayingFocus(focusKey, requesters)
            .testTag("now-playing-action-$label"),
    ) {
        Text(label, style = InfusionTvScaleCalibration.Type.Emphasis)
    }
}

@Composable
private fun LyricsPanel(
    state: MusicLyricsState,
    positionMs: Long,
    occurrenceId: dev.infusion.core.domain.music.QueueEntryId?,
    requesters: FocusKeyRegistry,
    onFocusEntered: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val activeIndex = state.activeLineIndex(positionMs)
    val warningColor = androidx.tv.material3.MaterialTheme.colorScheme.error
    val lines: List<String> = when (val lyrics = state.lyrics) {
        is MusicLyrics.Synced -> lyrics.lines.map { it.text }
        is MusicLyrics.Plain -> lyrics.lines
        MusicLyrics.None -> emptyList()
    }

    var userTookOver by remember(occurrenceId) { mutableStateOf(false) }

    LaunchedEffect(activeIndex, lines.size, state.phase, userTookOver) {
        if (state.phase == MusicLyricsPhase.READY && !userTookOver && activeIndex != null && lines.isNotEmpty()) {
            followActiveLyricLine(listState, activeIndex)
        }
    }

    Box(modifier = modifier.testTag("now-playing-lyrics")) {
        when (state.phase) {
            MusicLyricsPhase.LOADING -> Text(
                "Loading lyrics…",
                style = InfusionTvScaleCalibration.Type.DisplayNormal,
                color = Color.White.copy(alpha = 0.72f),
            )
            MusicLyricsPhase.UNAVAILABLE -> Text(
                "No lyrics for this track.",
                style = InfusionTvScaleCalibration.Type.DisplayNormal,
                color = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.testTag("now-playing-lyrics-unavailable"),
            )
            MusicLyricsPhase.FAILED -> Column {
                Text(
                    state.message ?: "Lyrics could not be loaded.",
                    style = InfusionTvScaleCalibration.Type.DisplayNormal,
                    color = warningColor,
                )
                Spacer(Modifier.height(8.dp))
                NowPlayingAction(
                    label = "Retry lyrics",
                    focusKey = "music-now:lyrics-retry",
                    requesters = requesters,
                    onClick = onRetry,
                )
            }
            MusicLyricsPhase.READY, MusicLyricsPhase.IDLE -> LazyColumn(state = listState) {
                itemsIndexed(lines) { index, line ->
                    val highlighted = activeIndex == index
                    Text(
                        text = line,
                        style = if (highlighted) {
                            InfusionTvScaleCalibration.Type.Display
                        } else {
                            InfusionTvScaleCalibration.Type.DisplayNormal
                        },
                        color = if (highlighted) Color.White else Color.White.copy(alpha = 0.62f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .nowPlayingFocus(lyricLineKey(index), requesters)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        onFocusEntered()
                                        userTookOver = true
                                        if (index < lines.lastIndex) {
                                            requesters.arm(lyricLineKey(index + 1))
                                            requesters.request(lyricLineKey(index + 1))
                                        }
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        onFocusEntered()
                                        userTookOver = true
                                        if (index > 0) {
                                            requesters.arm(lyricLineKey(index - 1))
                                            requesters.request(lyricLineKey(index - 1))
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

internal fun centeredLyricScrollOffset(viewportHeightPx: Int, activeLineHeightPx: Int): Int {
    val half = (viewportHeightPx - activeLineHeightPx) / 2

    return if (half > 0) -half else 0
}

private suspend fun followActiveLyricLine(listState: LazyListState, index: Int) {
    if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
        listState.scrollToItem(index)

        withFrameNanos { }
    }
    val info = listState.layoutInfo
    val lineHeight = info.visibleItemsInfo.firstOrNull { it.index == index }?.size
    val viewportHeight = info.viewportSize.height
    if (lineHeight == null || viewportHeight <= 0) {

        listState.animateScrollToItem(index)
        return
    }
    listState.animateScrollToItem(index, centeredLyricScrollOffset(viewportHeight, lineHeight))
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun Modifier.nowPlayingFocus(
    key: String,
    requesters: FocusKeyRegistry,
): Modifier = stableFocusModifier(
    key = key,
    requesters = requesters,
    onFocusedKeyChanged = {},
    topLevelContentBoundary = false,
)
