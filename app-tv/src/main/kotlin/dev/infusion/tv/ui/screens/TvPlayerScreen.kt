package dev.infusion.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import dev.infusion.core.playback.api.AudioLanguageTag
import dev.infusion.core.playback.api.AudioTrack
import dev.infusion.core.playback.api.ChapterNavigation
import dev.infusion.core.playback.api.PlaybackState
import dev.infusion.core.playback.api.RelativeSeekOutcome
import dev.infusion.core.playback.api.SegmentDisplayMode
import dev.infusion.core.playback.api.SkipIntroUi
import dev.infusion.core.playback.api.HdrSubtitleDimming
import dev.infusion.feature.vertical.model.NextEpisodeUi
import dev.infusion.core.playback.api.SubtitleAppearance
import dev.infusion.core.playback.api.SubtitleLanguagePreference
import dev.infusion.core.playback.api.SubtitleMode
import dev.infusion.core.playback.api.SubtitlePreferences
import dev.infusion.core.playback.api.SubtitleProvenance
import dev.infusion.core.playback.api.SubtitleTrack
import dev.infusion.core.playback.api.SubtitleTrackLabel
import dev.infusion.core.playback.api.TrackRole
import dev.infusion.core.playback.media3.Media3PlaybackBinder
import dev.infusion.core.playback.media3.SubtitleAppearanceBinder
import dev.infusion.feature.vertical.media.PersonCardUi
import dev.infusion.feature.vertical.model.PlayerSubordinateSurface
import dev.infusion.feature.vertical.model.PlayerUiState
import dev.infusion.feature.vertical.model.SubtitleAvailability
import dev.infusion.feature.vertical.player.PlayerMetadataPresentation
import dev.infusion.feature.vertical.player.PlayerStateHolder
import dev.infusion.tv.playback.DvUiCompensation
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvDetailsMetrics
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TvPlayerScreen(
    stateHolder: PlayerStateHolder,
    onBack: () -> Unit,
    onChooseSource: () -> Unit,
    onRetry: () -> Unit,

    onPlayNext: () -> Unit = {},

    onDismissUpNext: () -> Unit = {},

    metadata: PlayerMetadataPresentation = PlayerMetadataPresentation.NONE,

    onOpenCastPerson: (PersonCardUi) -> Unit = {},

    castReturnPersonKey: String? = null,
    onCastReturnConsumed: () -> Unit = {},

    now: () -> LocalTime = { LocalTime.now() },

    mediaKeysHandledBySession: () -> Boolean = { false },

    subtitleAppearance: SubtitleAppearance = SubtitleAppearance.DEFAULT,

    dimSubtitlesInHdr: HdrSubtitleDimming = HdrSubtitleDimming.DEFAULT,

    dvUiCompensation: DvUiCompensation = DvUiCompensation.Automatic,

    subtitlePreferences: SubtitlePreferences = SubtitlePreferences.DEFAULT,

    segmentDisplay: SegmentDisplayMode = SegmentDisplayMode.DEFAULT,

    segmentLegendEnabled: Boolean = true,

    preparation: PlaybackPreparationPresentation? = null,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()

    var browser by remember { mutableStateOf<PlayerBrowser?>(null) }

    var castRowOpen by remember { mutableStateOf(false) }
    val hiddenControlsFocus = remember { FocusRequester() }

    val contextualSkipFocus = remember { FocusRequester() }
    val requesters = rememberFocusRequesters()

    val seekFeedbackScope = rememberCoroutineScope()
    val seekFeedbackState = remember { TvPlayerSeekFeedbackState(seekFeedbackScope) }
    val seekFeedback by seekFeedbackState.feedback.collectAsStateWithLifecycle()

    val seekFeedbackSeconds by seekFeedbackState.seekSeconds.collectAsStateWithLifecycle()
    val chapterFeedback by seekFeedbackState.chapterFeedback.collectAsStateWithLifecycle()

    var lastSurfaceInvoker by remember { mutableStateOf<String?>(null) }

    var lastFocusedControlKey by remember { mutableStateOf<String?>(null) }

    var clockNow by remember { mutableStateOf(now()) }

    val attemptIdentity = state?.candidate
    LaunchedEffect(attemptIdentity) { seekFeedbackState.resetBurst() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ClockTickMillis)
            clockNow = now()
        }
    }

    val screenState = state

    val otherSurfaceOwnsFocus = browser != null || castRowOpen || screenState?.upNext != null
    val fullscreenSlot = fullscreenContextualSlot(
        next = screenState?.nextEpisode,
        skip = screenState?.skipIntro,
        controlsVisible = screenState?.controlsVisible == true,
        otherSurfaceOwnsFocus = otherSurfaceOwnsFocus,
    )
    val fullscreenNextOffered = fullscreenSlot.showNext
    val fullscreenSkipOffered = fullscreenSlot.showSkip

    val fullscreenNavigationEnabled = screenState?.controlsVisible == false && !otherSurfaceOwnsFocus

    val fullscreenSkipUi = if (fullscreenSkipOffered && !fullscreenNextOffered) screenState?.skipIntro else null

    val fullscreenNextUi = if (fullscreenNextOffered) screenState?.nextEpisode else null

    val contextualActionOffered = fullscreenNextOffered || fullscreenSkipOffered

    val preparationVisible = isPreparationVisible(preparation, screenState)

    val playbackUiDrawn = isPlaybackUiDrawn(
        controlsVisible = screenState?.controlsVisible == true,
        informationalVisible = screenState?.informationalVisible == true,
        buffering = screenState?.let { it.isBuffering && it.failure == null } == true,
        hasStinger = screenState?.stinger != null,
        hasUpNext = screenState?.upNext != null,
        castRowOpen = castRowOpen,
        browserOpen = browser != null,
        contextualActionOffered = contextualActionOffered,
        preparationVisible = preparationVisible,
    )

    LaunchedEffect(state?.controlsVisible, fullscreenSkipOffered, fullscreenNextOffered, otherSurfaceOwnsFocus) {
        if (state?.controlsVisible == false && !otherSurfaceOwnsFocus) {
            if (contextualActionOffered) {

                androidx.compose.runtime.withFrameNanos { }
                runCatching { contextualSkipFocus.requestFocus() }
            } else {
                hiddenControlsFocus.requestFocus()
            }
        }
    }

    var controlsRevealGeneration by remember { mutableStateOf(0) }
    LaunchedEffect(state?.controlsVisible) {
        if (state?.controlsVisible == true) controlsRevealGeneration++
    }
    LaunchedEffect(controlsRevealGeneration) {
        if (controlsRevealGeneration == 0) return@LaunchedEffect

        if (otherSurfaceOwnsFocus) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        val restored = requesters.requestFirst(lastFocusedControlKey, "retry", "play-pause")
        if (restored == null) {

            androidx.compose.runtime.withFrameNanos { }
            requesters.requestFirst(lastFocusedControlKey, "retry", "play-pause")
        }
    }

    LaunchedEffect(browser, castRowOpen) {
        stateHolder.setSubordinateSurface(
            when {
                browser == PlayerBrowser.AUDIO -> PlayerSubordinateSurface.AUDIO_BROWSER
                browser == PlayerBrowser.SUBTITLES -> PlayerSubordinateSurface.SUBTITLE_BROWSER
                castRowOpen -> PlayerSubordinateSurface.CAST_ROW
                else -> null
            },
        )
    }

    LaunchedEffect(castReturnPersonKey) {
        if (castReturnPersonKey != null) {
            castRowOpen = true
            onCastReturnConsumed()
        }
    }

    val current = state
    val cast = remember(metadata.cast) { metadata.cast.take(MaxCastMembers) }

    BackHandler {

        when {

            state?.upNext != null -> onDismissUpNext()
            castRowOpen -> {
                castRowOpen = false
            }
            browser != null -> {
                browser = null
            }

            fullscreenNextOffered -> stateHolder.dismissNextEpisode()

            fullscreenSkipOffered -> stateHolder.dismissSkipIntro()
            !stateHolder.dismissControlsOnBack() -> onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()

            .background(InfusionTvAppearance.OledBlack)
            .testTag("player-root")
            .focusRequester(hiddenControlsFocus)

            .then(
                if (state?.controlsVisible == false && !otherSurfaceOwnsFocus) Modifier.focusTarget()
                else Modifier,
            )
            .onPreviewKeyEvent { event ->
                handlePlaybackKey(
                    event = event,
                    stateHolder = stateHolder,
                    onSeekFeedback = { seekFeedbackState.showSeek(it) },
                    castRow = TvPlayerCastRowState(
                        isOpen = castRowOpen,
                        canOpen = cast.isNotEmpty() && browser == null,
                        lastFocusedControlKey = lastFocusedControlKey,
                    ),
                    onOpenCastRow = { invoker ->
                        lastSurfaceInvoker = invoker
                        castRowOpen = true
                    },
                    onCloseCastRow = { castRowOpen = false },
                    contextualActionActive = contextualActionOffered,
                    contextualNextActive = fullscreenNextOffered,
                    fullscreenNavigationEnabled = fullscreenNavigationEnabled,
                    otherSurfaceOwnsFocus = otherSurfaceOwnsFocus,
                    onChapterFeedback = { seekFeedbackState.showChapter(it) },
                    mediaKeysHandledBySession = mediaKeysHandledBySession,
                )
            },
    ) {

        val session = stateHolder.activePlaybackSession
        val livePlayer = session?.let { Media3PlaybackBinder.playerFor(it.sessionId) }

        val subtitleAppearanceBinder = remember { SubtitleAppearanceBinder() }

        val effectiveVideoTrack =
            dev.infusion.core.playback.api.SelectedTrackResolution
                .resolvedVideoTrack(screenState?.trackInventory)
        val dimSubtitleForeground =
            dev.infusion.core.playback.api.SubtitleHdrDimming
                .isDimmingActive(dimSubtitlesInHdr, effectiveVideoTrack)

        val playbackUiLuminanceScale =
            dev.infusion.core.playback.api.PlaybackUiDolbyVisionLuminance.effectiveScale(
                scale = dvUiCompensation.requestedScale,
                video = effectiveVideoTrack,
            )

        val holdDisplayOn = PlayerKeepAwake.shouldHoldDisplayOn(
            state = screenState,
            startupPresentationActive = preparation != null,
        )

        val keepAwakeHostView = LocalView.current
        SideEffect { keepAwakeHostView.keepScreenOn = holdDisplayOn }
        DisposableEffect(keepAwakeHostView) {
            onDispose { keepAwakeHostView.keepScreenOn = false }
        }
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    contentDescription = "Video surface"

                    keepScreenOn = holdDisplayOn

                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    findViewById<android.view.View>(
                        androidx.media3.ui.R.id.exo_content_frame,
                    )?.setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (view.player !== livePlayer) view.player = livePlayer
                subtitleAppearanceBinder.bind(view.subtitleView, subtitleAppearance, dimSubtitleForeground)

                view.keepScreenOn = holdDisplayOn
            },
            onRelease = { view ->
                view.player = null
                subtitleAppearanceBinder.reset()

                view.keepScreenOn = false
            },
        )

        PlaybackUiColourProvider(scale = playbackUiLuminanceScale) {
            PlaybackUiColourHost(modifier = Modifier.fillMaxSize(), active = playbackUiDrawn) {

                if (state?.isBuffering == true && state?.failure == null) {
                    Text(
                        "Buffering…",
                        modifier = Modifier.align(Alignment.Center).padding(8.dp),
                        style = InfusionTvScaleCalibration.Type.Body,
                    )
                }

                val upNext = state?.upNext
                if (upNext != null) {
                    TvUpNextOverlay(
                        state = state!!,
                        onPlayNext = onPlayNext,
                        onDismiss = onDismissUpNext,
                    )
                } else if (current != null) {

                    TvPlayerOverlay(
                        isControlsVisible = current.controlsVisible,
                        isInformationalVisible = current.informationalVisible,
                        seekFeedback = seekFeedback,
                        seekFeedbackSeconds = seekFeedbackSeconds,
                        chapterFeedback = chapterFeedback,
                        clock = formatPlaybackClock(clockNow),
                        endsAt = formatPlaybackEndsAt(
                            now = clockNow,
                            remainingMs = if (current.isLive) null else {
                                current.durationMs?.let { duration -> duration - current.positionMs }
                            },
                        ),
                        castAvailable = cast.isNotEmpty(),
                        metadata = metadata,

                        contextualActions = if (fullscreenNextUi != null || fullscreenSkipUi != null) {
                            {

                                if (fullscreenNextUi != null) {
                                    TvPlayerNextEpisodeControl(
                                        ui = fullscreenNextUi,
                                        onClick = { stateHolder.requestNextEpisode() },
                                        modifier = Modifier.focusRequester(contextualSkipFocus),
                                    )
                                } else if (fullscreenSkipUi != null) {
                                    TvPlayerSkipIntroControl(
                                        ui = fullscreenSkipUi,
                                        onClick = { stateHolder.skipIntro() },
                                        modifier = Modifier.focusRequester(contextualSkipFocus),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    ) {
                        PlayerControls(
                            state = current,
                            stateHolder = stateHolder,
                            metadata = metadata,
                            castAvailable = cast.isNotEmpty(),
                            showTransport = current.controlsVisible,
                            requesters = requesters,
                            segmentDisplay = segmentDisplay,
                            segmentLegendEnabled = segmentLegendEnabled,
                            onPlayPause = {
                                if (current.phase == PlaybackState.PLAYING) {
                                    seekFeedbackState.show(TvPlayerSeekFeedback.PAUSED)
                                }
                                stateHolder.playPause()
                            },
                            onBack = onBack,
                            onChooseSource = onChooseSource,
                            onRetry = onRetry,
                            onOpenBrowser = { picker ->

                                lastSurfaceInvoker = when (picker) {
                                    PlayerBrowser.AUDIO -> "audio"
                                    PlayerBrowser.SUBTITLES -> "subtitles"
                                }
                                browser = picker
                            },

                            controlsEnabled = browser == null && !castRowOpen,

                            restoreKey = lastSurfaceInvoker,
                            onFocusedKeyChanged = { lastFocusedControlKey = it },
                        )
                    }
                }

                current?.stinger?.let { stinger ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = StingerNoticeTopPadding),
                    ) {
                        TvPlayerStingerNotice(stinger)
                    }
                }

                if (castRowOpen && cast.isNotEmpty()) {
                    TvPlayerCastRow(
                        cast = cast,
                        requesters = requesters,
                        focusKey = castReturnPersonKey ?: firstNavigableCastKey(cast),
                        onOpenPerson = onOpenCastPerson,
                    )
                }
                current?.let { s ->
                    browser?.let { b ->
                        when (b) {

                            PlayerBrowser.AUDIO -> TvPlayerTrackBrowser(
                                title = "Audio",
                                entries = browserEntries(PlayerBrowser.AUDIO, s, stateHolder),
                                focusKey = browserFocusKey(PlayerBrowser.AUDIO, s),
                                requesters = requesters,
                            )

                            PlayerBrowser.SUBTITLES -> TvPlayerSubtitleBrowser(
                                title = "Subtitles",
                                modeEntries = browserEntries(PlayerBrowser.SUBTITLES, s, stateHolder),
                                groups = subtitleSourceGroups(s, stateHolder, subtitlePreferences),
                                entryKey = browserFocusKey(PlayerBrowser.SUBTITLES, s),
                                requesters = requesters,
                            )
                        }
                    }
                }

                if (preparation != null && preparationVisible) {
                    TvPlaybackPreparationOverlay(preparation)
                }
            }
        }

        if (dvUiCompensation.hasDebugOverride) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(DvUiCompensationIndicatorInset),
            ) {
                Text(
                    text = "DV-UI \u00D7" +
                        String.format(Locale.US, "%.2f", dvUiCompensation.requestedScale) +
                        if (dvUiCompensation.isBaseline) " (baseline)" else " (prototype)",
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(TvPlayerDvUiCompensationIndicatorTag),
                    color = Color.White,
                    style = InfusionTvScaleCalibration.Type.Body,
                )
            }
        }
    }
}

private const val ClockTickMillis = 10_000L

private val StingerNoticeTopPadding = 48.dp

private val DvUiCompensationIndicatorInset = 24.dp

internal const val TvPlayerDvUiCompensationIndicatorTag: String = "player-dv-ui-compensation-indicator"

internal const val MaxCastMembers = 20

internal val MEDIA_KEYS: Set<Key> = setOf(
    Key.MediaPlay,
    Key.MediaPause,
    Key.MediaPlayPause,
    Key.MediaFastForward,
    Key.MediaSkipForward,
    Key.MediaRewind,
    Key.MediaSkipBackward,
)

internal enum class PlayerBrowser { AUDIO, SUBTITLES }

internal class PlayerBrowserEntry(
    val key: String,
    val primary: String,
    val detail: String?,
    val selected: Boolean,
    val select: () -> Unit,
)

internal data class TvPlayerCastRowState(
    val isOpen: Boolean,
    val canOpen: Boolean,
    val lastFocusedControlKey: String?,
)

internal fun shouldRevealControlsOnCenter(
    controlsVisible: Boolean,
    contextualActionActive: Boolean,
): Boolean = !controlsVisible && !contextualActionActive

internal fun startupRevealReady(
    firstFrameRendered: Boolean,
    initialTrackSetupSettled: Boolean,
    initialDisplayMatchSettled: Boolean,
): Boolean = firstFrameRendered && initialTrackSetupSettled && initialDisplayMatchSettled

internal fun isPreparationVisible(
    preparation: PlaybackPreparationPresentation?,
    state: PlayerUiState?,
): Boolean {
    if (preparation == null) return false
    val revealReady = startupRevealReady(
        firstFrameRendered = state?.startupFrameReady == true,
        initialTrackSetupSettled = state?.initialTrackSetupSettled == true,
        initialDisplayMatchSettled = state?.initialDisplayMatchSettled == true,
    )
    val suppressed = state?.failure != null ||
        state?.phase == PlaybackState.CANCELLED ||
        state?.phase == PlaybackState.ENDED
    return !revealReady && !suppressed
}

internal fun isPlaybackUiDrawn(
    controlsVisible: Boolean,
    informationalVisible: Boolean,
    buffering: Boolean,
    hasStinger: Boolean,
    hasUpNext: Boolean,
    castRowOpen: Boolean,
    browserOpen: Boolean,
    contextualActionOffered: Boolean,
    preparationVisible: Boolean,
): Boolean = controlsVisible ||
    informationalVisible ||
    buffering ||
    hasStinger ||
    hasUpNext ||
    castRowOpen ||
    browserOpen ||
    contextualActionOffered ||
    preparationVisible

internal fun fullscreenContextualActionOffered(
    skip: SkipIntroUi?,
    controlsVisible: Boolean,
    otherSurfaceOwnsFocus: Boolean,
): Boolean = skip != null && !skip.fullscreenDismissed && !controlsVisible && !otherSurfaceOwnsFocus

internal fun fullscreenNextActionOffered(
    next: NextEpisodeUi?,
    controlsVisible: Boolean,
    otherSurfaceOwnsFocus: Boolean,
): Boolean = next != null && !next.fullscreenDismissed && !controlsVisible && !otherSurfaceOwnsFocus

internal data class FullscreenContextualSlot(
    val showNext: Boolean,
    val showSkip: Boolean,
) {
    val any: Boolean get() = showNext || showSkip
}

internal fun fullscreenContextualSlot(
    next: NextEpisodeUi?,
    skip: SkipIntroUi?,
    controlsVisible: Boolean,
    otherSurfaceOwnsFocus: Boolean,
): FullscreenContextualSlot {
    val nextOffered = fullscreenNextActionOffered(next, controlsVisible, otherSurfaceOwnsFocus)
    val skipOffered = fullscreenContextualActionOffered(skip, controlsVisible, otherSurfaceOwnsFocus)
    return FullscreenContextualSlot(showNext = nextOffered, showSkip = skipOffered && !nextOffered)
}

internal fun handlePlaybackKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    stateHolder: PlayerStateHolder,
    onSeekFeedback: (RelativeSeekOutcome) -> Unit = {},
    castRow: TvPlayerCastRowState = TvPlayerCastRowState(false, false, null),
    onOpenCastRow: (String?) -> Unit = {},
    onCloseCastRow: () -> Unit = {},

    contextualActionActive: Boolean = false,

    contextualNextActive: Boolean = false,

    fullscreenNavigationEnabled: Boolean = false,

    otherSurfaceOwnsFocus: Boolean = false,

    onChapterFeedback: (TvPlayerChapterFeedback) -> Unit = {},

    mediaKeysHandledBySession: () -> Boolean = { false },
): Boolean {
    if (event.type != KeyEventType.KeyUp) return false

    if (event.key in MEDIA_KEYS && mediaKeysHandledBySession()) return true
    val controlsVisible = stateHolder.state.value?.controlsVisible == true

    if (castRow.isOpen) {
        return when (event.key) {
            Key.DirectionUp -> {
                onCloseCastRow()
                true
            }
            Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
            Key.DirectionCenter, Key.Enter,
            -> false
            else -> false
        }
    }
    if (controlsVisible && castRow.canOpen) {
        when (event.key) {
            Key.DirectionDown -> {
                onOpenCastRow(castRow.lastFocusedControlKey)
                return true
            }
        }
    }

    val isSelect = event.key == Key.DirectionCenter || event.key == Key.Enter ||
        event.key == Key.NumPadEnter || event.key == Key.ButtonSelect || event.key == Key.ButtonA
    if (!isSelect) {
        stateHolder.cancelSkipCountdown()
        stateHolder.dismissStinger()

        stateHolder.cancelNextEpisodeAutomatic()
    }
    return when (event.key) {
        Key.Back -> {

            if (contextualNextActive) {
                stateHolder.dismissNextEpisode()
                true
            } else if (contextualActionActive) {
                stateHolder.dismissSkipIntro()
                true
            } else false
        }
        Key.MediaPlay, Key.MediaPause, Key.MediaPlayPause -> {
            stateHolder.playPause()
            true
        }
        Key.MediaFastForward, Key.MediaSkipForward -> {
            stateHolder.seekForward()
            true
        }
        Key.MediaRewind, Key.MediaSkipBackward -> {
            stateHolder.seekBackward()
            true
        }
        Key.DirectionLeft, Key.SystemNavigationLeft -> {
            if (stateHolder.state.value?.controlsVisible == false && !otherSurfaceOwnsFocus) {

                stateHolder.seekBackward()?.let(onSeekFeedback)
                true
            } else false
        }
        Key.DirectionRight, Key.SystemNavigationRight -> {
            if (stateHolder.state.value?.controlsVisible == false && !otherSurfaceOwnsFocus) {
                stateHolder.seekForward()?.let(onSeekFeedback)
                true
            } else false
        }
        Key.DirectionUp, Key.SystemNavigationUp -> {

            if (fullscreenNavigationEnabled) {
                navigateFullscreenChapter(stateHolder, forward = true, contextualActionActive, onChapterFeedback)
                true
            } else false
        }
        Key.DirectionDown, Key.SystemNavigationDown -> {
            if (fullscreenNavigationEnabled) {
                navigateFullscreenChapter(stateHolder, forward = false, contextualActionActive, onChapterFeedback)
                true
            } else false
        }
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        Key.ButtonSelect,
        Key.ButtonA,
        -> {
            val visible = stateHolder.state.value?.controlsVisible == true

            if (!otherSurfaceOwnsFocus &&
                shouldRevealControlsOnCenter(controlsVisible = visible, contextualActionActive = contextualActionActive)
            ) {
                stateHolder.showControls()
                true
            } else false
        }
        else -> false
    }
}

private fun navigateFullscreenChapter(
    stateHolder: PlayerStateHolder,
    forward: Boolean,
    dismissFullscreenSkip: Boolean,
    onChapterFeedback: (TvPlayerChapterFeedback) -> Unit,
) {
    if (dismissFullscreenSkip) stateHolder.dismissSkipIntro()
    val destination = stateHolder.navigateChapter(forward) ?: return
    onChapterFeedback(chapterFeedbackFor(destination))
}

internal fun chapterFeedbackFor(destination: ChapterNavigation.Destination): TvPlayerChapterFeedback {
    val insideLabel = if (destination.fallback) {
        if (destination.forward) "+5 min" else "-5 min"
    } else {
        "Chapter ${destination.chapterNumber}"
    }
    val belowLabel = destination.chapterTitle ?: formatPlaybackTime(destination.positionMs)
    return TvPlayerChapterFeedback(
        forward = destination.forward,
        insideLabel = insideLabel,
        belowLabel = belowLabel,
    )
}

@Composable
private fun PlayerControls(
    state: PlayerUiState,
    stateHolder: PlayerStateHolder,
    metadata: PlayerMetadataPresentation,
    castAvailable: Boolean,
    showTransport: Boolean,
    requesters: FocusKeyRegistry,
    onPlayPause: () -> Unit,
    onBack: () -> Unit,
    onChooseSource: () -> Unit,
    onRetry: () -> Unit,
    onOpenBrowser: (PlayerBrowser) -> Unit,

    segmentDisplay: SegmentDisplayMode = SegmentDisplayMode.DEFAULT,

    segmentLegendEnabled: Boolean = true,

    controlsEnabled: Boolean = true,

    restoreKey: String? = null,
    onFocusedKeyChanged: (String) -> Unit = {},
) {
    val failure = state.failure

    RequestFocusOnKey(
        key = when {
            !controlsEnabled -> null
            restoreKey != null -> restoreKey
            failure != null -> "retry"

            state.phase == PlaybackState.ENDED -> null
            else -> "play-pause"
        },
        requesters = requesters,
    )
    val controlModifier: @Composable (String) -> Modifier = { key ->
        if (controlsEnabled) stableFocusModifier(key, requesters, onFocusedKeyChanged) else Modifier
    }
    val ended = state.phase == PlaybackState.ENDED
    val playing = state.phase == PlaybackState.PLAYING

    if (!showTransport) {

        Column(Modifier.fillMaxWidth()) {
            TvPlayerCanonicalSuite(metadata = metadata)
        }
        return
    }

    TvPlayerMainFrame(
        mediaHeader = {
            TvPlayerCanonicalSuite(metadata = metadata)
        },
        mediaActions = {
            when {
                failure != null -> {
                    InfusionTvButton(
                        onClick = onRetry,
                        modifier = controlModifier("retry"),
                    ) { Text("Retry") }
                    InfusionTvButton(
                        onClick = onChooseSource,
                        modifier = controlModifier("choose-source"),
                    ) { Text(TvDetailsActionLabels.Sources) }
                    InfusionTvButton(
                        onClick = onBack,
                        modifier = controlModifier("back"),
                    ) { Text("Back") }
                }
                ended -> {

                }
                else -> {

                    state.nextEpisode?.let { next ->
                        TvPlayerNextEpisodeControl(
                            ui = next,
                            onClick = { stateHolder.requestNextEpisode() },
                            modifier = controlModifier("next-episode"),
                        )
                    }

                    state.skipIntro?.let { skip ->
                        TvPlayerSkipIntroControl(
                            ui = skip,
                            onClick = { stateHolder.skipIntro() },
                            modifier = controlModifier("skip-intro"),
                        )
                    }
                    TvPlayerIconButton(
                        glyph = TvPlayerGlyphs.Audio,
                        contentDescription = "Audio",
                        onClick = { onOpenBrowser(PlayerBrowser.AUDIO) },
                        modifier = controlModifier("audio"),
                    )
                    TvPlayerIconButton(
                        glyph = TvPlayerGlyphs.Subtitles,
                        contentDescription = "Subtitles",
                        onClick = { onOpenBrowser(PlayerBrowser.SUBTITLES) },
                        modifier = controlModifier("subtitles"),
                    )
                }
            }
        },
        seeker = {

            if (failure == null && !ended) {
                TvPlayerSeeker(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    bufferedPositionMs = state.bufferedPositionMs,
                    playPauseGlyph = if (playing) TvPlayerGlyphs.Pause else TvPlayerGlyphs.Play,
                    playPauseContentDescription = if (playing) "Pause" else "Play",
                    onPlayPause = onPlayPause,
                    playPauseModifier = controlModifier("play-pause"),

                    segments = state.segments,
                    displayMode = segmentDisplay,
                    legendEnabled = segmentLegendEnabled,
                )
            }
        },
        more = if (failure == null && !ended) {
            {

                val playingVideo = dev.infusion.core.playback.api.SelectedTrackResolution
                    .resolvedVideoTrack(state.trackInventory)
                TvPlayerInfoLineRow(
                    video = playingVideo,
                    audio = resolvedAudioTrack(state),
                    bitrate = dev.infusion.core.playback.api.PlayingMediaBitrateEvidence.resolve(
                        selectedVideoBitsPerSecond = playingVideo?.bitrateBitsPerSecond,
                        declaredTotalBitsPerSecond = state.candidate.facts.bitrateBps,
                        totalMediaBytes = state.candidate.facts.sizeBytes,
                        fullMediaDurationMs = state.engineReportedDurationMs,
                    ),
                ) {

                    if (state.isBuffering) {
                        Text("Buffering…", style = InfusionTvScaleCalibration.Type.Body)
                    }
                    SelectedTracks(state)
                }
            }
        } else {
            null
        },

        bottomIndicator = if (failure == null && !ended && castAvailable) {
            { TvPlayerCastAffordance() }
        } else {
            null
        },
    )
}

@Composable
private fun TvPlayerTrackBrowser(
    title: String,
    entries: List<PlayerBrowserEntry>,
    focusKey: String?,
    requesters: FocusKeyRegistry,
) {
    val listState = rememberLazyListState()
    val focusIndex = entries.indexOfFirst { it.key == focusKey }.coerceAtLeast(0)

    LaunchedEffect(focusKey, entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        val alreadyVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == focusIndex }
        if (!alreadyVisible) listState.scrollToItem(focusIndex)
    }
    RequestFocusOnKey(key = focusKey, requesters = requesters)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .focusGroup()
                .padding(horizontal = InfusionTvDetailsMetrics.PageHorizontalPadding)
                .width(TrackBrowserWidth)
                .heightIn(max = TrackBrowserMaxHeight)
                .background(
                    color = InfusionTvAppearance.OledBlack.copy(alpha = TrackBrowserScrimAlpha),
                    shape = InfusionTvContour.Shape,
                )
                .padding(TrackBrowserPadding)
                .testTag(PlayerTrackBrowserTag),
            verticalArrangement = Arrangement.spacedBy(TrackBrowserRowGap),
        ) {
            Text(title, style = InfusionTvScaleCalibration.Type.Emphasis)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(TrackBrowserRowGap),
            ) {
                items(entries, key = { it.key }) { entry ->
                    InfusionTvButton(
                        onClick = { entry.select() },
                        modifier = stableFocusModifier(entry.key, requesters) {},
                    ) {
                        Column {
                            Text(
                                if (entry.selected) "${entry.primary}  ✓" else entry.primary,
                                style = InfusionTvScaleCalibration.Type.Emphasis,
                            )
                            entry.detail?.let {
                                Text(it, style = InfusionTvScaleCalibration.Type.Body)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val TrackBrowserWidth = 360.dp
private val TrackBrowserMaxHeight = 300.dp
private val TrackBrowserPadding = 12.dp
private val TrackBrowserRowGap = 6.dp
private const val TrackBrowserScrimAlpha = 0.9f

@Composable
private fun TvPlayerSubtitleBrowser(
    title: String,
    modeEntries: List<PlayerBrowserEntry>,
    groups: List<PlayerSubtitleSourceGroup>,
    entryKey: String?,
    requesters: FocusKeyRegistry,
) {

    var focusedSourceKey by remember {
        mutableStateOf(groups.owningSourceOf(entryKey) ?: groups.firstOrNull()?.key)
    }
    var focusedKey by remember { mutableStateOf(entryKey) }
    val resultsState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(groups, entryKey) {
        if (groups.none { it.key == focusedSourceKey }) {
            focusedSourceKey = groups.owningSourceOf(entryKey) ?: groups.firstOrNull()?.key
        }
    }
    val focusedGroup = groups.firstOrNull { it.key == focusedSourceKey } ?: groups.firstOrNull()

    LaunchedEffect(focusedGroup?.key) {
        resultsState.scrollToItem(0)
    }

    LaunchedEffect(focusedGroup?.key, focusedKey, focusedGroup?.entries?.size) {
        val index = focusedGroup?.entries?.indexOfFirst { it.key == focusedKey } ?: -1
        if (index < 0) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        val visible = resultsState.layoutInfo.visibleItemsInfo.any { it.index == index }
        if (!visible) resultsState.scrollToItem(index)
    }
    RequestFocusOnKey(key = entryKey, requesters = requesters)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .focusGroup()
                .padding(horizontal = InfusionTvDetailsMetrics.PageHorizontalPadding)
                .width(TrackBrowserWidth)
                .heightIn(max = TrackBrowserMaxHeight)
                .background(
                    color = InfusionTvAppearance.OledBlack.copy(alpha = TrackBrowserScrimAlpha),
                    shape = InfusionTvContour.Shape,
                )
                .padding(TrackBrowserPadding)
                .testTag(PlayerTrackBrowserTag)

                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val direction = when (event.key) {
                        Key.DirectionLeft, Key.SystemNavigationLeft -> SubtitleBrowserDirection.LEFT
                        Key.DirectionRight, Key.SystemNavigationRight -> SubtitleBrowserDirection.RIGHT
                        else -> null
                    } ?: return@onPreviewKeyEvent false
                    val target = subtitleBrowserCrossColumnTarget(direction, focusedKey, groups)
                        ?: return@onPreviewKeyEvent false
                    if (subtitleBrowserColumnOf(target) == SubtitleBrowserColumn.RESULTS) {

                        val index = focusedGroup?.entries?.indexOfFirst { it.key == target } ?: -1
                        scope.launch {
                            if (index >= 0) resultsState.scrollToItem(index)
                            requesters.request(target)
                        }
                    } else {
                        requesters.request(target)
                    }
                    true
                },
            verticalArrangement = Arrangement.spacedBy(TrackBrowserRowGap),
        ) {
            Text(title, style = InfusionTvScaleCalibration.Type.Emphasis)

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {

                Column(
                    modifier = Modifier
                        .width(SubtitleSourceColumnWidth)
                        .fillMaxHeight(),
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(PlayerSubtitleModesTag),
                        horizontalArrangement = Arrangement.spacedBy(TrackBrowserRowGap),
                    ) {
                        modeEntries.forEach { entry ->
                            InfusionTvButton(
                                onClick = { entry.select() },
                                modifier = stableFocusModifier(entry.key, requesters) { focusedKey = entry.key }
                                    .weight(1f),
                                contentPadding = SubtitleModeRowPadding,
                            ) {
                                Text(
                                    if (entry.selected) "${entry.primary}  ✓" else entry.primary,
                                    style = InfusionTvScaleCalibration.Type.Emphasis,
                                )
                            }
                        }
                    }
                    if (groups.isEmpty()) {

                        Text(
                            "No matching subtitles for this stream.",
                            style = InfusionTvScaleCalibration.Type.Body,
                        )
                    } else {

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag(PlayerSubtitleSourcesTag),
                            verticalArrangement = Arrangement.spacedBy(TrackBrowserRowGap),
                        ) {
                            items(groups, key = { it.key }) { group ->
                                val active = group.key == focusedSourceKey
                                InfusionTvButton(
                                    onClick = {

                                        val first = group.entries.firstOrNull()?.key
                                        focusedSourceKey = group.key
                                        if (first != null) {
                                            scope.launch {
                                                resultsState.scrollToItem(0)
                                                requesters.request(first)
                                            }
                                        }
                                    },
                                    modifier = stableFocusModifier(group.key, requesters) {
                                        focusedKey = group.key
                                        focusedSourceKey = group.key
                                    }
                                        .fillMaxWidth()

                                        .semantics { selected = active },
                                    selected = active,
                                ) {
                                    Text(group.label, style = InfusionTvScaleCalibration.Type.Emphasis)
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = SubtitleColumnGap)
                        .width(SubtitleSeparatorThickness)
                        .fillMaxHeight()
                        .background(InfusionTvAppearance.PanelBorder)
                        .testTag(PlayerSubtitleSeparatorTag),
                )

                val results = focusedGroup?.entries.orEmpty()
                LazyColumn(
                    state = resultsState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag(PlayerSubtitleResultsTag),
                    verticalArrangement = Arrangement.spacedBy(TrackBrowserRowGap),
                ) {
                    items(results, key = { it.key }) { entry ->
                        InfusionTvButton(
                            onClick = { entry.select() },
                            modifier = stableFocusModifier(entry.key, requesters) {
                                focusedKey = entry.key
                                focusedGroup?.key?.let { key -> focusedSourceKey = key }
                            }
                                .fillMaxWidth(),
                        ) {
                            Column {
                                Text(
                                    if (entry.selected) "${entry.primary}  ✓" else entry.primary,
                                    style = InfusionTvScaleCalibration.Type.Emphasis,
                                )
                                entry.detail?.let {
                                    Text(it, style = InfusionTvScaleCalibration.Type.Body)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val SubtitleSourceColumnWidth = 132.dp

private val SubtitleColumnGap = 8.dp

private val SubtitleSeparatorThickness = 1.dp

private val SubtitleModeRowPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)

internal const val PlayerSubtitleSourcesTag: String = "player:subtitle-sources"

internal const val PlayerSubtitleModesTag: String = "player:subtitle-modes"

internal const val PlayerSubtitleResultsTag: String = "player:subtitle-results"

internal const val PlayerSubtitleSeparatorTag: String = "player:subtitle-separator"

private fun List<PlayerSubtitleSourceGroup>.owningSourceOf(key: String?): String? =
    key?.let { target -> firstOrNull { group -> group.entries.any { it.key == target } }?.key }

internal const val PlayerTrackBrowserTag: String = "player:track-browser"

@Composable
private fun TvPlayerCastRow(
    cast: List<PersonCardUi>,
    requesters: FocusKeyRegistry,
    focusKey: String?,
    onOpenPerson: (PersonCardUi) -> Unit,
) {
    RequestFocusOnKey(key = focusKey, requesters = requesters)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .background(InfusionTvAppearance.OledBlack.copy(alpha = CastRowScrimAlpha))
                .padding(bottom = CastRowBottomPadding),
        ) {
            TvCanonicalCastRow(
                cast = cast,
                requesters = requesters,
                onOpenPerson = onOpenPerson,
            )
        }
    }
}

private const val CastRowScrimAlpha = 0.85f
private val CastRowBottomPadding = 150.dp

internal fun firstNavigableCastKey(cast: List<PersonCardUi>): String? =
    cast.firstNotNullOfOrNull { it.activationKey }

@Composable
private fun SelectedTracks(state: PlayerUiState) {
    val audio = resolvedAudioTrack(state)

    val subtitle = resolvedSubtitleTrack(state)

    TvPlayerSelectedTracksLine(
        audioSummary = audioSummaryLine(audio),
        subtitleSummary = subtitleSummaryLine(state, subtitle),
    )
}

internal const val AudioInfoPrefix: String = "Audio: "

internal const val SubtitleInfoPrefix: String = "  ·  Subtitles: "

@Composable
internal fun TvPlayerSelectedTracksLine(
    audioSummary: String,
    subtitleSummary: String,
) {
    val style = InfusionTvScaleCalibration.Type.Body
    val measurer = rememberTextMeasurer()
    Layout(
        modifier = Modifier.testTag(PlayerTracksInfoTag),
        content = {
            Text(text = AudioInfoPrefix, style = style, maxLines = 1)
            Text(
                text = audioSummary,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(PlayerAudioInfoTag),
            )
            Text(text = SubtitleInfoPrefix, style = style, maxLines = 1)
            Text(
                text = subtitleSummary,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(PlayerSubtitleInfoTag),
            )
        },
    ) { measurables, constraints ->

        val prefix = measurables[0].measure(Constraints())
        val separator = measurables[2].measure(Constraints())
        val fixedWidth = prefix.width + separator.width

        val availableForLabels = (constraints.maxWidth - fixedWidth).coerceAtLeast(0)
        val allocation = TvPlayerFooterLayout.allocateLabelWidths(
            available = availableForLabels,
            audioNatural = naturalLabelWidth(measurer, audioSummary, style),
            subtitleNatural = naturalLabelWidth(measurer, subtitleSummary, style),
        )
        val audio = measurables[1].measure(Constraints(maxWidth = allocation.first))
        val subtitle = measurables[3].measure(Constraints(maxWidth = allocation.second))
        val width = fixedWidth + audio.width + subtitle.width
        val height = maxOf(prefix.height, audio.height, separator.height, subtitle.height)
        layout(width, height) {
            prefix.placeRelative(0, (height - prefix.height) / 2)
            audio.placeRelative(prefix.width, (height - audio.height) / 2)
            separator.placeRelative(prefix.width + audio.width, (height - separator.height) / 2)
            subtitle.placeRelative(
                prefix.width + audio.width + separator.width,
                (height - subtitle.height) / 2,
            )
        }
    }
}

private fun naturalLabelWidth(measurer: TextMeasurer, text: String, style: TextStyle): Int =
    measurer.measure(
        text = text,
        style = style,
        overflow = TextOverflow.Clip,
        softWrap = false,
        maxLines = 1,
        constraints = Constraints(),
    ).size.width

internal fun resolvedAudioTrack(state: PlayerUiState): AudioTrack? =
    dev.infusion.core.playback.api.SelectedTrackResolution.resolvedAudioTrack(
        state.trackInventory,
        state.trackSelection.audioTrackId,
    )

internal fun resolvedSubtitleTrack(state: PlayerUiState): SubtitleTrack? =
    dev.infusion.core.playback.api.SelectedTrackResolution.resolvedSubtitleTrack(
        state.trackInventory,
        state.trackSelection.subtitleTrackId.takeIf { state.trackSelection.subtitleMode == SubtitleMode.SELECTED },
    )

internal fun audioSummaryLine(track: AudioTrack?): String {
    if (track == null) return "default"
    val primary = track.label?.takeIf { it.isNotBlank() }
    val language = languageDisplayName(track.language)
    return when {
        primary == null -> language ?: track.language?.takeIf { it.isNotBlank() } ?: track.id.value
        language == null -> primary
        primary.equals(language, ignoreCase = true) -> primary
        else -> "$primary$GenreSeparator$language"
    }
}

internal fun subtitleSummaryLine(state: PlayerUiState, subtitle: SubtitleTrack?): String = when {

    state.subtitleNoEligible -> "no subtitle matched your subtitle settings"
    state.trackSelection.subtitleMode == SubtitleMode.OFF && subtitle == null -> "Off"
    subtitle != null -> languageDisplayName(subtitle.language)
        ?: subtitle.language?.takeIf { it.isNotBlank() }
        ?: "On"
    state.subtitleAvailability is SubtitleAvailability.Unavailable ->
        "unavailable (${(state.subtitleAvailability as SubtitleAvailability.Unavailable).reason})"
    state.subtitleAvailability is SubtitleAvailability.Unknown -> "unknown"
    else -> "default"
}

internal fun languageDisplayName(tag: String?): String? {
    val raw = tag?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val primary = raw.substringBefore('-').substringBefore('_').lowercase()
    if (primary.isBlank()) return null
    val locale = isoLanguageLocale(primary) ?: return null
    val name = locale.getDisplayLanguage()
    if (name.isBlank() || name.equals(locale.language, ignoreCase = true)) return null
    return name
}

private fun isoLanguageLocale(primary: String): Locale? {
    if (primary.length == 2) return Locale(primary)
    if (primary.length != 3) return null
    return iso3ToLocale.getOrPut(primary) {
        Locale.getISOLanguages()
            .firstOrNull { code -> runCatching { Locale(code).getISO3Language() }.getOrNull() == primary }
            ?.let { code -> Locale(code) }
            ?: Locale(primary)
    }
}

private val iso3ToLocale = ConcurrentHashMap<String, Locale>()

private fun browserEntries(
    browser: PlayerBrowser,
    state: PlayerUiState,
    stateHolder: PlayerStateHolder,
): List<PlayerBrowserEntry> = when (browser) {
    PlayerBrowser.AUDIO -> {
        val tracks = state.trackInventory?.audioTracks.orEmpty()
        if (tracks.isEmpty()) {
            listOf(
                PlayerBrowserEntry(
                    key = "audio-none",
                    primary = "Audio tracks unknown or unavailable.",
                    detail = null,
                    selected = false,
                    select = {},
                ),
            )
        } else {

            val shownSelected = resolvedAudioTrack(state)?.id
            tracks.map { track ->
                PlayerBrowserEntry(
                    key = "audio:${track.id.value}",
                    primary = track.label ?: track.language ?: track.id.value,
                    detail = audioTrackDetailLine(track),
                    selected = shownSelected == track.id,

                    select = { stateHolder.selectAudioTrack(track.id) },
                )
            }
        }
    }
    PlayerBrowser.SUBTITLES -> buildList {

        val renderedSubtitle = resolvedSubtitleTrack(state)?.id

        add(
            PlayerBrowserEntry(
                key = "subtitle-off",
                primary = "Off",
                detail = null,
                selected = state.trackSelection.subtitleMode == SubtitleMode.OFF && renderedSubtitle == null,
                select = { stateHolder.setSubtitleMode(SubtitleMode.OFF) },
            ),
        )
        add(
            PlayerBrowserEntry(
                key = "subtitle-auto",
                primary = "Auto",
                detail = null,
                selected = state.trackSelection.subtitleMode == SubtitleMode.AUTO && renderedSubtitle == null,
                select = { stateHolder.setSubtitleMode(SubtitleMode.AUTO) },
            ),
        )

    }
}

internal class PlayerSubtitleSourceGroup(
    val key: String,
    val label: String,
    val entries: List<PlayerBrowserEntry>,
)

internal const val SubtitleSourceEmbedded: String = "Embedded"

internal const val SubtitleSourceExternal: String = "External"

private const val SubtitleSourceEmbeddedKey: String = "subtitle-source:embedded"

private const val SubtitleSourceExternalKey: String = "subtitle-source:external"

private const val SubtitleSourceAddonPrefix: String = "subtitle-source:addon:"

internal fun subtitleSourceGroups(
    state: PlayerUiState,
    stateHolder: PlayerStateHolder,
    preferences: SubtitlePreferences = SubtitlePreferences.DEFAULT,
): List<PlayerSubtitleSourceGroup> {
    val tracks = state.trackInventory?.subtitleTracks.orEmpty()
    if (tracks.isEmpty()) return emptyList()
    val renderedId = resolvedSubtitleTrack(state)?.id
    val primary = (preferences.primary as? SubtitleLanguagePreference.Tag)?.value
    val secondary = (preferences.secondary as? SubtitleLanguagePreference.Tag)?.value

    fun tierRank(track: SubtitleTrack): Int? = when {
        primary == null && secondary == null -> 0
        primary != null && AudioLanguageTag.baseEquals(track.language, primary) -> 1
        secondary != null && AudioLanguageTag.baseEquals(track.language, secondary) -> 2
        else -> null
    }

    val chosen = tracks.withIndex().mapNotNull { (index, track) ->
        val rank = tierRank(track)
        val rendered = track.id == renderedId
        val supported = track.supported != false
        when {
            rank != null && supported -> Triple(index, rank, track)

            rendered -> Triple(index, Int.MAX_VALUE, track)
            else -> null
        }
    }

    val bySource = chosen.groupBy { (_, _, track) -> subtitleSourceKey(track) }
    return bySource.entries
        .sortedWith(compareBy({ subtitleSourceOrder(it.key) }, { it.value.first().first }))
        .map { (key, rows) ->
            val ordered = rows.sortedWith(compareBy({ it.second }, { it.first }))
            val groupTracks = ordered.map { it.third }
            val languageNames = groupTracks.map { languageDisplayName(it.language) }
            val nameTotals = languageNames.filterNotNull().groupingBy { it }.eachCount()
            val nameSeen = HashMap<String, Int>()
            PlayerSubtitleSourceGroup(
                key = key,
                label = subtitleSourceLabel(groupTracks.first()),
                entries = ordered.mapIndexed { position, (_, _, track) ->
                    val name = languageNames[position]
                    val primaryText = if (name != null) {
                        val ordinal = (nameSeen[name] ?: 0) + 1
                        nameSeen[name] = ordinal
                        if ((nameTotals[name] ?: 0) > 1) "$name $ordinal" else name
                    } else {

                        SubtitleTrackLabel.single(track)
                    }
                    PlayerBrowserEntry(
                        key = "subtitle:${track.id.value}",
                        primary = primaryText,
                        detail = subtitleResultDetailLine(track),
                        selected = track.id == renderedId,
                        select = { stateHolder.selectSubtitleTrack(track.id) },
                    )
                },
            )
        }
}

internal fun subtitleSourceKey(track: SubtitleTrack): String =
    if (track.provenance == SubtitleProvenance.SIDECAR) {
        track.sourceName?.takeIf { it.isNotBlank() }
            ?.let { name -> "$SubtitleSourceAddonPrefix$name" }
            ?: SubtitleSourceExternalKey
    } else {
        SubtitleSourceEmbeddedKey
    }

internal fun subtitleSourceLabel(track: SubtitleTrack): String =
    if (track.provenance == SubtitleProvenance.SIDECAR) {
        track.sourceName?.takeIf { it.isNotBlank() } ?: SubtitleSourceExternal
    } else {
        SubtitleSourceEmbedded
    }

private fun subtitleSourceOrder(sourceKey: String): Int = when (sourceKey) {
    SubtitleSourceEmbeddedKey -> 0
    SubtitleSourceExternalKey -> 1
    else -> 2
}

internal fun subtitleResultDetailLine(track: SubtitleTrack): String? = listOfNotNull(
    buildList {
        addAll(track.roles.map(::trackRoleLabel))
        if (track.isSdh == true) add("SDH")
    }.distinct().sorted().takeIf { it.isNotEmpty() }?.joinToString(", "),
    "Forced".takeIf { track.isForced == true && track.roles.none { role -> role == TrackRole.FORCED } },
    subtitleCodecLabel(track.format),
).takeIf { it.isNotEmpty() }?.joinToString(GenreSeparator)

internal enum class SubtitleBrowserColumn { SOURCE, RESULTS }

internal fun subtitleBrowserColumnOf(key: String?): SubtitleBrowserColumn? = when {
    key == null -> null
    key == SubtitleSourceEmbeddedKey || key == SubtitleSourceExternalKey ||
        key.startsWith(SubtitleSourceAddonPrefix) -> SubtitleBrowserColumn.SOURCE
    key.startsWith("subtitle:") -> SubtitleBrowserColumn.RESULTS
    else -> null
}

internal enum class SubtitleBrowserDirection { LEFT, RIGHT }

internal fun subtitleBrowserCrossColumnTarget(
    direction: SubtitleBrowserDirection,
    focusedKey: String?,
    groups: List<PlayerSubtitleSourceGroup>,
): String? = when (direction) {
    SubtitleBrowserDirection.LEFT ->
        groups.firstOrNull { group -> group.entries.any { it.key == focusedKey } }?.key
    SubtitleBrowserDirection.RIGHT ->
        groups.firstOrNull { it.key == focusedKey }?.entries?.firstOrNull()?.key
}

internal fun browserFocusKey(browser: PlayerBrowser, state: PlayerUiState): String? =
    when (browser) {
        PlayerBrowser.AUDIO -> state.trackSelection.audioTrackId
            ?.let { id -> "audio:${id.value}" }
            ?.takeIf { key -> state.trackInventory?.audioTracks?.any { "audio:${it.id.value}" == key } == true }
            ?: state.trackInventory?.audioTracks?.firstOrNull()?.let { "audio:${it.id.value}" }
            ?: "audio-none"
        PlayerBrowser.SUBTITLES -> when (state.trackSelection.subtitleMode) {
            SubtitleMode.OFF -> "subtitle-off"
            SubtitleMode.AUTO -> "subtitle-auto"
            SubtitleMode.SELECTED -> state.trackSelection.subtitleTrackId
                ?.let { id -> "subtitle:${id.value}" }
                ?.takeIf { key -> state.trackInventory?.subtitleTracks?.any { "subtitle:${it.id.value}" == key } == true }
                ?: "subtitle-off"
        }
    }

internal fun browserEntriesForTest(
    browser: PlayerBrowser,
    state: PlayerUiState,
    stateHolder: PlayerStateHolder,
): List<PlayerBrowserEntry> = browserEntries(browser, state, stateHolder)

internal fun audioTrackDetailLine(track: AudioTrack): String? = listOfNotNull(
    track.language?.takeIf { it.isNotBlank() },
    track.codec?.takeIf { it.isNotBlank() },
    track.channelCount?.takeIf { it > 0 }?.let(::audioChannelLabel),
    track.roles.takeIf { it.isNotEmpty() }?.map(::trackRoleLabel)?.sorted()?.joinToString(", "),
).takeIf { it.isNotEmpty() }?.joinToString(GenreSeparator)

internal fun audioChannelLabel(channels: Int): String = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels ch"
}

internal fun trackRoleLabel(role: TrackRole): String = when (role) {
    TrackRole.COMMENTARY -> "Commentary"
    TrackRole.DESCRIPTIVE -> "Descriptive"
    TrackRole.CAPTION -> "SDH"
    TrackRole.FORCED -> "Forced"
    TrackRole.DUB -> "Dub"
    TrackRole.ALTERNATE -> "Alternate"
}

internal fun subtitleCodecLabel(format: String?): String? = when (format?.lowercase()) {
    "application/x-subrip", "application/x-subrip+text" -> "SRT"
    "text/vtt", "application/x-vtt" -> "WebVTT"
    "application/ttml+xml" -> "TTML"
    "application/mp4" -> "tx3g"
    "text/x-ssa", "text/x-ass" -> "SSA"
    else -> null
}

internal const val PlayerSkipIntroTag: String = "player:skip-intro"

internal const val PlayerNextEpisodeTag: String = "player:next-episode"

internal const val PlayerMediaInfoTag: String = "player:media-info"

internal const val PlayerInfoRowTag: String = "player:info-row"

internal const val PlayerTracksInfoTag: String = "player:tracks-info"

internal const val PlayerAudioInfoTag: String = "player:audio-info"

internal const val PlayerSubtitleInfoTag: String = "player:subtitle-info"

internal const val PlayerInfoCentreTag: String = "player:info-centre"

@Composable
internal fun TvPlayerSkipIntroControl(
    ui: dev.infusion.core.playback.api.SkipIntroUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (ui.isCountingDown && ui.countdownSeconds != null) {
        "${ui.label} (${ui.countdownSeconds})"
    } else {
        ui.label
    }
    InfusionTvButton(
        onClick = onClick,
        modifier = modifier
            .height(TvPlayerOverlayAppearance.SkipIntroControlHeight)
            .testTag(PlayerSkipIntroTag),
        contentPadding = TvPlayerOverlayAppearance.SkipIntroControlContentPadding,
    ) {
        Text(text = label, style = InfusionTvScaleCalibration.Type.Body)
    }
}

@Composable
internal fun TvPlayerNextEpisodeControl(
    ui: NextEpisodeUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (ui.isCountingDown && ui.countdownSeconds != null) {
        "${NextEpisodeUi.LABEL} (${ui.countdownSeconds})"
    } else {
        NextEpisodeUi.LABEL
    }
    InfusionTvButton(
        onClick = onClick,
        modifier = modifier
            .height(TvPlayerOverlayAppearance.SkipIntroControlHeight)
            .testTag(PlayerNextEpisodeTag),
        contentPadding = TvPlayerOverlayAppearance.SkipIntroControlContentPadding,
    ) {
        Text(text = label, style = InfusionTvScaleCalibration.Type.Body)
    }
}

@Composable
internal fun TvPlayerInfoLineRow(
    video: dev.infusion.core.playback.api.VideoTrack?,
    audio: dev.infusion.core.playback.api.AudioTrack?,
    modifier: Modifier = Modifier,
    bitrate: dev.infusion.core.playback.api.PlayingMediaBitrate? = null,
    info: @Composable () -> Unit,
) {
    val facts = dev.infusion.core.playback.api.PlayingMediaInfo.line(video, audio, bitrate)
    val blockGap = with(LocalDensity.current) {
        TvPlayerOverlayAppearance.InfoLineBlockGap.roundToPx()
    }
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PlayerInfoRowTag),
        content = {
            Box(Modifier.testTag(PlayerInfoCentreTag)) { info() }
            if (facts != null) TvPlayerMediaInfoText(facts)
        },
    ) { measurables, constraints ->
        val rowWidth = constraints.maxWidth
        val factsMeasurable = measurables.getOrNull(1)

        val factsPlaceable = factsMeasurable?.measure(
            Constraints(maxWidth = TvPlayerFooterLayout.sideBlockMaxWidth(rowWidth)),
        )
        val factsWidth = factsPlaceable?.width ?: 0

        val centre = measurables[0].measure(
            Constraints(
                maxWidth = TvPlayerFooterLayout.centreBlockMaxWidth(rowWidth, factsWidth, blockGap),
            ),
        )
        val height = maxOf(centre.height, factsPlaceable?.height ?: 0)
        layout(rowWidth, height) {
            centre.placeRelative(
                TvPlayerFooterLayout.centreBlockLeft(rowWidth, centre.width),
                (height - centre.height) / 2,
            )
            factsPlaceable?.placeRelative(rowWidth - factsWidth, (height - factsPlaceable.height) / 2)
        }
    }
}

@Composable
internal fun TvPlayerPlayingMediaInfo(
    video: dev.infusion.core.playback.api.VideoTrack?,
    audio: dev.infusion.core.playback.api.AudioTrack?,
    modifier: Modifier = Modifier,
    bitrate: dev.infusion.core.playback.api.PlayingMediaBitrate? = null,
) {
    val text = dev.infusion.core.playback.api.PlayingMediaInfo.line(video, audio, bitrate) ?: return
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd,
    ) {
        TvPlayerMediaInfoText(text)
    }
}

@Composable
private fun TvPlayerMediaInfoText(text: String) {
    Text(
        text = text,
        style = InfusionTvScaleCalibration.Type.Body,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(PlayerMediaInfoTag),
    )
}
