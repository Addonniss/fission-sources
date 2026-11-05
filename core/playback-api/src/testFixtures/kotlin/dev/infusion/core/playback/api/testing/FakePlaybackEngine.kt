package dev.infusion.core.playback.api.testing

import dev.infusion.core.playback.api.AudioTrack
import dev.infusion.core.playback.api.BufferingReason
import dev.infusion.core.playback.api.BufferingState
import dev.infusion.core.playback.api.CapabilityKind
import dev.infusion.core.playback.api.CapabilityObservation
import dev.infusion.core.playback.api.CapabilityState
import dev.infusion.core.playback.api.CancellationReason
import dev.infusion.core.playback.api.DecoderKind
import dev.infusion.core.playback.api.DecoderObservation
import dev.infusion.core.playback.api.DeviceRouteSnapshot
import dev.infusion.core.playback.api.DiagnosticsSnapshot
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
import dev.infusion.core.playback.api.TrackId
import dev.infusion.core.playback.api.TrackInventory
import dev.infusion.core.playback.api.TrackSelection
import dev.infusion.core.playback.api.TransportRequest
import java.util.concurrent.CopyOnWriteArrayList

class FakePlaybackEngine(
    val clock: FakeClock = FakeClock(),
) : PlaybackEngine {

    var defaultScript: FakeScript = FakeScript()

    override val id: EngineId = EngineId("fake", "1.0.0")

    private var active: FakePlaybackSession? = null

    val createdSessions: MutableList<FakePlaybackSession> = mutableListOf()

    var released: Boolean = false
        private set

    override fun createSession(lineage: SessionLineage, route: DeviceRouteSnapshot?): PlaybackSession {
        check(!released) { "engine is released" }
        check(active == null) { "engine already has an active session" }
        val session = FakePlaybackSession(engine = this, lineage = lineage, route = route, clock = clock, script = defaultScript)
        active = session
        createdSessions += session
        return session
    }

    override fun release() {
        active?.release()
        active = null
        released = true
    }

    internal fun onSessionReleased(session: FakePlaybackSession) {
        if (active === session) active = null
    }
}

class FakePlaybackSession internal constructor(
    private val engine: FakePlaybackEngine,
    override val lineage: SessionLineage,
    private val route: DeviceRouteSnapshot?,
    private val clock: FakeClock,
    private val script: FakeScript,
) : PlaybackSession {

    override val sessionId: SessionId = lineage.sessionId

    private val listeners = CopyOnWriteArrayList<PlaybackEventListener>()
    private var state: PlaybackState = PlaybackState.IDLE
    private var positionMs: Long = 0L
    private var prepared: Boolean = false

    val preparedRequests: MutableList<TransportRequest> = mutableListOf()
    private var currentSelection: TrackSelection = TrackSelection.DEFAULT
    private var lastFailure: PlaybackFailure? = null
    private var lastCancellation: CancellationReason? = null
    private var droppedFrames: Int = 0
    private val decoderObservations = mutableListOf<DecoderObservation>()
    private val capabilityObservations = mutableListOf<CapabilityObservation>()

    var audioOutputProgress: dev.infusion.core.playback.api.AudioOutputProgress =
        dev.infusion.core.playback.api.AudioOutputProgress.NONE

    val playbackState: PlaybackState get() = state

    private fun now(): Long = clock.epochMs

    private fun emit(event: PlaybackEvent) {
        listeners.forEach { it.onPlaybackEvent(event) }
    }

    private fun transition(to: PlaybackState, cause: StateCause) {
        check(PlaybackStateMachine.canTransition(state, to)) {
            "illegal transition ${state} -> $to"
        }
        state = to
        emit(PlaybackEvent.StateChanged(now(), to, cause))
    }

    private fun fail(failure: PlaybackFailure) {
        lastFailure = failure
        emit(PlaybackEvent.Failed(now(), failure))
        transition(PlaybackState.FAILED, StateCause.FAILURE_OCCURRED)
    }

    private fun checkCommandable() {
        check(state !in TERMINAL_STATES) { "session is terminal ($state)" }
    }

    private fun checkActive() {
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
        preparedRequests += request
        script.failOnPrepare?.let { fail(it); return }
        transition(PlaybackState.PREPARING, StateCause.PREPARE_REQUESTED)
        emit(PlaybackEvent.SessionPhaseChanged(now(), SessionPhase.PREPARING))
        prepared = true
        if (!script.deferPrepare) completePrepare()
    }

    fun completePrepare() {
        check(state == PlaybackState.PREPARING) { "completePrepare requires PREPARING (state=$state)" }
        emit(PlaybackEvent.TimelineChanged(now(), PlaybackTimeline(script.durationMs, script.isSeekable, script.isLive)))
        emit(PlaybackEvent.TracksChanged(now(), TrackInventory(script.videoTracks, script.audioTracks, script.subtitleTracks)))
        emit(PlaybackEvent.TrackSelectionChanged(now(), TrackSelection.DEFAULT))
        if (script.emitDecoderObservations) {
            observeDecoder(DecoderObservation(DecoderKind.VIDEO, "fake.hw.decoder", isHardwareAccelerated = true))
            observeCapability(
                CapabilityObservation(
                    kind = CapabilityKind.VIDEO,
                    state = CapabilityState.UNKNOWN,
                    evidence = "video/avc 1920x1080 observed; no assessment performed",
                    timestampMs = now(),
                ),
            )
        }
        if (script.emitDegradedObservation) {
            observeCapability(
                CapabilityObservation(
                    kind = CapabilityKind.VIDEO,
                    state = CapabilityState.DEGRADED,
                    evidence = "software decoder 'fake.sw.decoder' active",
                    remedy = "select hardware-supported track or lower resolution",
                    timestampMs = now(),
                ),
            )
        }
        transition(PlaybackState.BUFFERING, StateCause.BUFFERING_STARTED)
        emit(PlaybackEvent.BufferingChanged(now(), BufferingState(true, 0L, BufferingReason.INITIAL_LOAD)))
        transition(PlaybackState.READY, StateCause.PREPARE_COMPLETED)
        emit(PlaybackEvent.BufferingChanged(now(), BufferingState(false, script.durationMs, BufferingReason.UNKNOWN)))
        emit(PlaybackEvent.SessionPhaseChanged(now(), SessionPhase.PREPARED))
    }

    private fun observeDecoder(decoder: DecoderObservation) {
        decoderObservations += decoder
        emit(PlaybackEvent.DecoderObserved(now(), decoder))
    }

    private fun observeCapability(observation: CapabilityObservation) {
        capabilityObservations += observation
        emit(PlaybackEvent.CapabilityObserved(now(), observation))
    }

    override fun play() {
        checkActive()
        if (state == PlaybackState.ENDED) {

            positionMs = 0L
            emit(PlaybackEvent.PositionChanged(now(), 0L, 0L))
        }
        script.failOnPlay?.let { fail(it); return }
        check(state == PlaybackState.READY || state == PlaybackState.PLAYING ||
            state == PlaybackState.PAUSED || state == PlaybackState.BUFFERING || state == PlaybackState.ENDED) {
            "play requires an active state (state=$state)"
        }
        if (state != PlaybackState.PLAYING) {
            transition(PlaybackState.PLAYING, StateCause.PLAY_REQUESTED)
        }
    }

    override fun pause() {
        checkActive()
        check(state == PlaybackState.PLAYING || state == PlaybackState.READY || state == PlaybackState.BUFFERING) {
            "pause requires PLAYING/READY/BUFFERING (state=$state)"
        }
        if (state != PlaybackState.PAUSED) {
            transition(PlaybackState.PAUSED, StateCause.PAUSE_REQUESTED)
        }
    }

    override fun seekTo(positionMs: Long) {
        require(positionMs >= 0) { "seek position must be >= 0 (was $positionMs)" }
        checkActive()
        script.failOnSeek?.let { fail(it); return }
        val wasPlaying = state == PlaybackState.PLAYING
        val clamped = script.durationMs?.let { minOf(positionMs, it) } ?: positionMs
        this.positionMs = clamped
        if (script.dropFramesOnSeek > 0) {
            droppedFrames += script.dropFramesOnSeek

            emit(PlaybackEvent.DroppedFrames(now(), droppedFrames))
        }
        emit(PlaybackEvent.PositionChanged(now(), clamped, clamped))
        transition(PlaybackState.BUFFERING, StateCause.SEEK_REQUESTED)
        emit(PlaybackEvent.BufferingChanged(now(), BufferingState(true, clamped, BufferingReason.SEEK)))
        transition(if (wasPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED, StateCause.SEEK_REQUESTED)
        emit(PlaybackEvent.BufferingChanged(now(), BufferingState(false, clamped, BufferingReason.UNKNOWN)))
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
        emit(PlaybackEvent.Cancelled(now(), cancellationReason))
        transition(PlaybackState.CANCELLED, cause)
    }

    override fun selectTrack(selection: TrackSelection) {
        checkActive()
        check(state in ACTIVE_PLAYBACK_STATES) { "selectTrack requires an active session (state=$state)" }
        selection.videoTrackId?.let { id ->
            require(script.videoTracks.any { it.id == id }) { "unknown video track $id" }
        }
        selection.audioTrackId?.let { id ->
            require(script.audioTracks.any { it.id == id }) { "unknown audio track $id" }
        }
        if (selection.subtitleMode == SubtitleMode.SELECTED) {
            val id = selection.subtitleTrackId
            require(id != null) { "subtitleMode=SELECTED requires a subtitleTrackId" }
            require(script.subtitleTracks.any { it.id == id }) { "unknown subtitle track $id" }
        }
        currentSelection = selection
        emit(PlaybackEvent.TrackSelectionChanged(now(), selection))
    }

    override fun snapshot(): DiagnosticsSnapshot = DiagnosticsSnapshot(
        sessionId = sessionId,
        lineage = lineage,
        engineId = engine.id,
        state = state,
        positionMs = if (prepared) positionMs else null,
        timeline = if (prepared) PlaybackTimeline(script.durationMs, script.isSeekable, script.isLive) else null,
        videoSize = null,
        droppedFrames = droppedFrames.takeIf { it > 0 },
        decoderObservations = decoderObservations.toList(),
        capabilityObservations = capabilityObservations.toList(),
        trackInventory = if (prepared) TrackInventory(script.videoTracks, script.audioTracks, script.subtitleTracks) else null,
        lastFailure = lastFailure,
        lastCancellationReason = lastCancellation,
        audioOutputProgress = audioOutputProgress,
        createdAtMs = now(),
    )

    fun advanceClock(ms: Long) {
        check(state == PlaybackState.PLAYING) { "advanceClock requires PLAYING (state=$state)" }
        clock.advance(ms)
        val target = script.durationMs?.let { minOf(positionMs + ms, it) } ?: (positionMs + ms)
        positionMs = target
        emit(PlaybackEvent.PositionChanged(now(), positionMs, positionMs))
        if (script.durationMs != null && positionMs >= script.durationMs) {
            emit(PlaybackEvent.Ended(now(), EndReason.END_OF_MEDIA))
            transition(PlaybackState.ENDED, StateCause.MEDIA_ENDED)
        }
    }

    fun emitBuffering(isBuffering: Boolean) {
        emit(
            PlaybackEvent.BufferingChanged(
                now(),
                BufferingState(
                    isBuffering = isBuffering,
                    bufferedPositionMs = positionMs,
                    reason = if (isBuffering) BufferingReason.UNDERFLOW else BufferingReason.UNKNOWN,
                ),
            ),
        )
    }

    fun emitTracks(inventory: TrackInventory) {
        emit(PlaybackEvent.TracksChanged(now(), inventory))
    }

    fun emitChapters(chapters: List<dev.infusion.core.playback.api.ChapterMark>) {
        emit(PlaybackEvent.ChaptersObserved(now(), chapters))
    }

    fun emitLatePositionChanged(positionMs: Long) {
        emit(PlaybackEvent.PositionChanged(now(), positionMs, positionMs))
    }

    fun emitFailure(failure: PlaybackFailure) {
        check(state !in TERMINAL_STATES) { "emitFailure requires a non-terminal session (state=$state)" }
        fail(failure)
    }

    override fun addListener(listener: PlaybackEventListener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackEventListener) {
        listeners -= listener
    }

    override fun release() {
        if (state == PlaybackState.RELEASED) return
        emit(PlaybackEvent.SessionPhaseChanged(now(), SessionPhase.RELEASED))
        transition(PlaybackState.RELEASED, StateCause.SESSION_RELEASED)
        engine.onSessionReleased(this)
    }

    fun currentState(): PlaybackState = state

    fun currentPositionMs(): Long = positionMs

    fun currentSelection(): TrackSelection = currentSelection

    val listenerCount: Int
        get() = listeners.size

    private companion object {
        val TERMINAL_STATES = setOf(PlaybackState.CANCELLED, PlaybackState.FAILED, PlaybackState.RELEASED)
        val ACTIVE_PLAYBACK_STATES = setOf(PlaybackState.READY, PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.BUFFERING)
    }
}
