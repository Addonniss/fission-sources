package dev.infusion.feature.vertical.player

import dev.infusion.core.model.EventId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.SessionId
import dev.infusion.core.model.watch.ResumeAnchor
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.playback.api.ActivePlaybackReporter
import dev.infusion.core.playback.api.AttemptReason
import dev.infusion.core.playback.api.AudioLanguagePreferences
import dev.infusion.core.playback.api.AudioMatchReason
import dev.infusion.core.playback.api.AudioRouteFallback
import dev.infusion.core.playback.api.AudioSelectionDecision
import dev.infusion.core.playback.api.AudioTrackSelector
import dev.infusion.core.playback.api.ChapterNavigation
import dev.infusion.core.playback.api.CompletionThresholds
import dev.infusion.core.playback.api.DiagnosticsSnapshot
import dev.infusion.core.playback.api.MeasuredVideoEvidence
import dev.infusion.core.playback.api.NextEpisodePreferences
import dev.infusion.core.playback.api.NextEpisodeSettings
import dev.infusion.core.playback.api.NextEpisodeTrigger
import dev.infusion.core.playback.api.PlaybackControlsAutoHide
import dev.infusion.core.playback.api.PlaybackEngine
import dev.infusion.core.playback.api.PlaybackEvent
import dev.infusion.core.playback.api.PlaybackSession
import dev.infusion.core.playback.api.PlaybackState
import dev.infusion.core.playback.api.RelativeSeekOutcome
import dev.infusion.core.playback.api.RelativeSeekStep
import dev.infusion.core.playback.api.ChapterMark
import dev.infusion.core.playback.api.PlaybackSegment
import dev.infusion.core.playback.api.SegmentAttemptEvidence
import dev.infusion.core.playback.api.SegmentEvidenceInput
import dev.infusion.core.playback.api.SegmentEvidencePreferences
import dev.infusion.core.playback.api.SegmentEvidenceSettings
import dev.infusion.core.playback.api.SegmentLookupIdentity
import dev.infusion.core.playback.api.SegmentType
import dev.infusion.core.playback.api.SegmentSourceOutcome
import dev.infusion.core.playback.api.ServerResumePosition
import dev.infusion.core.playback.api.SkipIntroProjection
import dev.infusion.core.playback.api.SkipIntroUi
import dev.infusion.core.playback.api.StingerEvidence
import dev.infusion.core.playback.api.StingerLookupIdentity
import dev.infusion.core.playback.api.StingerReconciliation
import dev.infusion.core.playback.api.SessionLineage
import dev.infusion.core.playback.api.StopReason
import dev.infusion.core.playback.api.SubtitleForcednessGuard
import dev.infusion.core.playback.api.SubtitleForcednessVerdict
import dev.infusion.core.playback.api.SubtitleMode
import dev.infusion.core.playback.api.SubtitlePreferences
import dev.infusion.core.playback.api.SubtitleSelectionDecision
import dev.infusion.core.playback.api.SubtitleTrackSelector
import dev.infusion.core.playback.api.SelectedTrackResolution
import dev.infusion.core.playback.api.TrackId
import dev.infusion.core.playback.api.TrackInventory
import dev.infusion.core.playback.api.TrackSelection
import dev.infusion.core.playback.api.TransportRequest
import dev.infusion.core.sync.WatchSyncCoordinator
import dev.infusion.feature.vertical.model.PlayerUiState
import dev.infusion.feature.vertical.model.PlayerSubordinateSurface
import dev.infusion.feature.vertical.model.StingerUi
import dev.infusion.feature.vertical.model.SubtitleAvailability
import dev.infusion.feature.vertical.model.TrackSelectionOutcome
import dev.infusion.feature.vertical.model.EpisodeIdentityUi
import dev.infusion.feature.vertical.model.NextEpisodeUi
import dev.infusion.feature.vertical.model.UpNextAvailability
import dev.infusion.feature.vertical.model.UpNextUi
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerStateHolder(
    private val watchKey: WatchKey,
    private val engine: PlaybackEngine,
    private val coordinator: WatchSyncCoordinator,
    private val scope: CoroutineScope,

    private val transportFor: suspend (candidate: PlayableCandidate, lineage: SessionLineage) -> TransportRequest,

    private val sourceSessionBinder: suspend (watchSessionId: SessionId, candidate: PlayableCandidate) -> Unit = { _, _ -> },

    private val sourceSessionUnbinder: suspend (SessionId) -> Unit = {},

    private val diagnosticsSink: (DiagnosticsSnapshot) -> Unit = {},
    private val now: () -> Instant = Instant::now,
    private val progressThrottleMs: Long = 5_000,
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.Default,

    private val terminalStopScope: CoroutineScope = CoroutineScope(SupervisorJob() + writeDispatcher),

    private val seekStepMs: Long = RelativeSeekStep.DEFAULT.stepMs,

    private val relativeSeekStepMs: () -> Long = { seekStepMs },

    private val subtitlePreferences: () -> SubtitlePreferences? = { null },

    private val captioningLanguage: () -> String? = { null },

    private val audioPreferences: () -> AudioLanguagePreferences? = { null },

    private val systemLanguages: () -> List<String> = { emptyList() },

    private val completionThresholds: () -> CompletionThresholds? = { null },

    private val controlsAutoHide: () -> PlaybackControlsAutoHide? = { null },

    private val segmentEvidence: PlaybackSegmentCoordinator? = null,

    private val segmentAttemptEvidence: suspend (WatchKey, PlayableCandidate) -> SegmentAttemptEvidence = { _, _ ->
        SegmentAttemptEvidence()
    },

    private val segmentSettings: () -> SegmentEvidenceSettings? = { null },

    private val stingerEvidence: PlaybackStingerCoordinator? = null,

    private val nextEpisodeSettings: () -> NextEpisodeSettings? = { null },

    private val upNextState: StateFlow<UpNextUi?>? = null,

    private val onAttemptReleased: () -> Unit = {},

    private val onAttemptTerminal: () -> Unit = {},

    private val activeReporterFor: (PlayableCandidate) -> ActivePlaybackReporter? = { null },

    private val errorRecovery: (suspend (candidate: PlayableCandidate, positionMs: Long, failure: dev.infusion.core.playback.api.PlaybackFailure) -> PlayableCandidate?)? = null,

    private val startWithControlsVisible: Boolean = true,

    private val overlayTimeoutMs: Long? = PlaybackControlsAutoHide.DEFAULT.timeoutMs,

    private val startupSettleCeilingMs: Long = 5_000L,

    private val startupDisplayMatchCeilingMs: Long = 8_000L,

    private val serverResumePosition: (suspend (candidate: PlayableCandidate) -> ServerResumePosition?)? = null,
) {

    private val _state = MutableStateFlow<PlayerUiState?>(null)
    val state: StateFlow<PlayerUiState?> = _state.asStateFlow()

    private var session: PlaybackSession? = null

    val activeEngineSessionId: dev.infusion.core.playback.api.SessionId? get() = session?.sessionId

    private var watchSessionId: SessionId? = null
    private var lastRecordedPositionMs: Long = 0
    private var currentCanSeek: Boolean = false

    private var attemptAnchor: ResumeAnchor? = null

    private var attemptStartMode: PlaybackStartMode = PlaybackStartMode.RESUME

    private var attemptLocalResumeObservedAt: java.time.Instant? = null
    private var controlsVisible: Boolean = false

    private var informationalVisible: Boolean = false

    private var overlayTimeoutJob: kotlinx.coroutines.Job? = null

    private var trackSetupCeilingJob: kotlinx.coroutines.Job? = null

    private var displayMatchCeilingJob: kotlinx.coroutines.Job? = null

    private var audioManualPin: Boolean = false
    private var subtitleManualPin: Boolean = false

    private var pendingAudioTrackId: TrackId? = null

    private var pendingTextApplication: SubtitleTextApplication? = null

    private var appliedSubtitleApplication: SubtitleTextApplication? = null

    private var subtitleDecision: SubtitleSelectionDecision? = null

    private var audioDecision: AudioSelectionDecision? = null

    private var attemptAudioPreferences: AudioLanguagePreferences? = null

    private var attemptSystemLanguages: List<String> = emptyList()

    private var attemptSubtitlePreferences: SubtitlePreferences? = null

    private var attemptCaptioningLanguage: String? = null

    private var attemptCompletionThresholds: CompletionThresholds? = null

    private var attemptControlsAutoHide: PlaybackControlsAutoHide? = null

    private var attemptSegmentSettings: SegmentEvidenceSettings? = null

    @Volatile
    private var attemptSegmentIdentity: SegmentLookupIdentity = SegmentLookupIdentity()

    @Volatile
    private var attemptSegmentChapters: List<ChapterMark> = emptyList()

    @Volatile
    private var attemptSegmentServerSegments: List<PlaybackSegment> = emptyList()

    @Volatile
    private var attemptEngineChapters: List<ChapterMark> = emptyList()

    @Volatile
    private var attemptSegmentStarted: Boolean = false

    private var segmentCollector: kotlinx.coroutines.Job? = null

    @Volatile
    private var attemptEnableSkipIntro: Boolean = SegmentEvidencePreferences.DEFAULT.enableSkipIntro
    @Volatile
    private var attemptAutoSkipIntro: Boolean = SegmentEvidencePreferences.DEFAULT.autoSkipIntro
    @Volatile
    private var attemptAutoSkipDelaySeconds: Int =
        SegmentEvidencePreferences.DEFAULT.autoSkipDelaySeconds
    @Volatile
    private var attemptPersistentSkipButton: Boolean =
        SegmentEvidencePreferences.DEFAULT.persistentSkipButton

    private var skipIntroFullscreenSuppressed: Boolean = false

    private var skipIntroShownAtMs: Long? = null

    private var autoSkipConsumedKey: String? = null

    private var skipCountdownJob: kotlinx.coroutines.Job? = null

    private var skipCountdownRemaining: Int? = null

    private var skipCountdownTargetMs: Long? = null
    private var skipCountdownTargetKey: String? = null

    private var pendingNavigationPositionMs: Long? = null

    private var pendingNavigationForward: Boolean = false

    private var pendingRelativeSeekPositionMs: Long? = null

    private var pendingRelativeSeekForward: Boolean = false

    @Volatile
    private var attemptStingerIdentity: StingerLookupIdentity = StingerLookupIdentity()

    @Volatile
    private var attemptStingerVerdict: StingerEvidence? = null

    @Volatile
    private var attemptStingerTriggerMs: Long? = null
    private var stingerEarlyShown: Boolean = false
    private var stingerCreditsShown: Boolean = false

    private var stingerCollector: kotlinx.coroutines.Job? = null

    private var stingerToastJob: kotlinx.coroutines.Job? = null

    private val _nextTransition = MutableStateFlow<NextTransitionRequest?>(null)
    val nextTransition: StateFlow<NextTransitionRequest?> = _nextTransition.asStateFlow()

    @Volatile
    private var attemptEnableNextEpisode: Boolean = NextEpisodePreferences.DEFAULT.enableNextEpisode
    @Volatile
    private var attemptOnlineNextPriority: Boolean =
        NextEpisodePreferences.DEFAULT.onlineNextMetadataPriority
    @Volatile
    private var attemptAutoPlayNext: Boolean = NextEpisodePreferences.DEFAULT.autoPlayNextEpisode
    @Volatile
    private var attemptAutoPlayDelaySeconds: Int = NextEpisodePreferences.DEFAULT.autoPlayDelaySeconds
    @Volatile
    private var attemptFallbackTriggerPercent: Int =
        NextEpisodePreferences.DEFAULT.fallbackTriggerPercent

    private var nextAutoSuppressed: Boolean = false

    private var nextFullscreenSuppressed: Boolean = false

    private var attemptNextTransitioned: Boolean = false

    private var nextPromptActive: Boolean = false

    private var nextCountdownConsumed: Boolean = false
    private var nextCountdownJob: kotlinx.coroutines.Job? = null
    private var nextCountdownRemaining: Int? = null

    private var nextRequestCounter: Long = 0L

    private var pendingNextRequestId: Long? = null

    data class NextTransitionRequest(
        val requestId: Long,
        val nextEpisode: EpisodeIdentityUi,
    )

    private var attemptThresholdCompleted: Boolean = false

    private val isEpisode: Boolean = watchKey.isEpisode

    private var activePlaybackReporter: ActivePlaybackReporter? = null

    private var recoveryInFlight: Boolean = false

    private var recoveryBudgetConsumed: Boolean = false

    private var audioRoutePlan: AudioRouteFallback.Plan? = null

    private var pendingAudioRouteKey: AudioRouteFallback.RouteKey? = null

    private var audioRouteFallbackAttempts: Int = 0

    private var audioRouteFallbackStartPositionMs: Long = 0L

    private var audioRouteFallbackPlaying: Boolean = false

    val watchSessionIdForTest: SessionId? get() = watchSessionId
    val lastAttemptSessionIdForTest: SessionId? get() = lastAttemptSessionId
    val sessionForTest: PlaybackSession? get() = session

    val activePlaybackSession: PlaybackSession? get() = session

    private var lastAttemptSessionId: SessionId? = null

    private var upNextCollector: kotlinx.coroutines.Job? = null

    init {

        if (isEpisode && upNextState != null) {
            upNextCollector = scope.launch {
                upNextState!!.collect { derived ->
                    if (derived == null) return@collect
                    val s = _state.value ?: return@collect
                    if (s.phase == PlaybackState.ENDED && s.upNext != null) {
                        _state.value = s.copy(upNext = derived)
                    }
                }
            }
        }

        if (segmentEvidence != null) {
            segmentCollector = scope.launch {
                segmentEvidence.timeline.collect { timeline ->
                    val s = _state.value ?: return@collect
                    if (s.segments != timeline) _state.value = s.copy(segments = timeline)

                    recomputeSkipIntro()

                    recomputeNextEpisode(_state.value?.positionMs ?: 0L)
                }
            }
        }

        if (stingerEvidence != null) {
            stingerCollector = scope.launch {
                stingerEvidence.evidence.collect { verdict ->
                    attemptStingerVerdict = verdict
                    attemptStingerTriggerMs = if (verdict?.isPositive == true) {
                        StingerReconciliation.stingerTriggerMs(
                            durationMs = _state.value?.durationMs,
                            chapters = mergedChapters(),
                            percent = attemptSegmentSettings?.stingerTriggerPercent
                                ?: SegmentEvidencePreferences.DEFAULT.stingerTriggerPercent,
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    private var pendingTerminalEvent: WatchEvent.Local? = null

    val hasFailure: Boolean get() = _state.value?.failure != null

    suspend fun start(
        candidate: PlayableCandidate,
        resumePositionMs: Long? = null,
        sessionId: SessionId? = null,
        startMode: PlaybackStartMode = PlaybackStartMode.RESUME,

        localResumeObservedAt: Instant? = null,
    ): SessionId {

        attemptStartMode = startMode
        attemptLocalResumeObservedAt = localResumeObservedAt
        recoveryInFlight = false
        recoveryBudgetConsumed = false

        audioRoutePlan = null
        pendingAudioRouteKey = null
        audioRouteFallbackAttempts = 0
        audioRouteFallbackPlaying = false
        audioRouteFallbackStartPositionMs = 0L
        return startInternal(candidate, resumePositionMs, sessionId)
    }

    private fun serverResumeOutranksLocal(claim: ServerResumePosition?, localPositionMs: Long?): Boolean {
        if (claim == null) return false
        return dev.infusion.feature.vertical.media.ResumeAuthority.serverDecides(
            serverObservedAt = claim.observedAt,
            localObservedAt = attemptLocalResumeObservedAt,

            localObservationPresent = localPositionMs != null || attemptLocalResumeObservedAt != null,
        )
    }

    private suspend fun startInternal(
        candidate: PlayableCandidate,
        resumePositionMs: Long?,
        sessionId: SessionId?,
    ): SessionId {

        val startMode = attemptStartMode

        require(candidate.expectedMode != dev.infusion.core.model.ExpectedMode.EXTERNAL_LINK) {
            "external-link candidates must never reach the playback engine"
        }

        val uuid = UUID.randomUUID()
        val watchSid = sessionId ?: SessionId(uuid)
        lastAttemptSessionId = watchSid

        val serverResume = when {
            startMode == PlaybackStartMode.FROM_BEGINNING -> null
            recoveryInFlight -> null
            else -> serverResumePosition?.let { seam ->
                try {
                    withContext(writeDispatcher) { seam(candidate) }
                } catch (_: Exception) {
                    null
                }
            }
        }
        val lineage = SessionLineage.first(
            sessionId = dev.infusion.core.playback.api.SessionId(uuid.toString()),
            reason = if (
                startMode == PlaybackStartMode.RESUME &&
                when {
                    serverResumeOutranksLocal(serverResume, resumePositionMs) -> serverResume!!.positionMs > 0L
                    else -> resumePositionMs != null
                }
            ) {
                AttemptReason.RESUMED
            } else {
                AttemptReason.USER_INITIATED
            },
            requestId = "req:$uuid",
            nowEpochMs = now().toEpochMilli(),
        )
        val resolvedRequest = transportFor(candidate, lineage)

        val serverClaimsResume = serverResumeOutranksLocal(serverResume, resumePositionMs)
        val effectiveResumePositionMs = when {
            startMode == PlaybackStartMode.FROM_BEGINNING -> null
            serverClaimsResume -> serverResume!!.positionMs
            else -> resumePositionMs?.takeIf { it > 0L }
                ?: resolvedRequest.startPositionMs.takeIf { it > 0L }
        }

        val request = when {
            startMode == PlaybackStartMode.FROM_BEGINNING ->
                resolvedRequest.copy(startPositionMs = 0L)
            serverClaimsResume ->
                resolvedRequest.copy(startPositionMs = serverResume!!.positionMs)
            effectiveResumePositionMs != null ->
                resolvedRequest.copy(startPositionMs = effectiveResumePositionMs)
            else -> resolvedRequest
        }
        session?.release()
        val s = engine.createSession(lineage)
        session = s

        if (activePlaybackReporter == null) {
            activePlaybackReporter = activeReporterFor(candidate)
        }
        watchSessionId = watchSid
        lastRecordedPositionMs = effectiveResumePositionMs ?: 0
        attemptAnchor = ResumeAnchor(
            nativeItemId = candidate.nativeItemId,
            familyId = candidate.familyId,
            versionId = null,
            positionMs = effectiveResumePositionMs ?: 0,
            anchoredAt = now(),
        )
        controlsVisible = startWithControlsVisible
        informationalVisible = startWithControlsVisible
        audioManualPin = false
        subtitleManualPin = false
        pendingAudioTrackId = null
        pendingTextApplication = null
        appliedSubtitleApplication = null
        subtitleDecision = null

        attemptAudioPreferences = audioPreferences()
        attemptSystemLanguages = systemLanguages()
        audioDecision = null

        attemptSubtitlePreferences = subtitlePreferences()
        attemptCaptioningLanguage = captioningLanguage()

        attemptCompletionThresholds = completionThresholds()
        attemptThresholdCompleted = false

        attemptControlsAutoHide = controlsAutoHide()

        attemptSegmentSettings = segmentSettings()

        attemptEnableSkipIntro = attemptSegmentSettings?.enableSkipIntro
            ?: SegmentEvidencePreferences.DEFAULT.enableSkipIntro
        attemptAutoSkipIntro = attemptSegmentSettings?.autoSkipIntro
            ?: SegmentEvidencePreferences.DEFAULT.autoSkipIntro
        attemptAutoSkipDelaySeconds = attemptSegmentSettings?.autoSkipDelaySeconds
            ?: SegmentEvidencePreferences.DEFAULT.autoSkipDelaySeconds
        attemptPersistentSkipButton = attemptSegmentSettings?.persistentSkipButton
            ?: SegmentEvidencePreferences.DEFAULT.persistentSkipButton
        skipIntroFullscreenSuppressed = false
        skipIntroShownAtMs = null
        autoSkipConsumedKey = null
        cancelSkipCountdown()

        clearPendingSeekTargets()
        attemptStingerIdentity = StingerLookupIdentity()
        attemptStingerVerdict = null
        attemptStingerTriggerMs = null
        stingerEarlyShown = false
        stingerCreditsShown = false
        cancelStingerToast()

        val nextSettings = nextEpisodeSettings()
        attemptEnableNextEpisode = nextSettings?.enableNextEpisode
            ?: NextEpisodePreferences.DEFAULT.enableNextEpisode
        attemptOnlineNextPriority = nextSettings?.onlineNextMetadataPriority
            ?: NextEpisodePreferences.DEFAULT.onlineNextMetadataPriority
        attemptAutoPlayNext = nextSettings?.autoPlayNextEpisode
            ?: NextEpisodePreferences.DEFAULT.autoPlayNextEpisode
        attemptAutoPlayDelaySeconds = nextSettings?.autoPlayDelaySeconds
            ?: NextEpisodePreferences.DEFAULT.autoPlayDelaySeconds
        attemptFallbackTriggerPercent = nextSettings?.fallbackTriggerPercent
            ?: NextEpisodePreferences.DEFAULT.fallbackTriggerPercent
        nextAutoSuppressed = false
        nextFullscreenSuppressed = false
        nextPromptActive = false
        nextCountdownConsumed = false
        attemptNextTransitioned = false
        pendingNextRequestId = null
        _nextTransition.value = null
        cancelNextCountdown()

        attemptEngineChapters = emptyList()
        attemptSegmentStarted = false

        _state.value = _state.value?.copy(upNext = null)
        meaningfulState(candidate, effectiveResumePositionMs ?: 0)

        beginSegmentLookup(candidate)

        restartOverlayTimeout()

        cancelDisplayMatchSettleCeiling()
        armTrackSetupSettleCeiling()
        maybeSettleInitialTrackSetup()
        record(
            WatchEvent.Local(
                eventId = EventId.random(),
                watchKey = watchKey,
                kind = WatchEvent.Kind.START,
                occurredAt = now(),
                sessionId = watchSid,
                positionMs = effectiveResumePositionMs ?: 0,

                durationMs = candidate.durationMs,
                anchor = ResumeAnchor(
                    nativeItemId = candidate.nativeItemId,
                    familyId = candidate.familyId,
                    versionId = null,
                    positionMs = effectiveResumePositionMs ?: 0,
                    anchoredAt = now(),
                ),
            ),
        )
        s.addListener { event -> onEngineEvent(event) }
        s.prepare(request)

        val snapshotAfterPrepare = s.snapshot()
        val afterPrepare = snapshotAfterPrepare.state

        diagnosticsSink(snapshotAfterPrepare)
        if (afterPrepare != PlaybackState.FAILED &&
            afterPrepare != PlaybackState.CANCELLED &&
            afterPrepare != PlaybackState.RELEASED
        ) {

            try {
                withContext(writeDispatcher) {
                    sourceSessionBinder(watchSid, candidate)
                    activePlaybackReporter?.bind()
                }
            } catch (_: Exception) {

            }
            s.play()
        }
        return watchSid
    }

    private fun meaningfulState(candidate: PlayableCandidate, positionMs: Long) {
        _state.value = PlayerUiState(
            candidate = candidate,
            phase = PlaybackState.IDLE,
            positionMs = positionMs,
            durationMs = candidate.durationMs,
            controlsVisible = startWithControlsVisible,
            informationalVisible = startWithControlsVisible,
            watchRecorded = true,
            subtitleAvailability = if (candidate.embeddedSubtitles.isNotEmpty()) {
                SubtitleAvailability.Available
            } else {
                SubtitleAvailability.Unknown
            },
        )
    }

    private fun onEngineEvent(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.StateChanged -> handlePhase(event.state)
            is PlaybackEvent.PositionChanged -> handlePosition(event.positionMs, event.bufferedPositionMs)
            is PlaybackEvent.TimelineChanged -> handleTimeline(event.timeline)
            is PlaybackEvent.Failed -> handleFailure(event.failure)
            is PlaybackEvent.Cancelled -> handleCancelled()
            is PlaybackEvent.Ended -> handleEnded()
            is PlaybackEvent.BufferingChanged -> handleBuffering(event.buffering)
            is PlaybackEvent.TracksChanged -> handleTracks(event.inventory)
            is PlaybackEvent.ChaptersObserved -> handleEngineChapters(event.chapters)
            is PlaybackEvent.TrackSelectionChanged -> handleTrackSelection(event.selection)
            is PlaybackEvent.MeasuredVideoEvidenceObserved -> handleMeasuredVideoEvidence(event.evidence)

            is PlaybackEvent.RenderedFirstFrame -> handleRenderedFirstFrame()

            else -> Unit
        }
    }

    private fun handleRenderedFirstFrame() {
        val current = _state.value ?: return
        if (current.firstFrameRendered) return
        _state.value = current.copy(firstFrameRendered = true, startupFrameReady = true)

        armDisplayMatchSettleCeiling()
    }

    private fun handlePhase(phase: PlaybackState) {
        val current = _state.value ?: return

        if (phase == PlaybackState.FAILED && recoveryInFlight) {
            PlayerForensicsLog.event("recovery-phase-withheld", "phase=FAILED")
            return
        }
        val wasPaused = current.phase == PlaybackState.PAUSED
        _state.value = current.copy(phase = phase)

        if (phase == PlaybackState.PAUSED && !wasPaused) {
            showControls()
        }

        if (phase == PlaybackState.PLAYING && informationalVisible && !controlsVisible) {
            hideControls()
        }
        activePlaybackReporter?.let { reporter ->
            when (phase) {
                PlaybackState.PLAYING -> scope.launch(writeDispatcher) {
                    if (wasPaused) reporter.onUnpause(current.positionMs, currentCanSeek, current.durationMs)
                    else reporter.onPlayerState(current.positionMs, false, currentCanSeek, current.durationMs)
                }
                PlaybackState.PAUSED -> scope.launch(writeDispatcher) {
                    reporter.onPause(current.positionMs, currentCanSeek, current.durationMs)
                }
                else -> Unit
            }
        }

        if (phase in ACTIVE_PLAYBACK_STATES) {
            flushPendingSelections()
        }

        maybeSettleInitialTrackSetup()

        if (phase == PlaybackState.PLAYING) armDisplayMatchSettleCeiling()

        maybePublishStartupFrameReady()
    }

    private fun maybePublishStartupFrameReady() {
        val current = _state.value ?: return
        if (current.startupFrameReady) return
        if (current.phase != PlaybackState.PLAYING || current.isBuffering) return
        _state.value = current.copy(startupFrameReady = true)
    }

    private fun flushPendingSelections() {
        val current = _state.value ?: return
        if (!selectionPossible(current)) return
        val audioPending = pendingAudioTrackId
        val textPending = pendingTextApplication
        if (audioPending == null && textPending == null) return
        pendingAudioTrackId = null
        pendingTextApplication = null
        val selection = applySelectionToEngine(
            composeSelection(
                audioTrackId = audioPending ?: current.trackSelection.audioTrackId,
                text = if (subtitleManualPin) null else textPending ?: appliedSubtitleApplication,
                fallback = current.trackSelection,
            ),
        )
        _state.value = _state.value?.copy(trackSelection = selection)
    }

    private fun applySelectionToEngine(selection: TrackSelection): TrackSelection {
        val inventory = _state.value?.trackInventory
        val applied = if (inventory == null) selection else {
            val audio = selection.audioTrackId?.takeIf { id -> inventory.audioTracks.any { it.id == id } }
            val subtitleId = selection.subtitleTrackId?.takeIf { id ->
                selection.subtitleMode != SubtitleMode.SELECTED || inventory.subtitleTracks.any { it.id == id }
            }
            val subtitleMode =
                if (selection.subtitleMode == SubtitleMode.SELECTED && subtitleId == null) SubtitleMode.AUTO
                else selection.subtitleMode
            if (audio == selection.audioTrackId &&
                subtitleId == selection.subtitleTrackId &&
                subtitleMode == selection.subtitleMode
            ) {
                selection
            } else {
                selection.copy(
                    audioTrackId = audio,
                    subtitleTrackId = subtitleId,
                    subtitleMode = subtitleMode,
                )
            }
        }
        session?.selectTrack(applied)
        return applied
    }

    private fun composeSelection(
        audioTrackId: TrackId?,
        text: SubtitleTextApplication?,
        fallback: TrackSelection,
    ): TrackSelection = TrackSelection(
        audioTrackId = audioTrackId,
        subtitleTrackId = when (text) {
            is SubtitleTextApplication.Select -> text.trackId
            null -> fallback.subtitleTrackId
            else -> null
        },
        subtitleMode = when (text) {
            is SubtitleTextApplication.Select -> SubtitleMode.SELECTED
            SubtitleTextApplication.Off -> SubtitleMode.OFF
            SubtitleTextApplication.EngineDefault -> SubtitleMode.AUTO
            null -> fallback.subtitleMode
        },
    )

    private fun handleMeasuredVideoEvidence(evidence: MeasuredVideoEvidence) {
        val current = _state.value ?: return
        if (current.phase in TERMINAL_PLAYER_PHASES) return
        if (current.measuredVideo == evidence) return
        _state.value = current.copy(measuredVideo = evidence)
    }

    private fun handleTimeline(timeline: dev.infusion.core.playback.api.PlaybackTimeline) {
        val current = _state.value ?: return
        currentCanSeek = timeline.isSeekable

        _state.value = current.copy(
            durationMs = timeline.durationMs ?: current.durationMs,

            isLive = timeline.isLive,

            engineReportedDurationMs = if (timeline.isLive) {
                null
            } else {
                timeline.durationMs ?: current.engineReportedDurationMs
            },
        )

        if (timeline.durationMs != null && timeline.durationMs != current.durationMs) {
            refreshSegmentDuration(timeline.durationMs)
        }
    }

    private fun handlePosition(positionMs: Long, bufferedPositionMs: Long? = null) {
        val current = _state.value ?: return

        if (current.phase in TERMINAL_PLAYER_PHASES) return

        maybeRecordThresholdCompletion(positionMs, current.durationMs)
        _state.value = current.copy(
            positionMs = positionMs,

            bufferedPositionMs = bufferedPositionMs ?: current.bufferedPositionMs,
        )

        pendingNavigationPositionMs?.let { pending ->
            val caughtUp = if (pendingNavigationForward) positionMs >= pending else positionMs <= pending
            if (caughtUp) clearPendingNavigation()
        }

        pendingRelativeSeekPositionMs?.let { pending ->
            val caughtUp = if (pendingRelativeSeekForward) positionMs >= pending else positionMs <= pending
            if (caughtUp) clearPendingRelativeSeek()
        }
        recomputeSkipIntro()
        recomputeStingerNotice(positionMs)

        recomputeNextEpisode(positionMs)
        if (current.phase == PlaybackState.PLAYING || current.phase == PlaybackState.PAUSED) {
            activePlaybackReporter?.let { reporter ->
                scope.launch(writeDispatcher) {
                    reporter.onPlayerState(positionMs, current.phase == PlaybackState.PAUSED, currentCanSeek, current.durationMs)
                }
            }
        }

        val fallbackRoute = pendingAudioRouteKey
        if (fallbackRoute != null && !audioRouteFallbackPlaying && positionMs > audioRouteFallbackStartPositionMs) {
            audioRouteFallbackPlaying = true
            PlayerForensicsLog.event(
                "audio-route-fallback",
                "attempt=$audioRouteFallbackAttempts route=${AudioRouteFallback.describe(fallbackRoute)} " +
                    "outcome=playing positionMs=$positionMs",
            )
        }
        if (positionMs - lastRecordedPositionMs >= progressThrottleMs) {
            lastRecordedPositionMs = positionMs
            record(
                WatchEvent.Local(
                    eventId = EventId.random(),
                    watchKey = watchKey,
                    kind = WatchEvent.Kind.PROGRESS,
                    occurredAt = now(),
                    sessionId = watchSessionId,
                    positionMs = positionMs,
                    durationMs = current.durationMs,
                    anchor = _state.value?.candidate?.let { candidate ->
                        ResumeAnchor(
                            nativeItemId = candidate.nativeItemId,
                            familyId = candidate.familyId,
                            positionMs = positionMs,
                            anchoredAt = now(),
                        )
                    },
                ),
            )
            _state.value = _state.value?.copy(watchRecorded = true)
        }
    }

    private fun handleBuffering(buffering: dev.infusion.core.playback.api.BufferingState) {
        val current = _state.value ?: return
        val isBuffering = buffering.isBuffering
        if (current.failure != null || current.phase == PlaybackState.FAILED) {
            if (current.isBuffering) {
                _state.value = current.copy(isBuffering = false)
            }
            return
        }
        _state.value = current.copy(
            isBuffering = isBuffering,

            bufferedPositionMs = buffering.bufferedPositionMs ?: current.bufferedPositionMs,
        )

        maybePublishStartupFrameReady()
    }

    private fun handleTracks(inventory: TrackInventory) {
        val current = _state.value ?: return
        val availability = when {
            inventory.subtitleTracks.isNotEmpty() || current.candidate.embeddedSubtitles.isNotEmpty() ->
                SubtitleAvailability.Available

            else -> SubtitleAvailability.Unavailable("No subtitles are available for this source.")
        }
        _state.value = current.copy(
            trackInventory = inventory,
            subtitleAvailability = availability,
        )

        applyAudioPreference(inventory)

        applySubtitlePreference(inventory)

        maybeSettleInitialTrackSetup()
    }

    private fun handleTrackSelection(selection: TrackSelection) {
        val current = _state.value ?: return
        _state.value = current.copy(trackSelection = selection)
    }

    private fun applySubtitlePreference(inventory: TrackInventory) {
        if (subtitleManualPin) return
        val preferences = attemptSubtitlePreferences ?: return

        val audioLanguage = SelectedTrackResolution.selectedAudioLanguage(
            inventory,
            (audioDecision as? AudioSelectionDecision.Select)?.trackId,
        )
        val decision = SubtitleTrackSelector.decide(inventory, preferences, audioLanguage)
        val target: SubtitleTextApplication = resolveTextApplication(inventory, decision, audioLanguage)
        subtitleDecision = decision
        _state.value = _state.value?.copy(subtitleNoEligible = decision is SubtitleSelectionDecision.NoEligible)
        if (isAlreadyEffective(inventory, target)) return
        appliedSubtitleApplication = target
        pendingTextApplication = target
        flushPendingSelections()
    }

    private fun resolveTextApplication(
        inventory: TrackInventory,
        decision: SubtitleSelectionDecision,
        audioLanguage: String?,
    ): SubtitleTextApplication {
        if (decision !is SubtitleSelectionDecision.LeaveToEngine) {
            return when (decision) {
                is SubtitleSelectionDecision.Select -> SubtitleTextApplication.Select(decision.trackId)

                SubtitleSelectionDecision.Off, SubtitleSelectionDecision.NoEligible -> SubtitleTextApplication.Off
                SubtitleSelectionDecision.LeaveToEngine -> error("handled above")
            }
        }
        return when (val verdict = SubtitleForcednessGuard.reconcile(inventory, audioLanguage, attemptCaptioningLanguage)) {
            SubtitleForcednessVerdict.EngineDefaultStands -> SubtitleTextApplication.EngineDefault
            is SubtitleForcednessVerdict.Assert -> SubtitleTextApplication.Select(verdict.trackId)
            SubtitleForcednessVerdict.DisableText -> SubtitleTextApplication.Off
        }
    }

    private fun isAlreadyEffective(inventory: TrackInventory, target: SubtitleTextApplication): Boolean = when {
        target == appliedSubtitleApplication -> true
        target is SubtitleTextApplication.Select && appliedSubtitleApplication == SubtitleTextApplication.EngineDefault ->
            SelectedTrackResolution.resolvedSubtitleTrack(inventory, null)?.id == target.trackId
        else -> false
    }

    private fun applyAudioPreference(inventory: TrackInventory) {
        if (audioManualPin) return

        val seed = pendingAudioRouteKey
        if (seed != null) {
            val seeded = AudioRouteFallback.resolve(inventory, seed)
            if (seeded != null) {
                val already = (audioDecision as? AudioSelectionDecision.Select)?.trackId
                if (already != seeded) {
                    audioDecision = AudioSelectionDecision.Select(seeded, AudioMatchReason.ROUTE_FALLBACK)
                    queueAudioSelection(seeded)
                }
                return
            }
        }
        val preferences = attemptAudioPreferences ?: return
        if (inventory.audioTracks.isEmpty()) return
        val decided = audioDecision
        if (decided != null && decided !is AudioSelectionDecision.Select) return
        if (decided is AudioSelectionDecision.Select &&
            inventory.audioTracks.any { it.id == decided.trackId }
        ) {
            return
        }
        val next = AudioTrackSelector.decide(inventory, preferences, attemptSystemLanguages)
        audioDecision = next
        when (next) {
            is AudioSelectionDecision.Select -> queueAudioSelection(next.trackId)
            AudioSelectionDecision.LeaveToEngine ->

                if (decided != null) clearAudioAssertion()
        }
    }

    private fun queueAudioSelection(trackId: TrackId) {
        pendingAudioTrackId = trackId
        flushPendingSelections()
    }

    private fun clearAudioAssertion() {
        pendingAudioTrackId = null
        _state.value = _state.value?.let { state ->
            state.copy(trackSelection = state.trackSelection.copy(audioTrackId = null))
        }
    }

    private fun handleFailure(failure: dev.infusion.core.playback.api.PlaybackFailure) {
        val current = _state.value ?: return

        record(
            WatchEvent.Local(
                eventId = EventId.random(),
                watchKey = watchKey,
                kind = WatchEvent.Kind.SOURCE_FAILURE,
                occurredAt = now(),
                sessionId = watchSessionId,
                positionMs = current.positionMs,
                durationMs = current.durationMs,
            ),
        )

        if (beginAudioRouteFallback(current, failure)) return

        val recovery = errorRecovery
        val failedSession = session
        if (recovery != null && failedSession != null && !recoveryInFlight && !recoveryBudgetConsumed) {
            recoveryInFlight = true
            recoveryBudgetConsumed = true
            val capturedPositionMs = current.positionMs

            val recoveryPositionMs = if (attemptStartMode == PlaybackStartMode.FROM_BEGINNING) {
                0L
            } else {
                capturedPositionMs
            }
            val watchSid = watchSessionId
            PlayerForensicsLog.event(
                "recovery-negotiate",
                "positionMs=$recoveryPositionMs code=${failure.code} category=${failure.category.name}",
            )
            scope.launch {
                val replacement = try {
                    recovery(current.candidate, recoveryPositionMs, failure)
                } catch (_: Exception) {
                    null
                }
                if (replacement != null && recoveryInFlight && session === failedSession && watchSid != null) {
                    performRecoveryReplacement(replacement, recoveryPositionMs, watchSid, failedSession)
                } else if (session === failedSession) {

                    applyFailureUi(failure)
                }
                recoveryInFlight = false
            }
            return
        }
        controlsVisible = true
        applyFailureUi(failure)
    }

    private fun applyFailureUi(failure: dev.infusion.core.playback.api.PlaybackFailure) {
        val current = _state.value ?: return

        cancelOverlayTimeout()
        _state.value = current.copy(
            phase = PlaybackState.FAILED,
            failure = failure,
            controlsVisible = true,
            informationalVisible = true,

            isBuffering = false,
        )
    }

    private fun beginAudioRouteFallback(
        current: PlayerUiState,
        failure: dev.infusion.core.playback.api.PlaybackFailure,
    ): Boolean {
        if (audioManualPin) return false
        val failedSession = session ?: return false
        val inventory = current.trackInventory ?: return false
        if (inventory.audioTracks.isEmpty()) return false
        val plan = audioRoutePlan
        if (plan == null &&
            !AudioRouteFallback.involvesAudioRoute(failure, failedSession.snapshot().audioOutputProgress)
        ) {
            return false
        }

        val failedTrackId = current.trackSelection.audioTrackId
            ?: SelectedTrackResolution.resolvedAudioTrack(inventory, null)?.id
        val chain = plan ?: AudioRouteFallback.plan(
            inventory = inventory,
            failedTrackId = failedTrackId,
            preferences = attemptAudioPreferences ?: AudioLanguagePreferences.DEFAULT,
            systemLanguages = attemptSystemLanguages,
        )

        val attempted = chain.attempted + listOfNotNull(AudioRouteFallback.keyOf(inventory, failedTrackId))
        val updated = chain.copy(attempted = attempted)
        audioRoutePlan = updated
        val next = AudioRouteFallback.nextAlternative(inventory, updated)
        if (next == null) {
            PlayerForensicsLog.event(
                "audio-route-fallback",
                "outcome=exhausted attempted=${attempted.size} planned=${chain.order.size} code=${failure.code}",
            )
            return false
        }
        val watchSid = watchSessionId ?: return false
        val positionMs = current.positionMs
        val candidate = current.candidate
        audioRouteFallbackAttempts += 1
        audioRouteFallbackStartPositionMs = positionMs
        audioRouteFallbackPlaying = false
        pendingAudioRouteKey = next
        PlayerForensicsLog.event(
            "audio-route-fallback",
            "attempt=$audioRouteFallbackAttempts route=${AudioRouteFallback.describe(next)} " +
                "positionMs=$positionMs code=${failure.code} stall=${failure.stall?.name ?: "none"} outcome=restarting",
        )

        recoveryInFlight = true
        scope.launch {
            if (session !== failedSession) {
                recoveryInFlight = false
                return@launch
            }
            val started = runCatching {
                startInternal(candidate, positionMs.takeIf { it > 0L }, sessionId = watchSid)
            }.isSuccess
            recoveryInFlight = false
            if (!started) {

                PlayerForensicsLog.event("audio-route-fallback", "outcome=restart-failed code=${failure.code}")
                applyFailureUi(failure)
            }
        }
        return true
    }

    private suspend fun performRecoveryReplacement(
        replacement: PlayableCandidate,
        positionMs: Long,
        watchSid: SessionId,
        failedSession: PlaybackSession,
    ) {
        PlayerForensicsLog.event("recovery-replace", "positionMs=$positionMs")
        val old = activePlaybackReporter
        if (old != null) {
            val durationMs = _state.value?.durationMs

            withContext(writeDispatcher) {
                old.stop(positionMs, durationMs, failed = false)
            }
        }

        if (session !== failedSession) {
            PlayerForensicsLog.event("recovery-replace-aborted", "holderReleased=true")
            return
        }
        activePlaybackReporter = activeReporterFor(replacement)
        startInternal(replacement, positionMs.takeIf { it > 0L }, sessionId = watchSid)
        PlayerForensicsLog.event("recovery-replace-complete", "phase=${_state.value?.phase?.name ?: "none"}")
    }

    fun failSetup(message: String, candidate: dev.infusion.core.model.PlayableCandidate? = null) {
        val effective = _state.value?.candidate ?: candidate ?: return
        _state.value = PlayerUiState(
            candidate = effective,
            phase = PlaybackState.FAILED,
            positionMs = 0L,
            durationMs = null,
            failure = dev.infusion.core.playback.api.PlaybackFailure(
                category = dev.infusion.core.playback.api.FailureCategory.SOURCE,
                code = "playback.setup_unavailable",
                severity = dev.infusion.core.playback.api.FailureSeverity.PERMANENT,
                recoverability = dev.infusion.core.playback.api.Recoverability.NOT_RETRYABLE,
                message = message,
                timestampMs = now().toEpochMilli(),
            ),
            controlsVisible = true,
            informationalVisible = true,
            watchRecorded = false,
        )
    }

    private fun maybeRecordThresholdCompletion(positionMs: Long, durationMs: Long?) {
        if (attemptThresholdCompleted) return
        val thresholds = attemptCompletionThresholds ?: return
        if (!thresholds.qualifies(isEpisode, positionMs, durationMs)) return
        attemptThresholdCompleted = true
        record(
            WatchEvent.Local(
                eventId = EventId.random(),
                watchKey = watchKey,
                kind = WatchEvent.Kind.COMPLETED,
                occurredAt = now(),
                sessionId = watchSessionId,
                positionMs = positionMs,
                durationMs = durationMs,
                anchor = activeResumeAnchor(positionMs),
            ),
        )
    }

    private fun handleEnded() {
        val current = _state.value ?: return

        if (current.phase == PlaybackState.ENDED) return

        cancelOverlayTimeout()

        endSegmentLookup()
        val event = WatchEvent.Local(
            eventId = EventId.random(),
            watchKey = watchKey,
            kind = WatchEvent.Kind.COMPLETED,
            occurredAt = now(),
            sessionId = watchSessionId,
            positionMs = current.positionMs,
            durationMs = current.durationMs,
            anchor = activeResumeAnchor(current.positionMs),
        )

        terminalStopPositionMs = current.positionMs
        terminalStopDurationMs = current.durationMs
        pendingTerminalEvent = event
        record(event)

        runCatching { onAttemptTerminal() }

        val upNext = if (isEpisode && upNextState != null) {
            upNextState!!.value
                ?: UpNextUi(nextEpisode = null, identityLabel = "UNKNOWN", isEpisode = true)
        } else {
            null
        }
        _state.value = current.copy(
            phase = PlaybackState.ENDED,
            watchRecorded = true,
            isBuffering = false,
            upNext = upNext,
            nextEpisode = null,
        )

        if (isEpisode && upNextState != null && attemptEnableNextEpisode &&
            attemptAutoPlayNext && !nextAutoSuppressed && !attemptNextTransitioned
        ) {
            requestNextTransition()
        }
    }

    private fun handleCancelled() {
        val current = _state.value ?: return
        cancelOverlayTimeout()

        endSegmentLookup()
        val event = WatchEvent.Local(
            eventId = EventId.random(),
            watchKey = watchKey,
            kind = WatchEvent.Kind.CANCELLED,
            occurredAt = now(),
            sessionId = watchSessionId,
            positionMs = current.positionMs,
            durationMs = current.durationMs,
            anchor = activeResumeAnchor(current.positionMs),
        )

        terminalStopPositionMs = current.positionMs
        terminalStopDurationMs = current.durationMs
        pendingTerminalEvent = event
        record(event)

        runCatching { onAttemptTerminal() }

        _state.value = current.copy(
            phase = PlaybackState.CANCELLED,
            watchRecorded = true,
            isBuffering = false,
            upNext = null,
            nextEpisode = null,
        )
    }

    private fun record(event: WatchEvent.Local) {
        scope.launch(writeDispatcher) {
            coordinator.recordLocal(event)
        }
    }

    private fun beginSegmentLookup(candidate: PlayableCandidate) {
        val segmentOwner = segmentEvidence
        val stingerOwner = stingerEvidence
        if (segmentOwner == null && stingerOwner == null) return
        scope.launch(writeDispatcher) {
            val context = try {
                segmentAttemptEvidence(watchKey, candidate)
            } catch (_: Exception) {
                SegmentAttemptEvidence()
            }
            attemptSegmentIdentity = context.identity
            attemptSegmentChapters = context.chapters
            attemptSegmentServerSegments = context.serverSegments

            attemptStingerIdentity = if (isEpisode) StingerLookupIdentity() else context.stingerIdentity
            stingerOwner?.begin(attemptStingerIdentity)
            val current = _state.value ?: return@launch

            attemptSegmentStarted = true
            segmentOwner?.begin(segmentInput(current.durationMs))
        }
    }

    private fun endSegmentLookup() {
        attemptSegmentStarted = false
        attemptEngineChapters = emptyList()
        segmentEvidence?.end()
        stingerEvidence?.end()
        cancelSkipCountdown()
        cancelStingerToast()

        cancelNextCountdown()

        clearPendingSeekTargets()
    }

    private fun segmentInput(durationMs: Long?): SegmentEvidenceInput = SegmentEvidenceInput(

        identity = attemptSegmentIdentity.copy(isMovie = !isEpisode),
        durationMs = durationMs,
        chapters = mergedChapters(),
        settings = attemptSegmentSettings ?: SegmentEvidenceSettings.DEFAULT,
        serverSegments = attemptSegmentServerSegments,
    )

    private fun mergedChapters(): List<ChapterMark> =
        (attemptSegmentChapters + attemptEngineChapters)
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }

    private fun handleEngineChapters(chapters: List<ChapterMark>) {
        if (chapters == attemptEngineChapters) return
        attemptEngineChapters = chapters
        if (!attemptSegmentStarted) return
        val owner = segmentEvidence ?: return
        owner.reconcileWith(segmentInput(_state.value?.durationMs))

        refreshStingerTrigger()
        recomputeSkipIntro()
        recomputeNextEpisode(_state.value?.positionMs ?: 0L)
    }

    private fun refreshSegmentDuration(durationMs: Long?) {
        if (durationMs == null) return
        segmentEvidence?.reconcileWith(segmentInput(durationMs))

        refreshStingerTrigger()
        recomputeSkipIntro()
        recomputeNextEpisode(_state.value?.positionMs ?: 0L)
    }

    fun skipIntro(): Boolean {
        val ui = _state.value?.skipIntro ?: return false
        noteOverlayInteraction()
        cancelSkipCountdown()
        return performSkipTo(ui.targetMs)
    }

    fun dismissSkipIntro() {
        skipIntroFullscreenSuppressed = true
        skipIntroShownAtMs = null
        cancelSkipCountdown()

        _state.value = _state.value?.let { s ->
            val ui = s.skipIntro ?: return@let s
            if (ui.fullscreenDismissed) s else s.copy(skipIntro = ui.copy(fullscreenDismissed = true))
        }
    }

    fun cancelSkipCountdown() {
        val wasCounting = skipCountdownRemaining != null
        skipCountdownJob?.cancel()
        skipCountdownJob = null
        skipCountdownRemaining = null
        skipCountdownTargetMs = null
        skipCountdownTargetKey = null
        if (wasCounting) {
            _state.value = _state.value?.let { s ->
                val ui = s.skipIntro
                if (ui != null && ui.isCountingDown) s.copy(skipIntro = ui.copy(countdownSeconds = null)) else s
            }
        }
    }

    fun dismissStinger() {
        cancelStingerToast()
        _state.value = _state.value?.copy(stinger = null)
    }

    private fun performSkipTo(targetMs: Long): Boolean {
        val current = _state.value ?: return false
        if (!transportSeekable(current)) return false
        if (targetMs <= current.positionMs) return false

        clearPendingSeekTargets()
        _state.value = current.copy(positionMs = targetMs, skipIntro = null)
        session?.seekTo(targetMs)
        val reported = _state.value
        activePlaybackReporter?.let { reporter ->
            if (reported != null) scope.launch(writeDispatcher) {
                reporter.onSeek(targetMs, reported.phase == PlaybackState.PAUSED, currentCanSeek, reported.durationMs)
            }
        }
        return true
    }

    private fun clearSkipUi() {
        skipIntroShownAtMs = null
        if (_state.value?.skipIntro != null) {
            _state.value = _state.value?.copy(skipIntro = null)
        }
    }

    private fun recomputeSkipIntro() {
        val current = _state.value ?: return

        if (!isEpisode || !attemptEnableSkipIntro) {
            clearSkipUi()
            return
        }
        if (current.phase in TERMINAL_PLAYER_PHASES) {
            clearSkipUi()
            return
        }
        val segment = SkipIntroProjection.openingSegment(current.segments)
        val targetMs = segment?.let(SkipIntroProjection::targetMs)
        val persistent = attemptPersistentSkipButton

        if (segment == null || targetMs == null || current.positionMs >= targetMs ||
            !SkipIntroProjection.isEligible(segment, current.positionMs, persistent)
        ) {
            skipIntroFullscreenSuppressed = false
            clearSkipUi()
            return
        }

        val shownAt = skipIntroShownAtMs
        if (shownAt != null && !persistent &&
            now().toEpochMilli() - shownAt > SkipIntroProjection.WALL_CLOCK_CAP_MS
        ) {
            clearSkipUi()
            return
        }
        if (shownAt == null) skipIntroShownAtMs = now().toEpochMilli()
        val ui = SkipIntroUi(
            targetMs = targetMs,
            countdownSeconds = skipCountdownRemaining,
            fullscreenDismissed = skipIntroFullscreenSuppressed,
        )
        if (current.skipIntro != ui) _state.value = current.copy(skipIntro = ui)
        maybeStartAutoSkip(segment, targetMs)
    }

    private fun maybeStartAutoSkip(segment: PlaybackSegment, targetMs: Long) {
        if (!attemptAutoSkipIntro) return
        val key = SkipIntroProjection.targetKey(segment)
        if (autoSkipConsumedKey == key) return
        val delaySeconds = attemptAutoSkipDelaySeconds.coerceIn(0, 10)
        if (delaySeconds == 0) {
            autoSkipConsumedKey = key
            performSkipTo(targetMs)
            return
        }
        if (skipCountdownJob != null) return
        autoSkipConsumedKey = key
        skipCountdownTargetMs = targetMs
        skipCountdownTargetKey = key
        skipCountdownRemaining = delaySeconds
        _state.value = _state.value?.let { s ->
            s.copy(skipIntro = s.skipIntro?.copy(countdownSeconds = delaySeconds))
        }
        skipCountdownJob = scope.launch {
            var remaining = delaySeconds
            while (remaining > 0) {
                delay(1_000L)
                remaining -= 1
                val live = _state.value

                if (live == null || live.skipIntro == null || live.phase in TERMINAL_PLAYER_PHASES) break
                val target = skipCountdownTargetMs ?: break
                if (live.positionMs >= target) break
                skipCountdownRemaining = remaining
                _state.value = live.copy(skipIntro = live.skipIntro?.copy(countdownSeconds = remaining))
            }
            val target = skipCountdownTargetMs
            if (remaining <= 0 && target != null && autoSkipConsumedKey == skipCountdownTargetKey) {
                performSkipTo(target)
            }
            skipCountdownJob = null
            skipCountdownRemaining = null
            skipCountdownTargetMs = null
            skipCountdownTargetKey = null
        }
    }

    private fun refreshStingerTrigger() {
        val verdict = attemptStingerVerdict
        attemptStingerTriggerMs = if (!isEpisode && verdict?.isPositive == true) {
            StingerReconciliation.stingerTriggerMs(
                durationMs = _state.value?.durationMs,
                chapters = mergedChapters(),
                percent = attemptSegmentSettings?.stingerTriggerPercent
                    ?: SegmentEvidencePreferences.DEFAULT.stingerTriggerPercent,
            )
        } else {
            null
        }
    }

    private fun recomputeStingerNotice(positionMs: Long) {
        if (isEpisode) return
        val settings = attemptSegmentSettings ?: return
        if (!settings.enableStingerAlerts) return
        val verdict = attemptStingerVerdict ?: return
        if (!verdict.isPositive) return
        val current = _state.value ?: return
        if (current.phase in TERMINAL_PLAYER_PHASES) return
        val toastMs = settings.stingerToastDurationSeconds.coerceIn(1, 30) * 1_000L
        val earlySeconds = settings.stingerEarlyNotifySeconds
        if (!stingerEarlyShown && earlySeconds > 0 && positionMs >= earlySeconds * 1_000L) {
            stingerEarlyShown = true
            showStinger(StingerUi.early(), toastMs)
            return
        }
        val trigger = attemptStingerTriggerMs
        if (!stingerCreditsShown && trigger != null && positionMs >= trigger) {
            stingerCreditsShown = true
            showStinger(StingerUi.credits(), toastMs)
        }
    }

    private fun showStinger(ui: StingerUi, durationMs: Long) {
        cancelStingerToast()
        _state.value = _state.value?.copy(stinger = ui)
        stingerToastJob = scope.launch {
            delay(durationMs)
            _state.value = _state.value?.copy(stinger = null)
            stingerToastJob = null
        }
    }

    private fun cancelStingerToast() {
        stingerToastJob?.cancel()
        stingerToastJob = null
    }

    private fun recomputeNextEpisode(positionMs: Long) {

        val upNextFlow = upNextState ?: return
        val current = _state.value ?: return
        if (!isEpisode) return
        if (current.phase !in ACTIVE_PLAYBACK_STATES) {
            if (current.nextEpisode != null) _state.value = current.copy(nextEpisode = null)
            return
        }
        if (attemptNextTransitioned) {
            if (current.nextEpisode != null) _state.value = current.copy(nextEpisode = null)
            return
        }
        if (!attemptEnableNextEpisode) {
            if (current.nextEpisode != null) _state.value = current.copy(nextEpisode = null)
            return
        }
        val trigger = NextEpisodeTrigger.triggerMs(
            priorityOnline = attemptOnlineNextPriority,
            credits = current.segments?.ofType(SegmentType.CREDITS).orEmpty(),
            chapters = mergedChapters(),
            durationMs = current.durationMs,
            fallbackPercent = attemptFallbackTriggerPercent,
        )
        val reached = trigger != null && positionMs >= trigger
        if (!reached) {

            nextPromptActive = false
            if (current.nextEpisode != null) _state.value = current.copy(nextEpisode = null)
            return
        }

        val derived = upNextFlow.value
        val identity = derived?.nextEpisode
        val actionable = identity != null && derived.availability == UpNextAvailability.AVAILABLE
        if (!actionable) {
            nextPromptActive = false
            if (current.nextEpisode != null) _state.value = current.copy(nextEpisode = null)
            return
        }
        if (!nextPromptActive) {
            nextPromptActive = true

            nextFullscreenSuppressed = false
        }
        val autoArmed = attemptAutoPlayNext && !nextAutoSuppressed
        if (autoArmed && !nextCountdownConsumed && !attemptNextTransitioned) {
            nextCountdownConsumed = true
            if (attemptAutoPlayDelaySeconds <= 0) {

                requestNextTransition()
                return
            }
            startNextCountdown()
        }
        publishNextEpisodeUi()
    }

    private fun publishNextEpisodeUi() {
        val current = _state.value ?: return
        val derived = upNextState?.value
        val identity = derived?.nextEpisode
        val ui = if (nextPromptActive && identity != null &&
            derived.availability == UpNextAvailability.AVAILABLE && !attemptNextTransitioned
        ) {
            NextEpisodeUi(
                identityLabel = derived.identityLabel,
                countdownSeconds = nextCountdownRemaining,
                isAutomatic = attemptAutoPlayNext && !nextAutoSuppressed,
                suppressed = nextAutoSuppressed,
                fullscreenDismissed = nextFullscreenSuppressed,
            )
        } else {
            null
        }
        if (current.nextEpisode != ui) _state.value = current.copy(nextEpisode = ui)
    }

    private fun startNextCountdown() {
        val seconds = attemptAutoPlayDelaySeconds.coerceIn(1, NextEpisodeSettings.AUTO_PLAY_DELAY_MAX)
        nextCountdownRemaining = seconds
        publishNextEpisodeUi()
        nextCountdownJob?.cancel()
        nextCountdownJob = scope.launch {
            while (true) {
                delay(1_000L)

                if (nextAutoSuppressed || attemptNextTransitioned) {
                    nextCountdownRemaining = null
                    publishNextEpisodeUi()
                    return@launch
                }
                val live = _state.value ?: return@launch
                if (live.phase !in ACTIVE_PLAYBACK_STATES) {
                    nextCountdownRemaining = null
                    publishNextEpisodeUi()
                    return@launch
                }
                val remaining = (nextCountdownRemaining ?: return@launch) - 1
                if (remaining <= 0) {
                    nextCountdownRemaining = 0
                    publishNextEpisodeUi()
                    break
                }
                nextCountdownRemaining = remaining
                publishNextEpisodeUi()
            }
            if (!nextAutoSuppressed && !attemptNextTransitioned) requestNextTransition()
        }
    }

    private fun cancelNextCountdown() {
        nextCountdownJob?.cancel()
        nextCountdownJob = null
        nextCountdownRemaining = null
    }

    private fun requestNextTransition() {
        if (attemptNextTransitioned) return
        val derived = upNextState?.value ?: return
        val identity = derived.nextEpisode ?: return
        if (derived.availability != UpNextAvailability.AVAILABLE) return
        attemptNextTransitioned = true
        cancelNextCountdown()
        val id = ++nextRequestCounter
        pendingNextRequestId = id
        _nextTransition.value = NextTransitionRequest(requestId = id, nextEpisode = identity)
        publishNextEpisodeUi()
    }

    fun requestNextEpisode() {
        if (attemptNextTransitioned) return
        if (!nextPromptActive) return
        noteOverlayInteraction()
        cancelNextCountdown()
        requestNextTransition()
    }

    fun cancelNextEpisodeAutomatic() {
        if (nextCountdownRemaining == null) return
        nextAutoSuppressed = true
        cancelNextCountdown()
        publishNextEpisodeUi()
    }

    fun dismissNextEpisode() {
        nextAutoSuppressed = true
        nextFullscreenSuppressed = true
        cancelNextCountdown()
        publishNextEpisodeUi()
    }

    val isNextEpisodeVisible: Boolean get() = _state.value?.nextEpisode != null

    val derivedUpNext: UpNextUi? get() = upNextState?.value

    fun acknowledgeNextTransition(requestId: Long) {
        if (pendingNextRequestId == requestId) _nextTransition.value = null
    }

    private fun activeResumeAnchor(positionMs: Long): ResumeAnchor? =
        attemptAnchor?.copy(positionMs = positionMs, anchoredAt = now())

    fun play() {
        if (!transportCommandable(_state.value)) return
        session?.play()
    }

    fun playPause() {

        noteOverlayInteraction()
        if (_state.value?.phase == PlaybackState.PLAYING) pause() else play()
    }

    fun pause() {
        val current = _state.value ?: return
        if (!transportCommandable(current)) return
        session?.pause()
        record(
            WatchEvent.Local(
                eventId = EventId.random(),
                watchKey = watchKey,
                kind = WatchEvent.Kind.PAUSE,
                occurredAt = now(),
                sessionId = watchSessionId,
                positionMs = current.positionMs,
                durationMs = current.durationMs,
                anchor = activeResumeAnchor(current.positionMs),
            ),
        )
    }

    fun seekTo(positionMs: Long) {
        require(positionMs >= 0) { "seek position must be >= 0 (was $positionMs)" }
        if (!transportSeekable(_state.value)) return

        clearPendingSeekTargets()
        session?.seekTo(positionMs)
        val current = _state.value
        activePlaybackReporter?.let { reporter ->
            if (current != null) scope.launch(writeDispatcher) {
                reporter.onSeek(positionMs, current.phase == PlaybackState.PAUSED, currentCanSeek, current.durationMs)
            }
        }
    }

    fun seekBy(deltaMs: Long = seekStepMs): RelativeSeekOutcome? {

        noteOverlayInteraction()

        clearPendingNavigation()
        val current = _state.value ?: return null

        if (!transportSeekable(current)) return null

        if (!currentCanSeek) return null
        val duration = current.durationMs

        val base = pendingRelativeSeekPositionMs ?: current.positionMs
        val target = (base + deltaMs).coerceAtLeast(0L)
        val clamped = if (duration != null) target.coerceAtMost(duration) else target
        val commanded = clamped - base
        _state.value = current.copy(positionMs = clamped)
        pendingRelativeSeekPositionMs = clamped
        pendingRelativeSeekForward = deltaMs > 0
        session?.seekTo(clamped)
        return RelativeSeekOutcome(
            requestedStepMs = if (deltaMs < 0) -deltaMs else deltaMs,
            commandedMs = commanded,
        )
    }

    fun seekBackward(): RelativeSeekOutcome? = seekBy(-relativeSeekStepMs())

    fun seekForward(): RelativeSeekOutcome? = seekBy(relativeSeekStepMs())

    val relativeSeekStep: Long get() = relativeSeekStepMs()

    fun navigateChapter(forward: Boolean): ChapterNavigation.Destination? {
        val current = _state.value ?: return null
        if (!transportSeekable(current)) return null

        if (current.isLive) return null
        val base = pendingNavigationPositionMs ?: current.positionMs
        val destination = if (forward) {
            ChapterNavigation.next(current.segments, base, current.durationMs)
        } else {
            ChapterNavigation.previous(current.segments, base, current.durationMs)
        } ?: return null
        pendingNavigationPositionMs = destination.positionMs
        pendingNavigationForward = forward
        _state.value = current.copy(positionMs = destination.positionMs)
        session?.seekTo(destination.positionMs)
        activePlaybackReporter?.let { reporter ->
            scope.launch(writeDispatcher) {
                reporter.onSeek(
                    destination.positionMs,
                    current.phase == PlaybackState.PAUSED,
                    currentCanSeek,
                    current.durationMs,
                )
            }
        }
        return destination
    }

    private fun clearPendingNavigation() {
        pendingNavigationPositionMs = null
        pendingNavigationForward = false
    }

    private fun clearPendingRelativeSeek() {
        pendingRelativeSeekPositionMs = null
        pendingRelativeSeekForward = false
    }

    private fun clearPendingSeekTargets() {
        clearPendingNavigation()
        clearPendingRelativeSeek()
    }

    private fun selectionPossible(current: PlayerUiState): Boolean =
        current.failure == null && current.phase in ACTIVE_PLAYBACK_STATES

    fun selectAudioTrack(trackId: TrackId): TrackSelectionOutcome {
        noteOverlayInteraction()
        val current = _state.value ?: return TrackSelectionOutcome.Rejected("Playback has not started.")
        val inventory = current.trackInventory
        val track = inventory?.audioTracks?.firstOrNull { it.id == trackId }
        if (track == null) {
            _state.value = current.copy(lastTrackSelectionOutcome = TrackSelectionOutcome.Rejected("Audio track not available."))
            return TrackSelectionOutcome.Rejected("Audio track not available.")
        }
        if (!selectionPossible(current)) {
            return TrackSelectionOutcome.Rejected("Track selection is not available in the current state.")
        }
        audioManualPin = true
        pendingAudioTrackId = null
        val selection = applySelectionToEngine(current.trackSelection.copy(audioTrackId = trackId))
        _state.value = _state.value?.copy(
            trackSelection = selection,
            lastTrackSelectionOutcome = TrackSelectionOutcome.Applied,
        )
        return TrackSelectionOutcome.Applied
    }

    fun selectSubtitleTrack(trackId: TrackId): TrackSelectionOutcome {
        noteOverlayInteraction()
        val current = _state.value ?: return TrackSelectionOutcome.Rejected("Playback has not started.")
        val inventory = current.trackInventory
        val track = inventory?.subtitleTracks?.firstOrNull { it.id == trackId }
        if (track == null) {
            _state.value = current.copy(lastTrackSelectionOutcome = TrackSelectionOutcome.Rejected("Subtitle track not available."))
            return TrackSelectionOutcome.Rejected("Subtitle track not available.")
        }
        if (!selectionPossible(current)) {
            return TrackSelectionOutcome.Rejected("Track selection is not available in the current state.")
        }
        subtitleManualPin = true
        pendingTextApplication = null
        val selection = applySelectionToEngine(
            current.trackSelection.copy(
                subtitleTrackId = trackId,
                subtitleMode = SubtitleMode.SELECTED,
            ),
        )
        _state.value = _state.value?.copy(
            trackSelection = selection,
            subtitleNoEligible = false,
            lastTrackSelectionOutcome = TrackSelectionOutcome.Applied,
        )
        return TrackSelectionOutcome.Applied
    }

    fun setSubtitleMode(mode: SubtitleMode): TrackSelectionOutcome {
        require(mode != SubtitleMode.SELECTED) { "use selectSubtitleTrack for SELECTED" }
        noteOverlayInteraction()
        val current = _state.value ?: return TrackSelectionOutcome.Rejected("Playback has not started.")
        if (!selectionPossible(current)) {
            return TrackSelectionOutcome.Rejected("Track selection is not available in the current state.")
        }
        subtitleManualPin = true
        pendingTextApplication = null
        val selection = applySelectionToEngine(
            current.trackSelection.copy(
                subtitleMode = mode,
                subtitleTrackId = if (mode == SubtitleMode.OFF) null else current.trackSelection.subtitleTrackId,
            ),
        )
        _state.value = _state.value?.copy(
            trackSelection = selection,
            subtitleNoEligible = false,
            lastTrackSelectionOutcome = TrackSelectionOutcome.Applied,
        )
        return TrackSelectionOutcome.Applied
    }

    fun stop() {
        if (!canIssueUserStop()) return
        session?.stop(StopReason.USER_EXIT)
    }

    private fun transportCommandable(current: PlayerUiState?): Boolean {
        if (recoveryInFlight) return false
        val phase = current?.phase ?: return false
        return phase != PlaybackState.IDLE && phase !in TERMINAL_PLAYER_PHASES
    }

    private fun transportSeekable(current: PlayerUiState?): Boolean =
        transportCommandable(current) && current?.phase != PlaybackState.ENDED

    private fun canIssueUserStop(): Boolean {
        if (recoveryInFlight) return false
        val phase = _state.value?.phase ?: return true
        return phase !in TERMINAL_PLAYER_PHASES
    }

    fun stopAndRelease() {
        val phase = _state.value?.phase
        PlayerForensicsLog.event("destination-exit", "sessionOwned=${session != null} phase=${phase?.name ?: "none"}")

        if (session != null && !recoveryInFlight && phase !in setOf(
                PlaybackState.CANCELLED,
                PlaybackState.ENDED,
                PlaybackState.FAILED,
                PlaybackState.RELEASED,
            )) {
            stop()
        }
        release()
    }

    fun toggleControls() {
        if (isOverlayVisible()) hideControls() else showControls()
    }

    fun showControls() {
        controlsVisible = true
        informationalVisible = true
        _state.value = _state.value?.copy(controlsVisible = true, informationalVisible = true)
        restartOverlayTimeout()

        recomputeSkipIntro()

        recomputeNextEpisode(_state.value?.positionMs ?: 0L)
    }

    fun hideControls() {
        controlsVisible = false
        informationalVisible = false
        cancelOverlayTimeout()
        _state.value = _state.value?.copy(controlsVisible = false, informationalVisible = false)
    }

    fun noteOverlayInteraction() {
        if (!isOverlayVisible()) return
        restartOverlayTimeout()
    }

    fun setSubordinateSurface(surface: PlayerSubordinateSurface?) {
        val current = _state.value ?: return
        if (current.subordinateSurface == surface) return
        _state.value = current.copy(subordinateSurface = surface)
        if (surface == null) restartOverlayTimeout() else cancelOverlayTimeout()
    }

    private fun isOverlayVisible(): Boolean =
        _state.value?.let { it.controlsVisible || it.informationalVisible } == true

    private fun restartOverlayTimeout() {
        cancelOverlayTimeout()
        if (!isOverlayVisible()) return
        if (_state.value?.subordinateSurface != null) return
        val timeoutMs = effectiveOverlayTimeoutMs() ?: return
        overlayTimeoutJob = scope.launch {
            delay(timeoutMs)
            applyOverlayTimeout()
        }
    }

    private fun effectiveOverlayTimeoutMs(): Long? {
        val captured = attemptControlsAutoHide ?: return overlayTimeoutMs
        return captured.timeoutMs
    }

    private fun cancelOverlayTimeout() {
        overlayTimeoutJob?.cancel()
        overlayTimeoutJob = null
    }

    private fun publishInitialTrackSetupSettled(settled: Boolean) {
        if (!settled) return
        val current = _state.value ?: return
        if (current.initialTrackSetupSettled) return
        _state.value = current.copy(initialTrackSetupSettled = true)

        PlayerForensicsLog.event("startup-settle", "kind=track phase=${current.phase}")
        maybeCancelSettleCeilings()
    }

    fun noteInitialDisplayMatchSettled(settled: Boolean) {
        if (!settled) return
        val current = _state.value ?: return
        if (current.initialDisplayMatchSettled) return
        _state.value = current.copy(initialDisplayMatchSettled = true)

        PlayerForensicsLog.event("startup-settle", "kind=display phase=${current.phase}")
        maybeCancelSettleCeilings()
    }

    private fun maybeCancelSettleCeilings() {
        val current = _state.value ?: return
        if (current.initialTrackSetupSettled) cancelTrackSetupSettleCeiling()
        if (current.initialDisplayMatchSettled) cancelDisplayMatchSettleCeiling()
    }

    private fun maybeSettleInitialTrackSetup() {
        val current = _state.value ?: return
        if (current.initialTrackSetupSettled) return
        if (initialTrackSetupIsSettled(current)) publishInitialTrackSetupSettled(true)
    }

    private fun initialTrackSetupIsSettled(current: PlayerUiState): Boolean {
        val inventory = current.trackInventory
        val audioSettled = attemptAudioPreferences == null || audioManualPin ||
            (inventory != null && inventory.audioTracks.isEmpty()) ||
            (audioDecision != null && pendingAudioTrackId == null)
        val subtitleSettled = attemptSubtitlePreferences == null || subtitleManualPin ||
            (inventory != null && inventory.subtitleTracks.isEmpty()) ||
            (appliedSubtitleApplication != null && pendingTextApplication == null)
        return audioSettled && subtitleSettled
    }

    private fun armTrackSetupSettleCeiling() {
        cancelTrackSetupSettleCeiling()
        trackSetupCeilingJob = scope.launch {
            delay(startupSettleCeilingMs)
            publishInitialTrackSetupSettled(true)
        }
    }

    private fun cancelTrackSetupSettleCeiling() {
        trackSetupCeilingJob?.cancel()
        trackSetupCeilingJob = null
    }

    private fun armDisplayMatchSettleCeiling() {
        if (displayMatchCeilingJob != null) return
        if (_state.value?.initialDisplayMatchSettled == true) return
        displayMatchCeilingJob = scope.launch {
            delay(startupDisplayMatchCeilingMs)
            noteInitialDisplayMatchSettled(true)
        }
    }

    private fun cancelDisplayMatchSettleCeiling() {
        displayMatchCeilingJob?.cancel()
        displayMatchCeilingJob = null
    }

    private fun applyOverlayTimeout() {
        val current = _state.value ?: return
        controlsVisible = false
        informationalVisible = current.phase == PlaybackState.PAUSED
        _state.value = current.copy(
            controlsVisible = false,
            informationalVisible = informationalVisible,
        )
    }

    fun dismissControlsOnBack(): Boolean {
        if (!isOverlayVisible()) return false
        hideControls()
        return true
    }

    private var terminalStopDispatched = false

    private var terminalStopPositionMs: Long? = null
    private var terminalStopDurationMs: Long? = null

    fun release() {

        cancelOverlayTimeout()

        cancelTrackSetupSettleCeiling()
        cancelDisplayMatchSettleCeiling()
        upNextCollector?.cancel()
        upNextCollector = null

        segmentCollector?.cancel()
        segmentCollector = null

        stingerCollector?.cancel()
        stingerCollector = null
        cancelSkipCountdown()
        cancelStingerToast()

        cancelNextCountdown()
        endSegmentLookup()
        val finalState = _state.value
        PlayerForensicsLog.event(
            "release-start",
            "sessionOwned=${session != null} attempt=${watchSessionId != null} positionMs=${finalState?.positionMs ?: -1L} " +
                "paused=${finalState?.phase == PlaybackState.PAUSED} terminalEvent=${pendingTerminalEvent != null}",
        )
        activePlaybackReporter?.let { reporter ->

            if (finalState != null && !terminalStopDispatched) {
                terminalStopDispatched = true

                val terminalPositionMs = terminalStopPositionMs ?: finalState.positionMs
                val terminalDurationMs = terminalStopDurationMs ?: finalState.durationMs
                val terminalFailed = finalState.phase == PlaybackState.FAILED
                PlayerForensicsLog.event(
                    "reporter-stop-dispatch",
                    "scope=terminal positionMs=$terminalPositionMs failed=$terminalFailed " +
                        "phase=${finalState.phase.name} captured=${terminalStopPositionMs != null} " +
                        "lastRecordedPositionMs=$lastRecordedPositionMs",
                )
                terminalStopScope.launch {
                    PlayerForensicsLog.event("reporter-stop-callback", "positionMs=$terminalPositionMs failed=$terminalFailed")
                    reporter.stop(terminalPositionMs, terminalDurationMs, terminalFailed)
                }
            }
        }
        session?.release()
        val sid = watchSessionId
        val terminal = pendingTerminalEvent
        pendingTerminalEvent = null
        if (sid != null) {
            if (terminal != null) {

                scope.launch(writeDispatcher) {
                    coordinator.recordLocal(terminal)
                    sourceSessionUnbinder(sid)
                }
            } else {

                scope.launch(writeDispatcher) {
                    sourceSessionUnbinder(sid)
                }
            }
            watchSessionId = null
        }
        session = null
        attemptAnchor = null

        runCatching { onAttemptReleased() }
        PlayerForensicsLog.event("release-complete", "sessionOwned=false attemptRetired=true")
    }

    fun dismissUpNext(): Boolean {
        val current = _state.value ?: return false
        if (current.upNext == null) return false
        _state.value = current.copy(upNext = null)
        return true
    }

    val isUpNextVisible: Boolean get() = _state.value?.upNext != null

    private companion object {
        val ACTIVE_PLAYBACK_STATES = setOf(
            PlaybackState.READY,
            PlaybackState.PLAYING,
            PlaybackState.PAUSED,
            PlaybackState.BUFFERING,
        )

        val TERMINAL_PLAYER_PHASES = setOf(
            PlaybackState.CANCELLED,
            PlaybackState.FAILED,
            PlaybackState.RELEASED,
        )
    }
}

private sealed interface SubtitleTextApplication {

    data class Select(val trackId: TrackId) : SubtitleTextApplication

    data object Off : SubtitleTextApplication

    data object EngineDefault : SubtitleTextApplication
}
