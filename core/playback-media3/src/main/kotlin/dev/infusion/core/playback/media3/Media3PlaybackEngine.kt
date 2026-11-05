package dev.infusion.core.playback.media3

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.infusion.core.playback.api.AudioRouteEvidence
import dev.infusion.core.playback.api.BufferingReason
import dev.infusion.core.playback.api.BufferingState
import dev.infusion.core.playback.api.CapabilityKind
import dev.infusion.core.playback.api.CapabilityObservation
import dev.infusion.core.playback.api.CapabilityState
import dev.infusion.core.playback.api.CancellationReason
import dev.infusion.core.playback.api.ChapterMark
import dev.infusion.core.playback.api.DecoderKind
import dev.infusion.core.playback.api.DecoderObservation
import dev.infusion.core.playback.api.DeviceRouteSnapshot
import dev.infusion.core.playback.api.DiagnosticsSnapshot
import dev.infusion.core.playback.api.DolbyVisionProfile7Fallback
import dev.infusion.core.playback.api.EndReason
import dev.infusion.core.playback.api.EngineId
import dev.infusion.core.playback.api.PlaybackEngine
import dev.infusion.core.playback.api.PlaybackEvent
import dev.infusion.core.playback.api.PlaybackEventListener
import dev.infusion.core.playback.api.PlaybackFailure
import dev.infusion.core.playback.api.PlaybackSession
import dev.infusion.core.playback.api.PlaybackState
import dev.infusion.core.playback.api.PlaybackStateMachine
import dev.infusion.core.playback.api.PlaybackTimeline
import dev.infusion.core.playback.api.SessionId
import dev.infusion.core.playback.api.SessionLineage
import dev.infusion.core.playback.api.SessionPhase
import dev.infusion.core.playback.api.StateCause
import dev.infusion.core.playback.api.StopReason
import dev.infusion.core.playback.api.SubtitleMode
import dev.infusion.core.playback.api.TrackInventory
import dev.infusion.core.playback.api.TrackSelection
import dev.infusion.core.playback.api.TransportRequest
import java.util.concurrent.CopyOnWriteArrayList

@UnstableApi
class Media3PlaybackEngine(
    context: Context,

    private val httpDataSourceFactory: (() -> HttpDataSource.Factory)? = null,

    private val dolbyVisionProfile7Fallback: () -> DolbyVisionProfile7Fallback = {
        DolbyVisionProfile7Fallback.DEFAULT
    },

    private val audioAttributes: androidx.media3.common.AudioAttributes? = null,
    private val handleAudioFocus: Boolean = false,
    private val playerFactory: Media3PlayerFactory? = null,
) : PlaybackEngine {

    private val appContext = context.applicationContext

    override val id: EngineId = EngineId("media3", MediaLibraryInfo.VERSION)

    private var activeSession: Media3PlaybackSession? = null
    private var released: Boolean = false

    override fun createSession(lineage: SessionLineage, route: DeviceRouteSnapshot?): PlaybackSession {
        check(!released) { "engine is released" }
        check(activeSession == null) { "engine already has an active session" }
        val session = Media3PlaybackSession(
            appContext,
            lineage,
            route,
            id,
            playerFactory,
            httpDataSourceFactory,
            dolbyVisionProfile7Fallback,
            audioAttributes,
            handleAudioFocus,
        )
        session.onReleasedCallback = { onSessionReleased(it) }
        activeSession = session
        return session
    }

    override fun release() {
        activeSession?.release()
        activeSession = null
        released = true
    }

    internal fun onSessionReleased(session: Media3PlaybackSession) {
        if (activeSession === session) activeSession = null
    }
}

@UnstableApi
internal fun jellyfinExtractorsFactory(): DefaultExtractorsFactory =
    DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setConstantBitrateSeekingAlwaysEnabled(true)

@UnstableApi
fun interface Media3PlayerFactory {
    fun create(context: Context, mediaSourceFactory: androidx.media3.exoplayer.source.MediaSource.Factory): ExoPlayer

    companion object {

        fun withLoadControl(loadControl: (Context) -> LoadControl): Media3PlayerFactory =
            Media3PlayerFactory { context, mediaSourceFactory ->
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(mediaSourceFactory)

                    .setLoadControl(loadControl(context))
                    .build()
            }

        val DEFAULT: Media3PlayerFactory = withLoadControl { context ->
            sampleBufferBoundedLoadControl(context)
        }
    }
}

@UnstableApi
internal fun sampleBufferBoundedLoadControlBuilder(context: Context): DefaultLoadControl.Builder =
    sampleBufferBoundedLoadControlBuilder(PlaybackSampleBufferBudget.maxMemoryBytes(context))

@UnstableApi
internal fun sampleBufferBoundedLoadControlBuilder(maxMemoryBytes: Long): DefaultLoadControl.Builder =
    DefaultLoadControl.Builder()
        .setTargetBufferBytes(PlaybackSampleBufferBudget.targetBufferBytes(maxMemoryBytes))
        .setPrioritizeTimeOverSizeThresholds(false)

@UnstableApi
internal fun sampleBufferBoundedLoadControl(context: Context): DefaultLoadControl =
    sampleBufferBoundedLoadControlBuilder(context).build()

@UnstableApi
class Media3PlaybackSession internal constructor(
    private val context: Context,
    override val lineage: SessionLineage,
    private val route: DeviceRouteSnapshot?,
    private val engineId: EngineId,
    private val playerFactory: Media3PlayerFactory? = null,

    private val httpDataSourceFactory: (() -> HttpDataSource.Factory)? = null,

    private val dolbyVisionProfile7Fallback: () -> DolbyVisionProfile7Fallback = {
        DolbyVisionProfile7Fallback.DEFAULT
    },

    private val audioAttributes: androidx.media3.common.AudioAttributes? = null,
    private val handleAudioFocus: Boolean = false,
) : PlaybackSession {

    override val sessionId: SessionId = lineage.sessionId

    private val listeners = CopyOnWriteArrayList<PlaybackEventListener>()
    private var player: ExoPlayer? = null
    private var state: PlaybackState = PlaybackState.IDLE
    private var prepared: Boolean = false

    private var stopping: Boolean = false
    private var pendingCause: StateCause = StateCause.UNKNOWN
    private var initialLoadDone: Boolean = false
    private var currentSelection: TrackSelection = TrackSelection.DEFAULT
    private var trackIndex: Media3TrackMapper.TrackIndex? = null
    private var trackInventory: TrackInventory? = null

    private var embeddedChapters: List<ChapterMark> = emptyList()

    private var sidecarEvidence: Map<String, dev.infusion.core.playback.api.SidecarSubtitleEvidence> = emptyMap()
    private var lastFailure: PlaybackFailure? = null
    private var lastCancellation: CancellationReason? = null
    private var timeline: PlaybackTimeline? = null
    private var buffering: BufferingState? = null

    @Volatile private var lastKnownPositionMs: Long? = null
    private var videoSize: dev.infusion.core.playback.api.VideoSize? = null
    private var firstFrameRenderedAtMs: Long? = null

    private var resumeReapplyTargetMs: Long? = null
    private var resumeReapplyFired: Boolean = false

    private var resumeReapplyAttainedSamples: Int = 0

    private var contentOffsetMs: Long = 0L

    private var userCommandedSincePrepare: Boolean = false
    private var droppedFrames: Int = 0
    private val decoderObservations = mutableListOf<DecoderObservation>()
    private val capabilityObservations = mutableListOf<CapabilityObservation>()
    private var positionHandler: Handler? = null

    private val measuredEvidenceProbe = MeasuredVideoEvidenceProbe(
        onEvidence = { evidence ->
            emit(PlaybackEvent.MeasuredVideoEvidenceObserved(nowMs(), evidence))
        },
        log = { message -> logDisplayMatching(message) },
    )
    private val frameMetadataListener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        measuredEvidenceProbe.onFramePresentationTimeUs(presentationTimeUs)
    }

    private val sampleAllocator: PlaybackSampleAllocator by lazy { PlaybackSampleAllocator() }

    private val audioOutputProgressTracker = AudioOutputProgressTracker()

    private val loadControl: DefaultLoadControl by lazy {
        sampleBufferBoundedLoadControlBuilder(context)
            .setAllocator(sampleAllocator.allocator)
            .build()
    }

    private val effectivePlayerFactory: Media3PlayerFactory =
        playerFactory ?: Media3PlayerFactory.withLoadControl { loadControl }

    private val meter: PlaybackMeter = playbackMeterFor(
        context = context,
        sessionTag = { LoggingPlaybackMeter.sessionTag(sessionId.value) },
        sampleBufferTargetBytes = { PlaybackSampleBufferBudget.targetBufferBytesFor(context) },
        allocatorProbe = sampleAllocator,
        heapClassSupported = { PlaybackSampleBufferBudget.supportsMeasuredWorkingSet(context) },
    )
    private val positionTicker = object : Runnable {
        override fun run() {
            val currentPlayer = player ?: return
            if (state in TERMINAL_STATES || stopping) return
            contentPositionMs(currentPlayer)?.let { position ->
                lastKnownPositionMs = position
                val buffered = bufferedPositionMs(currentPlayer)

                meter.onPositionSample(nowMs(), position, buffered, currentPlayer.isPlaying)
                emit(PlaybackEvent.PositionChanged(nowMs(), position, buffered))

                maybeReapplyResume(position, currentPlayer)
            }
            positionHandler?.postDelayed(this, POSITION_SAMPLE_INTERVAL_MS)
        }
    }

    private fun contentPositionMs(player: ExoPlayer): Long? =
        player.currentPosition.takeIf { it != C.TIME_UNSET }?.plus(contentOffsetMs)

    private fun streamPositionMs(contentPositionMs: Long): Long =
        (contentPositionMs - contentOffsetMs).coerceAtLeast(0L)

    private fun maybeReapplyResumeNow() {
        if (resumeReapplyTargetMs == null) return
        val currentPlayer = player ?: return
        val position = contentPositionMs(currentPlayer) ?: return
        maybeReapplyResume(position, currentPlayer)
    }

    private fun maybeReapplyResume(positionMs: Long, currentPlayer: ExoPlayer) {
        val target = resumeReapplyTargetMs ?: return
        val playbackReady = currentPlayer.playbackState == Player.STATE_READY
        val playing = currentPlayer.isPlaying
        if (playbackReady && playing && positionMs >= target - ResumeReapply.ATTAINED_TOLERANCE_MS) {
            resumeReapplyAttainedSamples += 1
        } else {
            resumeReapplyAttainedSamples = 0
        }
        when (ResumeReapply.decide(
            ResumeReapply.Input(
                requestedMs = target,
                attainedMs = positionMs,
                playbackReady = playbackReady,
                playing = playing,
                attainedSamples = resumeReapplyAttainedSamples,
                userCommanded = userCommandedSincePrepare,
                alreadyFired = resumeReapplyFired,
            ),
        )) {
            ResumeReapply.Decision.ATTAINED -> {
                resumeReapplyTargetMs = null
                Media3ForensicsLog.event(
                    "resume-reapply-attained",
                    "requestedMs=$target attainedMs=$positionMs samples=$resumeReapplyAttainedSamples",
                )
            }
            ResumeReapply.Decision.REAPPLY -> {
                resumeReapplyFired = true
                val streamTarget = streamPositionMs(target)
                Media3ForensicsLog.event(
                    "resume-reapply",
                    "requestedMs=$target attainedMs=$positionMs streamTargetMs=$streamTarget " +
                        "offsetMs=$contentOffsetMs seekable=${currentPlayer.isCurrentMediaItemSeekable}",
                )
                currentPlayer.seekTo(streamTarget)

                lastKnownPositionMs = target
                emit(PlaybackEvent.PositionChanged(nowMs(), target, bufferedPositionMs(currentPlayer)))
            }
            ResumeReapply.Decision.NONE -> Unit
        }
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    private fun emit(event: PlaybackEvent) {
        listeners.forEach { it.onPlaybackEvent(event) }
    }

    private fun transition(to: PlaybackState, cause: StateCause) {
        check(PlaybackStateMachine.canTransition(state, to)) {
            "illegal transition ${state} -> $to"
        }
        state = to
        emit(PlaybackEvent.StateChanged(nowMs(), to, cause))
    }

    private fun checkCommandable() {
        check(state !in TERMINAL_STATES) { "session is terminal ($state)" }
    }

    private fun checkPrepared() {
        checkCommandable()
        check(prepared) { "session is not prepared" }
    }

    override fun prepare(request: TransportRequest) {
        checkCommandable()
        check(state == PlaybackState.IDLE || state == PlaybackState.PREPARING) {
            "prepare only valid from IDLE/PREPARING (state=$state)"
        }
        require(request.requestId == lineage.requestId) {
            "transport request must match session lineage"
        }
        if (state == PlaybackState.PREPARING) return
        Media3ForensicsLog.event("prepare-request", "startPositionMs=${request.startPositionMs} mime=${request.mimeType ?: "none"} sidecars=${request.sidecarUris.size}")
        meter.onPrepareStarted()

        val httpFactory = (httpDataSourceFactory?.invoke() ?: DefaultHttpDataSource.Factory())
            .setDefaultRequestProperties(request.headers)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val baseExtractorsFactory = jellyfinExtractorsFactory()

        val dolbyVisionFallback = dolbyVisionProfile7Fallback()
        Media3ForensicsLog.event("dv-profile7-setting", "fallback=$dolbyVisionFallback")
        val extractorsFactory = if (dolbyVisionFallback == DolbyVisionProfile7Fallback.ON) {
            DolbyVisionProfile7ExtractorsFactory(
                delegate = baseExtractorsFactory,
                rewriter = DolbyVisionProfile7FormatRewriter(
                    context = context,
                    setting = dolbyVisionFallback,
                    log = ::logDisplayMatching,
                ),
            )
        } else {
            baseExtractorsFactory
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        val player = effectivePlayerFactory.create(context, mediaSourceFactory)
        this.player = player

        audioAttributes?.let { attributes ->
            player.setAudioAttributes(attributes, handleAudioFocus)
            player.setHandleAudioBecomingNoisy(handleAudioFocus)
        }

        player.trackSelectionParameters = Media3TrackMapper.withInfusionTextBase(player.trackSelectionParameters)
        Media3PlaybackBinder.attach(sessionId, player)
        meter.onPlayerCreated()

        if (android.util.Log.isLoggable(DISPLAY_MATCHING_TAG, android.util.Log.VERBOSE)) {
            android.util.Log.v(
                DISPLAY_MATCHING_TAG,
                "media3.player videoChangeFrameRateStrategy=${player.videoChangeFrameRateStrategy}" +
                    " (${C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF}=OFF," +
                    " ${C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS}=ONLY_IF_SEAMLESS)",
            )
        }

        pendingCause = StateCause.PREPARE_REQUESTED
        transition(PlaybackState.PREPARING, StateCause.PREPARE_REQUESTED)
        emit(PlaybackEvent.SessionPhaseChanged(nowMs(), SessionPhase.PREPARING))
        prepared = true

        player.addAnalyticsListener(analyticsListener)
        player.addListener(playerListener)

        player.setVideoFrameMetadataListener(frameMetadataListener)

        val mediaItemBuilder = MediaItem.Builder()
            .setMediaId(request.requestId)
            .setUri(request.uri)
            .setMimeType(request.mimeType)
        val subtitleConfigurations = request.sidecarUris.mapIndexedNotNull { index, sidecar ->

            val declared = request.sidecarMimeTypes.getOrNull(index)
            val mime = dev.infusion.core.playback.api.SubtitleMimeAllowlist.normalize(declared)
                ?: subtitleMimeType(sidecar)
            mime?.let {

                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sidecar))
                    .setMimeType(it)
                    .setId(Media3TrackMapper.sidecarId(index))
                    .build()
            }
        }

        sidecarEvidence = request.sidecarUris.indices
            .filter { index -> subtitleConfigurations.any { configuration -> configuration.id == Media3TrackMapper.sidecarId(index) } }
            .associate { index ->
                Media3TrackMapper.sidecarId(index) to dev.infusion.core.playback.api.SidecarSubtitleEvidence(
                    language = request.sidecarLanguages.getOrNull(index),
                    forced = request.sidecarForced.getOrNull(index),
                    label = request.sidecarLabels.getOrNull(index),

                    source = request.sidecarSources.getOrNull(index),
                )
            }
        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }

        contentOffsetMs = request.contentOffsetMs
        val streamStartPositionMs = streamPositionMs(request.startPositionMs)
        player.setMediaItem(mediaItemBuilder.build(), streamStartPositionMs)
        Media3ForensicsLog.event(
            "media-item-installed",
            "startPositionMs=${request.startPositionMs} streamStartPositionMs=$streamStartPositionMs offsetMs=$contentOffsetMs",
        )

        resumeReapplyTargetMs = request.startPositionMs.takeIf { it > 0L }
        resumeReapplyFired = false
        resumeReapplyAttainedSamples = 0
        userCommandedSincePrepare = false
        resumeReapplyTargetMs?.let { target ->
            Media3ForensicsLog.event("resume-reapply-armed", "requestedMs=$target")
        }
        player.prepare()
        positionHandler = Handler(Looper.getMainLooper()).also { it.post(positionTicker) }
    }

    override fun play() {
        checkPrepared()
        val p = player ?: throw IllegalStateException("player not created")
        if (state == PlaybackState.ENDED) {

            val restartContentMs = contentOffsetMs
            p.seekTo(streamPositionMs(restartContentMs))
            emit(PlaybackEvent.PositionChanged(nowMs(), restartContentMs, bufferedPositionMs(p)))
        }
        pendingCause = StateCause.PLAY_REQUESTED
        p.play()
    }

    override fun pause() {
        checkPrepared()
        val p = player ?: throw IllegalStateException("player not created")

        userCommandedSincePrepare = true
        pendingCause = StateCause.PAUSE_REQUESTED
        p.pause()
        if (state == PlaybackState.READY) {

            transition(PlaybackState.PAUSED, StateCause.PAUSE_REQUESTED)
        }
    }

    override fun seekTo(positionMs: Long) {
        require(positionMs >= 0) { "seek position must be >= 0 (was $positionMs)" }

        if (state == PlaybackState.ENDED) return
        checkPrepared()
        val p = player ?: throw IllegalStateException("player not created")

        userCommandedSincePrepare = true
        resumeReapplyTargetMs = null
        val streamPosition = streamPositionMs(positionMs)
        Media3ForensicsLog.event("seek-command", "beforePositionMs=${contentPositionMs(p)} targetPositionMs=$positionMs streamTargetMs=$streamPosition offsetMs=$contentOffsetMs seekable=${p.isCurrentMediaItemSeekable} isPlaying=${p.isPlaying} playWhenReady=${p.playWhenReady}")
        pendingCause = StateCause.SEEK_REQUESTED
        p.seekTo(streamPosition)
    }

    override fun stop(reason: StopReason) {
        checkCommandable()
        val cancellationReason = when (reason) {
            StopReason.USER_EXIT, StopReason.COMPLETED -> CancellationReason.USER
            StopReason.ERROR_RECOVERY, StopReason.SYSTEM -> CancellationReason.SYSTEM
            StopReason.SUPERSEDED -> CancellationReason.SUPERSEDED
        }
        val cause = when (reason) {
            StopReason.USER_EXIT, StopReason.COMPLETED -> StateCause.CANCELLED_BY_USER
            else -> StateCause.CANCELLED_BY_SYSTEM
        }
        lastCancellation = cancellationReason
        player?.run {

            contentPositionMs(this)?.let { position ->
                lastKnownPositionMs = position
                emit(PlaybackEvent.PositionChanged(nowMs(), position, bufferedPositionMs(this)))
            }

            stopping = true
            stop()
            clearMediaItems()
        }

        Media3ForensicsLog.event(
            "terminal-stop-sample",
            "positionMs=${lastKnownPositionMs} state=$state stopping=true",
        )
        emit(PlaybackEvent.Cancelled(nowMs(), cancellationReason))
        transition(PlaybackState.CANCELLED, cause)
    }

    override fun selectTrack(selection: TrackSelection) {
        checkPrepared()
        val p = player ?: throw IllegalStateException("player not created")
        val index = trackIndex ?: throw IllegalStateException("track inventory not available")

        selection.videoTrackId?.let { id ->
            require(index.video.containsKey(id)) { "unknown video track $id" }
        }
        selection.audioTrackId?.let { id ->
            require(index.audio.containsKey(id)) { "unknown audio track $id" }
        }
        if (selection.subtitleMode == SubtitleMode.SELECTED) {
            val id = selection.subtitleTrackId
            require(id != null) { "subtitleMode=SELECTED requires a subtitleTrackId" }
            require(index.subtitle.containsKey(id)) { "unknown subtitle track $id" }
        }
        val params = Media3TrackMapper.buildSelectionParameters(
            context = context,
            base = p.trackSelectionParameters,
            selection = selection,
            index = index,
        )
        currentSelection = selection
        p.trackSelectionParameters = params
        emit(PlaybackEvent.TrackSelectionChanged(nowMs(), selection))
    }

    override fun snapshot(): DiagnosticsSnapshot = DiagnosticsSnapshot(
        sessionId = sessionId,
        lineage = lineage,
        engineId = engineId,
        state = state,
        positionMs = lastKnownPositionMs,
        timeline = timeline,
        buffering = buffering,
        videoSize = videoSize,
        firstFrameRenderedAtMs = firstFrameRenderedAtMs,
        droppedFrames = droppedFrames.takeIf { it > 0 },
        decoderObservations = decoderObservations.toList(),
        capabilityObservations = capabilityObservations.toList(),
        trackInventory = trackInventory,
        lastFailure = lastFailure,
        lastCancellationReason = lastCancellation,
        audioOutputProgress = audioOutputProgressTracker.snapshot(),
        createdAtMs = nowMs(),
    )

    override fun addListener(listener: PlaybackEventListener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackEventListener) {
        listeners -= listener
    }

    override fun release() {
        if (state == PlaybackState.RELEASED) return
        positionHandler?.removeCallbacks(positionTicker)
        positionHandler = null
        emit(PlaybackEvent.SessionPhaseChanged(nowMs(), SessionPhase.RELEASED))
        transition(PlaybackState.RELEASED, StateCause.SESSION_RELEASED)
        val releasedPlayer = player
        runCatching { releasedPlayer?.clearVideoFrameMetadataListener(frameMetadataListener) }
        measuredEvidenceProbe.reset()
        releasedPlayer?.release()
        meter.onPlayerReleased()
        player = null
        releasedPlayer?.let { Media3PlaybackBinder.clear(sessionId, it) }
        onReleasedCallback?.invoke(this)
        onReleasedCallback = null
    }

    internal var onReleasedCallback: ((Media3PlaybackSession) -> Unit)? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {

            if (state in TERMINAL_STATES || state == PlaybackState.ENDED) return
            val p = player
            Media3ForensicsLog.event("player-state", "state=$playbackState currentPositionMs=${p?.currentPosition} durationMs=${p?.duration} seekable=${p?.isCurrentMediaItemSeekable} isPlaying=${p?.isPlaying} playWhenReady=${p?.playWhenReady}")
            when (playbackState) {
                Player.STATE_IDLE -> Unit
                Player.STATE_BUFFERING -> onBuffering()
                Player.STATE_READY -> onReady()
                Player.STATE_ENDED -> onEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (state in TERMINAL_STATES) return
            Media3ForensicsLog.event("is-playing-changed", "isPlaying=$isPlaying positionMs=${player?.currentPosition} playWhenReady=${player?.playWhenReady}")

            if (isPlaying) maybeReapplyResumeNow()
            if (isPlaying && state != PlaybackState.PLAYING) {
                transition(PlaybackState.PLAYING, pendingCause.takeIf { it == StateCause.PLAY_REQUESTED } ?: StateCause.AUTO)
            } else if (!isPlaying && state == PlaybackState.PLAYING) {
                transition(PlaybackState.PAUSED, pendingCause.takeIf { it == StateCause.PAUSE_REQUESTED } ?: StateCause.AUTO)
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val player = player ?: return
            val window = androidx.media3.common.Timeline.Window()
            val hasWindow = player.currentTimeline.windowCount > 0
            if (hasWindow) {

                player.currentTimeline.getWindow(player.currentMediaItemIndex, window)
            }
            val duration = player.duration
            val mapped = PlaybackTimeline(
                durationMs = if (hasWindow && duration != C.TIME_UNSET) duration else null,
                isSeekable = hasWindow && window.isSeekable,
                isLive = hasWindow && window.isDynamic,
                windowCount = player.currentTimeline.windowCount,
            )
            Media3ForensicsLog.event("timeline", "reason=$reason windowCount=${player.currentTimeline.windowCount} durationMs=${mapped.durationMs} seekable=${mapped.isSeekable} dynamic=${mapped.isLive} currentPositionMs=${player.currentPosition}")
            this@Media3PlaybackSession.timeline = mapped
            emit(PlaybackEvent.TimelineChanged(nowMs(), mapped))
        }

        override fun onTracksChanged(tracks: Tracks) {
            observeAudioRouteEvidence(tracks)
            val (inventory, index) = Media3TrackMapper.mapTracks(tracks, sidecarEvidence)

            trackInventory = inventory
            trackIndex = index

            if (stopping) {
                Media3ForensicsLog.event(
                    "tracks-teardown-echo-suppressed",
                    "groups=${tracks.groups.size} stopping=true",
                )
                return
            }
            emit(PlaybackEvent.TracksChanged(nowMs(), inventory))
            emit(PlaybackEvent.TrackSelectionChanged(nowMs(), currentSelection))

            val chapters = mapEmbeddedChapters(tracks)
            if (chapters != embeddedChapters) {
                embeddedChapters = chapters
                Media3ForensicsLog.event(
                    "embedded-chapters",
                    "count=${chapters.size} starts=${chapters.joinToString(",") { it.startMs.toString() }}",
                )
                emit(PlaybackEvent.ChaptersObserved(nowMs(), chapters))
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {

            measuredEvidenceProbe.onDiscontinuity()

            if (state in TERMINAL_STATES || stopping) {
                if (stopping) {
                    Media3ForensicsLog.event(
                        "position-discontinuity-suppressed",
                        "reason=$reason stopping=true afterPositionMs=${newPosition.positionMs}",
                    )
                }
                return
            }
            val player = player ?: return
            val position = contentPositionMs(player) ?: return
            Media3ForensicsLog.event("position-discontinuity", "reason=$reason beforePositionMs=${oldPosition.positionMs} afterPositionMs=${newPosition.positionMs} currentPositionMs=$position seekable=${player.isCurrentMediaItemSeekable}")
            lastKnownPositionMs = position
            emit(PlaybackEvent.PositionChanged(nowMs(), position, bufferedPositionMs(player)))
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            val mapped = dev.infusion.core.playback.api.VideoSize(
                width = videoSize.width,
                height = videoSize.height,
                pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
                unappliedRotationDegrees = videoSize.unappliedRotationDegrees,
            )
            this@Media3PlaybackSession.videoSize = mapped
            emit(PlaybackEvent.VideoSizeChanged(nowMs(), mapped))

            measuredEvidenceProbe.onRenderedVideoSize(
                widthPx = videoSize.width,
                heightPx = videoSize.height,
                rotationDegrees = videoSize.unappliedRotationDegrees,
                pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            if (state in TERMINAL_STATES) return

            val deepest = Media3FailureMapper.deepestCause(error)
            Media3ForensicsLog.event(
                "player-error",
                "errorCode=${error.errorCodeName} " +
                    "cause=${error.cause?.javaClass?.simpleName ?: "none"} deepest=${deepest.javaClass.simpleName}",
            )
            fail(Media3FailureMapper.map(error, nowMs()))
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = Unit
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            meter.onFirstFrame(nowMs())
            if (firstFrameRenderedAtMs == null) {
                firstFrameRenderedAtMs = nowMs()
                emit(PlaybackEvent.RenderedFirstFrame(firstFrameRenderedAtMs!!))
            }
        }

        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) {
            meter.onLoadStarted(loadEventInfo)
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) {
            meter.onLoadCompleted(loadEventInfo)
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            meter.onLoadError(loadEventInfo)
        }

        override fun onLoadCanceled(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) {
            meter.onLoadCanceled(loadEventInfo)
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            observeDecoder(DecoderObservation(DecoderKind.VIDEO, decoderName, isHardwareAccelerated = isHardwareDecoder(decoderName)))
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {

            audioOutputProgressTracker.onRouteStarted()
            observeDecoder(DecoderObservation(DecoderKind.AUDIO, decoderName, isHardwareAccelerated = isHardwareDecoder(decoderName)))
        }

        override fun onAudioEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
            audioOutputProgressTracker.onRouteStarted()
        }

        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            audioOutputProgressTracker.onOutputAdvanced()
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            audioOutputProgressTracker.onUnderrun()
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {

            measuredEvidenceProbe.onVideoFormatChanged(format.frameRate)
            observeFormat(CapabilityKind.VIDEO, format)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {

            audioOutputProgressTracker.onRouteStarted()
            observeFormat(CapabilityKind.AUDIO, format)
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
            droppedFrames += count
            meter.onDroppedFrames(droppedFrames.toLong())

            emit(PlaybackEvent.DroppedFrames(nowMs(), droppedFrames))
        }
    }

    private fun observeDecoder(decoder: DecoderObservation) {
        decoderObservations += decoder
        emit(PlaybackEvent.DecoderObserved(nowMs(), decoder))
        if (decoder.isHardwareAccelerated == false) {

            observeCapability(
                CapabilityObservation(
                    kind = if (decoder.kind == DecoderKind.VIDEO) CapabilityKind.VIDEO else CapabilityKind.AUDIO,
                    state = CapabilityState.DEGRADED,
                    evidence = "software decoder '${decoder.name}' active",
                    remedy = "select a hardware-supported track or lower the resolution",
                    timestampMs = nowMs(),
                ),
            )
        }
    }

    private fun observeFormat(kind: CapabilityKind, format: Format) {
        val hdr = kind == CapabilityKind.VIDEO && ColorInfo.isTransferHdr(format.colorInfo)
        val evidence = when (kind) {
            CapabilityKind.VIDEO -> "video/${format.sampleMimeType} ${format.width}x${format.height}" +
                (if (hdr) " hdr:observed" else "") + " observed; capability not assessed"
            CapabilityKind.AUDIO -> "audio/${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate}Hz observed; capability not assessed"
            else -> return
        }
        observeCapability(
            CapabilityObservation(
                kind = kind,
                state = CapabilityState.UNKNOWN,
                evidence = evidence,
                timestampMs = nowMs(),
            ),
        )
    }

    private fun observeCapability(observation: CapabilityObservation) {
        capabilityObservations += observation
        emit(PlaybackEvent.CapabilityObserved(nowMs(), observation))
    }

    private fun onBuffering() {
        val reason = if (!initialLoadDone) {
            initialLoadDone = true
            BufferingReason.INITIAL_LOAD
        } else {
            BufferingReason.UNDERFLOW
        }
        val buffered = bufferedPositionMs(player)
        meter.onBufferingChanged(isBuffering = true, bufferedPositionMs = buffered)
        buffering = BufferingState(true, buffered, reason)
        emit(PlaybackEvent.BufferingChanged(nowMs(), buffering!!))
        if (state != PlaybackState.BUFFERING) {
            transition(PlaybackState.BUFFERING, pendingCause.takeIf { it == StateCause.SEEK_REQUESTED } ?: StateCause.BUFFERING_STARTED)
        }
    }

    private fun onReady() {
        val cause = when (pendingCause) {
            StateCause.SEEK_REQUESTED -> StateCause.SEEK_REQUESTED
            StateCause.PREPARE_REQUESTED -> StateCause.PREPARE_COMPLETED
            else -> StateCause.BUFFERING_ENDED
        }
        buffering = BufferingState(false, bufferedPositionMs(player), BufferingReason.UNKNOWN)
        meter.onBufferingChanged(isBuffering = false, bufferedPositionMs = buffering?.bufferedPositionMs)
        emit(PlaybackEvent.BufferingChanged(nowMs(), buffering!!))
        if (state != PlaybackState.READY) {
            transition(PlaybackState.READY, cause)
        }

        maybeReapplyResumeNow()
        if (cause == StateCause.PREPARE_COMPLETED) {
            emit(PlaybackEvent.SessionPhaseChanged(nowMs(), SessionPhase.PREPARED))
        }
        pendingCause = StateCause.UNKNOWN
    }

    private fun onEnded() {

        if (state == PlaybackState.ENDED) return
        emit(PlaybackEvent.Ended(nowMs(), EndReason.END_OF_MEDIA))
        transition(PlaybackState.ENDED, StateCause.MEDIA_ENDED)
    }

    private fun fail(failure: PlaybackFailure) {
        lastFailure = failure

        val audioProgress = audioOutputProgressTracker.snapshot()
        Media3ForensicsLog.event(
            "audio-output-progress",
            "routeStarted=${audioProgress.routeStarted} outputAdvanced=${audioProgress.outputAdvanced} " +
                "underruns=${audioProgress.underruns} stall=${failure.stall?.name ?: "none"}",
        )
        meter.summarise("failed")
        emit(PlaybackEvent.Failed(nowMs(), failure))
        transition(PlaybackState.FAILED, StateCause.FAILURE_OCCURRED)
    }

    private fun bufferedPositionMs(player: ExoPlayer?): Long? =
        player?.bufferedPosition?.takeIf { it != C.TIME_UNSET }?.plus(contentOffsetMs)

    private fun isHardwareDecoder(name: String): Boolean =
        !(name.startsWith("OMX.google.") || name.startsWith("c2.android."))

    private val recordedAudioRouteEvidence = mutableSetOf<String>()

    private fun observeAudioRouteEvidence(tracks: Tracks) {
        val capabilities = platformAudioCapabilities ?: return
        val attributes = androidx.media3.common.AudioAttributes.DEFAULT
        tracks.groups.forEachIndexed { _, group ->
            if (group.getMediaTrackGroup().type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val evidence = AudioRouteEvidence.of(
                    mimeType = format.sampleMimeType,
                    codecs = format.codecs,
                    channelCount = format.channelCount.takeIf { it != Format.NO_VALUE },
                    sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE },
                    language = format.language,
                    engineSupported = group.isTrackSupported(index),
                    platformPassthrough = runCatching {
                        capabilities.isPassthroughPlaybackSupported(format, attributes)
                    }.getOrNull(),
                )
                if (recordedAudioRouteEvidence.add(evidence)) {
                    observeCapability(
                        CapabilityObservation(
                            kind = CapabilityKind.AUDIO,
                            state = CapabilityState.UNKNOWN,
                            evidence = evidence,
                            timestampMs = nowMs(),
                        ),
                    )

                    Media3ForensicsLog.event("audio-route", evidence)
                }
            }
        }
    }

    private val platformAudioCapabilities: androidx.media3.exoplayer.audio.AudioCapabilities? by lazy {
        runCatching {
            androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(
                context,
                androidx.media3.common.AudioAttributes.DEFAULT,
                null,
                emptyList(),
            )
        }.getOrNull()
    }

    private fun logDisplayMatching(message: String) {
        if (android.util.Log.isLoggable(DISPLAY_MATCHING_TAG, android.util.Log.VERBOSE)) {
            android.util.Log.v(DISPLAY_MATCHING_TAG, "media3.evidence $message")
        }
    }

    private fun subtitleMimeType(uri: String): String? {
        val path = android.net.Uri.parse(uri).path ?: return null
        val ext = path.substringAfterLast('.').lowercase()
        return when (ext) {
            "srt", "subrip" -> "application/x-subrip"
            "vtt", "webvtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            "ttml", "dfxp" -> "application/ttml+xml"
            else -> null
        }
    }

    private companion object {
        const val POSITION_SAMPLE_INTERVAL_MS = 1_000L

        const val DISPLAY_MATCHING_TAG = "InfusionDispMatch"
        val TERMINAL_STATES = setOf(PlaybackState.CANCELLED, PlaybackState.FAILED, PlaybackState.RELEASED)
    }
}
