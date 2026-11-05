package dev.infusion.core.playback.api.testing

import com.google.common.truth.Truth.assertThat
import dev.infusion.core.playback.api.AttemptReason
import dev.infusion.core.playback.api.CancellationReason
import dev.infusion.core.playback.api.CapabilityState
import dev.infusion.core.playback.api.DeviceBuild
import dev.infusion.core.playback.api.DeviceRouteSnapshot
import dev.infusion.core.playback.api.DiagnosticsSnapshot
import dev.infusion.core.playback.api.EndReason
import dev.infusion.core.playback.api.FailureCategory
import dev.infusion.core.playback.api.FailureSeverity
import dev.infusion.core.playback.api.PlaybackEngine
import dev.infusion.core.playback.api.PlaybackEvent
import dev.infusion.core.playback.api.PlaybackFailure
import dev.infusion.core.playback.api.PlaybackSession
import dev.infusion.core.playback.api.PlaybackState
import dev.infusion.core.playback.api.Recoverability
import dev.infusion.core.playback.api.SessionId
import dev.infusion.core.playback.api.SessionLineage
import dev.infusion.core.playback.api.SessionPhase
import dev.infusion.core.playback.api.StopReason
import dev.infusion.core.playback.api.SubtitleMode
import dev.infusion.core.playback.api.TrackId
import dev.infusion.core.playback.api.TrackSelection
import dev.infusion.core.playback.api.TransportRequest
import dev.infusion.core.playback.api.events
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test

abstract class PlaybackContractTest {

    protected open val engineFactory: () -> PlaybackEngine = { FakePlaybackEngine() }

    protected fun newEngine(): PlaybackEngine = engineFactory()

    protected fun firstLineage(
        sessionId: String = "sess-1",
        requestId: String = "req-1",
        reason: AttemptReason = AttemptReason.USER_INITIATED,
        nowEpochMs: Long = 1_700_000_000_000L,
    ): SessionLineage = SessionLineage.first(SessionId(sessionId), reason, requestId, nowEpochMs)

    protected fun routeSnapshot(): DeviceRouteSnapshot = DeviceRouteSnapshot(
        device = DeviceBuild(sdkInt = 33, manufacturer = "fake", model = "fake-tv"),
        capturedAtEpochMs = 1_700_000_000_000L,
    )

    protected fun request(
        requestId: String = "req-1",
        uri: String = "https://media.example.test/item.mp4",
    ): TransportRequest = TransportRequest(requestId = requestId, uri = uri, mimeType = "video/mp4")

    protected fun sampleFailure(
        category: FailureCategory = FailureCategory.SOURCE,
        code: String = "source.file_not_found",
        recoverability: Recoverability = Recoverability.NOT_RETRYABLE,
    ): PlaybackFailure = PlaybackFailure(
        category = category,
        code = code,
        severity = FailureSeverity.PERMANENT,
        recoverability = recoverability,
        message = "redacted failure message",
        engineDetail = null,
        timestampMs = 1_700_000_000_000L,
    )

    @Test
    fun createSession_echoesLineage_andStartsIdle() {
        val engine = newEngine()
        val listener = RecordingPlaybackListener()
        val lineage = firstLineage()

        val session = engine.createSession(lineage, routeSnapshot())
        session.addListener(listener)

        assertThat(session.sessionId).isEqualTo(lineage.sessionId)
        assertThat(session.lineage).isEqualTo(lineage)
        assertThat(session.snapshot().state).isEqualTo(PlaybackState.IDLE)

        assertThat(listener.events).isEmpty()
    }

    @Test
    fun prepare_startsWithPreparingPhase() {
        val engine = newEngine()
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())

        val phases = listener.events.filterIsInstance<PlaybackEvent.SessionPhaseChanged>().map { it.phase }
        assertThat(phases.first()).isEqualTo(SessionPhase.PREPARING)
        assertThat(phases.last()).isEqualTo(SessionPhase.PREPARED)
    }

    @Test
    fun prepare_producesDeterministicEventSequence() {
        val engine = newEngine()
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())

        assertThat(listener.states()).containsExactly(
            PlaybackState.PREPARING,
            PlaybackState.BUFFERING,
            PlaybackState.READY,
        ).inOrder()
        assertThat(listener.timelineChanged()).hasSize(1)
        assertThat(listener.tracksChanged()).hasSize(1)
        assertThat(listener.timelineChanged().single().timeline.durationMs).isEqualTo(60_000L)
        val snapshot = session.snapshot()
        assertThat(snapshot.state).isEqualTo(PlaybackState.READY)
        assertThat(snapshot.timeline?.durationMs).isEqualTo(60_000L)
        assertThat(snapshot.trackInventory?.videoTracks).isNotEmpty()
    }

    @Test
    fun play_pause_seek_sequence_isLegal() {
        val engine = newEngine()
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())
        session.play()
        assertThat(listener.states().last()).isEqualTo(PlaybackState.PLAYING)

        session.pause()
        assertThat(listener.states().last()).isEqualTo(PlaybackState.PAUSED)

        session.seekTo(10_000L)
        val positions = listener.events.filterIsInstance<PlaybackEvent.PositionChanged>()
        assertThat(positions.last().positionMs).isEqualTo(10_000L)

        assertThat(listener.states().last()).isEqualTo(PlaybackState.PAUSED)
        assertThat(session.snapshot().positionMs).isEqualTo(10_000L)
    }

    @Test
    fun seek_clampsToDuration() {
        val engine = newEngine()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(RecordingPlaybackListener())

        session.prepare(request())
        session.seekTo(120_000L)

        assertThat(session.snapshot().positionMs).isEqualTo(60_000L)
    }

    @Test
    fun negativeSeek_throwsIllegalArgument() {
        val engine = newEngine()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.prepare(request())

        assertThrows(IllegalArgumentException::class.java) { session.seekTo(-1L) }
    }

    @Test
    fun play_beforePrepare_throwsIllegalState() {
        val engine = newEngine()
        val session = engine.createSession(firstLineage(), routeSnapshot())

        assertThrows(IllegalStateException::class.java) { session.play() }
    }

    @Test
    fun seek_beforePrepare_throwsIllegalState() {
        val engine = newEngine()
        val session = engine.createSession(firstLineage(), routeSnapshot())

        assertThrows(IllegalStateException::class.java) { session.seekTo(1_000L) }
    }

    @Test
    fun selectTrack_beforePrepare_throwsIllegalState() {
        val engine = newEngine()
        val session = engine.createSession(firstLineage(), routeSnapshot())

        assertThrows(IllegalStateException::class.java) {
            session.selectTrack(TrackSelection(audioTrackId = TrackId("audio:0")))
        }
    }

    @Test
    fun prepare_rejectsRequestFromDifferentLineage() {
        val engine = newEngine()
        val session = engine.createSession(firstLineage(requestId = "req-lineage"), routeSnapshot())

        assertThrows(IllegalArgumentException::class.java) {
            session.prepare(request(requestId = "req-other"))
        }
        assertThat(session.snapshot().state).isEqualTo(PlaybackState.IDLE)
    }

    @Test
    fun repeatedPrepare_rejectsRequestFromDifferentLineage() {
        val engine = FakePlaybackEngine()
        engine.defaultScript = FakeScript(deferPrepare = true)
        val session = engine.createSession(firstLineage(requestId = "req-lineage"), routeSnapshot())
        session.prepare(request(requestId = "req-lineage"))

        assertThrows(IllegalArgumentException::class.java) {
            session.prepare(request(requestId = "req-other"))
        }
        assertThat(session.snapshot().state).isEqualTo(PlaybackState.PREPARING)
    }

    @Test
    fun stop_emitsCancelled_notFailed() {
        val engine = newEngine()
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())
        session.play()
        session.stop(StopReason.USER_EXIT)

        assertThat(listener.cancellations()).containsExactly(CancellationReason.USER)
        assertThat(listener.failures()).isEmpty()
        assertThat(listener.states().last()).isEqualTo(PlaybackState.CANCELLED)
        assertThat(session.snapshot().state).isEqualTo(PlaybackState.CANCELLED)
        assertThat(session.snapshot().lastFailure).isNull()
        assertThat(session.snapshot().lastCancellationReason).isEqualTo(CancellationReason.USER)

        assertThrows(IllegalStateException::class.java) { session.play() }
    }

    @Test
    fun stopDuringPrepare_isCancellation_notFailure() {
        val engine = FakePlaybackEngine()
        engine.defaultScript = FakeScript(deferPrepare = true)
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot()) as FakePlaybackSession
        session.addListener(listener)

        session.prepare(request())
        assertThat(listener.states().last()).isEqualTo(PlaybackState.PREPARING)
        session.stop(StopReason.SYSTEM)

        assertThat(listener.failures()).isEmpty()
        assertThat(listener.cancellations()).containsExactly(CancellationReason.SYSTEM)
        assertThat(listener.states().last()).isEqualTo(PlaybackState.CANCELLED)
        assertThat(listener.states()).doesNotContain(PlaybackState.READY)
        assertThat(session.snapshot().lastFailure).isNull()
    }

    @Test
    fun deferredPrepare_completesDeterministically() {
        val engine = FakePlaybackEngine()
        engine.defaultScript = FakeScript(deferPrepare = true)
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot()) as FakePlaybackSession
        session.addListener(listener)

        session.prepare(request())
        assertThat(listener.states().last()).isEqualTo(PlaybackState.PREPARING)
        session.completePrepare()

        assertThat(listener.states()).containsExactly(
            PlaybackState.PREPARING,
            PlaybackState.BUFFERING,
            PlaybackState.READY,
        ).inOrder()
    }

    @Test
    fun failure_isClassified_andTerminal() {
        val engine = FakePlaybackEngine()
        val failure = sampleFailure()
        engine.defaultScript = FakeScript(failOnPrepare = failure)
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())

        assertThat(listener.failures()).containsExactly(failure)
        assertThat(listener.states().last()).isEqualTo(PlaybackState.FAILED)
        assertThat(session.snapshot().lastFailure).isEqualTo(failure)
        assertThat(session.snapshot().state).isEqualTo(PlaybackState.FAILED)
        assertThat(session.snapshot().lastCancellationReason).isNull()

        assertThrows(IllegalStateException::class.java) { session.play() }
        session.release()
        assertThat(listener.states().last()).isEqualTo(PlaybackState.RELEASED)
    }

    @Test
    fun failureOnPlay_isClassified() {
        val engine = FakePlaybackEngine()
        val failure = sampleFailure(category = FailureCategory.DECODE, code = "decode.init_failed")
        engine.defaultScript = FakeScript(failOnPlay = failure)
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())
        session.play()

        assertThat(listener.failures()).containsExactly(failure)
        assertThat(session.snapshot().state).isEqualTo(PlaybackState.FAILED)
    }

    @Test
    fun retry_createsNewSession_sharingLineage() {
        val engine = newEngine()
        val first = engine.createSession(firstLineage(), routeSnapshot())
        first.prepare(request())
        first.stop(StopReason.USER_EXIT)
        first.release()

        val retryLineage = first.lineage.nextAttempt(
            newSessionId = SessionId("sess-2"),
            nextReason = AttemptReason.TRANSIENT_RETRY,
            newRequestId = "req-2-refreshed",
            nowEpochMs = 1_700_000_100_000L,
        )
        val retry = engine.createSession(retryLineage, routeSnapshot())

        assertThat(retry.lineage.rootSessionId).isEqualTo(first.lineage.rootSessionId)
        assertThat(retry.lineage.parentSessionId).isEqualTo(first.sessionId)
        assertThat(retry.lineage.attemptSequence).isEqualTo(2)
        assertThat(retry.lineage.requestId).isEqualTo("req-2-refreshed")
        assertThat(retry.lineage.reason).isEqualTo(AttemptReason.TRANSIENT_RETRY)
    }

    @Test
    fun lineageNextAttempt_isDeterministic() {
        val base = firstLineage()
        val a = base.nextAttempt(SessionId("x"), AttemptReason.FALLBACK, "req-fallback", 1_700_000_200_000L)
        val b = base.nextAttempt(SessionId("x"), AttemptReason.FALLBACK, "req-fallback", 1_700_000_200_000L)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun trackSelection_applies_andValidates() {
        val engine = newEngine()
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())
        val selection = TrackSelection(
            audioTrackId = TrackId("audio:1"),
            subtitleMode = SubtitleMode.OFF,
        )
        session.selectTrack(selection)

        val applied = listener.events.filterIsInstance<PlaybackEvent.TrackSelectionChanged>().last()
        assertThat(applied.selection).isEqualTo(selection)
        assertThat(session.snapshot().trackInventory?.audioTracks?.map { it.id.value })
            .containsExactly("audio:0", "audio:1")
    }

    @Test
    fun unknownTrackId_isRejected() {
        val engine = newEngine()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.prepare(request())

        assertThrows(IllegalArgumentException::class.java) {
            session.selectTrack(TrackSelection(audioTrackId = TrackId("audio:does-not-exist")))
        }
    }

    @Test
    fun subtitleSelected_requiresTrackId() {
        val engine = newEngine()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.prepare(request())

        assertThrows(IllegalArgumentException::class.java) {
            session.selectTrack(TrackSelection(subtitleMode = SubtitleMode.SELECTED))
        }
    }

    @Test
    fun capability_isUnknownByDefault_andNeverFabricatedSupported() {
        val engine = newEngine()
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())

        val observations = listener.capabilityObservations().map { it.observation }

        assertThat(observations.map { it.state }).doesNotContain(CapabilityState.SUPPORTED)

        if (observations.isNotEmpty()) {
            assertThat(session.snapshot().capabilityObservations).containsExactlyElementsIn(observations)
        }
    }

    @Test
    fun degradedObservation_isPreservedInSnapshot() {
        val engine = FakePlaybackEngine()
        engine.defaultScript = FakeScript(emitDegradedObservation = true)
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())

        val degraded = listener.capabilityObservations()
            .map { it.observation }
            .filter { it.state == CapabilityState.DEGRADED }
        assertThat(degraded).hasSize(1)
        assertThat(degraded.single().evidence).contains("software decoder")
        assertThat(session.snapshot().capabilityObservations.map { it.state })
            .contains(CapabilityState.DEGRADED)
    }

    @Test
    fun diagnosticsSnapshot_isRedacted() {
        val engine = newEngine()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.prepare(request(uri = "https://media.example.test/secret/path.mp4?token=abc123"))

        val snapshot: DiagnosticsSnapshot = session.snapshot()
        val text = snapshot.toString()

        assertThat(text).doesNotContain("media.example.test")
        assertThat(text).doesNotContain("token=abc123")
        assertThat(text).doesNotContain("http://")
        assertThat(text).doesNotContain("https://")
        assertThat(snapshot.redaction.excludedFields).containsAtLeast("uri", "headers", "token")
        assertThat(snapshot.isRedacted()).isTrue()

        assertThat(snapshot.lineage.requestId).isEqualTo("req-1")
    }

    @Test
    fun droppedFrames_areReported_andSnapshotted() {
        val engine = FakePlaybackEngine()
        engine.defaultScript = FakeScript(dropFramesOnSeek = 3)
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())
        session.play()
        session.seekTo(1_000L)

        val dropped = listener.events.filterIsInstance<PlaybackEvent.DroppedFrames>().last()
        assertThat(dropped.droppedCount).isEqualTo(3)

        assertThat(dropped.totalCount).isNull()
        assertThat(session.snapshot().droppedFrames).isEqualTo(3)
    }

    @Test
    fun decoderObservations_areReported() {
        val engine = newEngine()
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())

        val decoders = listener.events.filterIsInstance<PlaybackEvent.DecoderObserved>().map { it.decoder }
        assertThat(decoders).isNotEmpty()
        assertThat(session.snapshot().decoderObservations).containsExactlyElementsIn(decoders)
    }

    @Test
    fun eventsFlow_deliversIdenticalSequence() = runBlocking {
        val engine = newEngine()
        val session = engine.createSession(firstLineage(), routeSnapshot()) as FakePlaybackSession
        val listener = RecordingPlaybackListener()
        session.addListener(listener)

        val collected = java.util.Collections.synchronizedList(mutableListOf<PlaybackEvent>())

        val job = launch(Dispatchers.Default) {
            session.events().collect { collected += it }
        }

        withTimeout(5_000) {
            while (session.listenerCount < 2) {
                delay(1)
            }
        }

        session.prepare(request())
        session.play()
        session.pause()
        session.release()

        withTimeout(5_000) {
            while (collected.size < listener.events.size) {
                delay(1)
            }
        }
        job.cancel()
        job.join()

        assertThat(collected.toList()).containsExactlyElementsIn(listener.events).inOrder()
    }

    @Test
    fun release_emitsPhase_andBlocksCommands() {
        val engine = newEngine()
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())
        session.release()

        val phases = listener.events.filterIsInstance<PlaybackEvent.SessionPhaseChanged>().map { it.phase }
        assertThat(phases.last()).isEqualTo(SessionPhase.RELEASED)
        assertThat(listener.states().last()).isEqualTo(PlaybackState.RELEASED)
        assertThrows(IllegalStateException::class.java) { session.play() }
        assertThrows(IllegalStateException::class.java) { session.prepare(request()) }
    }

    @Test
    fun naturalEnd_emitsEndedEvent() {
        val engine = FakePlaybackEngine()
        engine.defaultScript = FakeScript(durationMs = 2_000L)
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot()) as FakePlaybackSession
        session.addListener(listener)

        session.prepare(request())
        session.play()
        session.advanceClock(2_500L)

        val ended = listener.events.filterIsInstance<PlaybackEvent.Ended>()
        assertThat(ended.map { it.reason }).containsExactly(EndReason.END_OF_MEDIA)
        assertThat(listener.states().last()).isEqualTo(PlaybackState.ENDED)
        assertThat(session.snapshot().state).isEqualTo(PlaybackState.ENDED)
        assertThat(session.snapshot().positionMs).isEqualTo(2_000L)
    }

    @Test
    fun engine_supportsSingleActiveSession() {
        val engine = newEngine()
        engine.createSession(firstLineage(), routeSnapshot())

        assertThrows(IllegalStateException::class.java) {
            engine.createSession(firstLineage(sessionId = "sess-2"), routeSnapshot())
        }
    }

    @Test
    fun engineRelease_tearsDownSessions() {
        val engine = newEngine()
        val session = engine.createSession(firstLineage(), routeSnapshot())

        engine.release()

        assertThrows(IllegalStateException::class.java) { session.play() }
        assertThrows(IllegalStateException::class.java) {
            engine.createSession(firstLineage(sessionId = "sess-2"), routeSnapshot())
        }
    }

    @Test
    fun stopReasonSuperseded_mapsToSupersededCancellation() {
        val engine = newEngine()
        val listener = RecordingPlaybackListener()
        val session = engine.createSession(firstLineage(), routeSnapshot())
        session.addListener(listener)

        session.prepare(request())
        session.stop(StopReason.SUPERSEDED)

        assertThat(listener.cancellations()).containsExactly(CancellationReason.SUPERSEDED)
    }
}
