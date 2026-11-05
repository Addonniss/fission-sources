package dev.infusion.source.aggregation.select

import dev.infusion.core.domain.candidate.CandidateHandoffStore
import dev.infusion.core.domain.source.PlaybackSourceRegistry
import dev.infusion.core.domain.sync.PlaybackSessionContext
import dev.infusion.core.domain.sync.PlaybackSessionContextStore
import dev.infusion.core.domain.watch.WatchEventLedger
import dev.infusion.core.model.EventId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.SessionId
import dev.infusion.core.model.watch.ResumeAnchor
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import java.time.Instant
import java.util.UUID

class FallbackCoordinator(
    private val ledger: WatchEventLedger,
    private val handoffStore: CandidateHandoffStore,
    private val contextStore: PlaybackSessionContextStore,

    private val sourceRegistry: PlaybackSourceRegistry? = null,

    private val policy: SelectionPolicy = SelectionPolicy.MANUAL,
    private val budget: FallbackBudget = FallbackBudget(),
    private val now: () -> Instant = Instant::now,
) {

    private var sessionId: SessionId? = null
    private var watchKey: WatchKey? = null
    private var cancelled = false
    private var meaningfulPlaybackStarted = false
    private var completed = false
    private var attemptsUsed = 0
    private val sameCandidateRetries = mutableMapOf<String, Int>()

    val activeSessionId: SessionId? get() = sessionId

    suspend fun beginWatchAttempt(
        watchKey: WatchKey,
        candidate: PlayableCandidate,
    ): SessionId {

        val registry = sourceRegistry
        if (registry != null && !registry.ensureSourceRow(candidate)) {
            throw WatchAttemptSetupException(
                "cannot begin watch attempt: no FK parent source row for " +
                    "candidate source ${candidate.sourceId.value}",
            )
        }
        val session = SessionId(UUID.randomUUID())
        this.sessionId = session
        this.watchKey = watchKey
        cancelled = false
        meaningfulPlaybackStarted = false
        attemptsUsed = 1
        sameCandidateRetries.clear()

        contextStore.put(
            PlaybackSessionContext(
                sourceId = candidate.sourceId.value.toString(),
                watchSessionId = session.value.toString(),
                itemId = candidate.nativeItemId.nativeId,
                mediaSourceId = candidate.familyId.digest,
                playSessionId = session.value.toString(),
            ),
        )
        handoffStore.put(candidate)
        ledger.append(
            WatchEvent.Local(
                eventId = EventId(UUID.randomUUID()),
                watchKey = watchKey,
                kind = WatchEvent.Kind.START,
                occurredAt = now(),
                sessionId = session,
                positionMs = 0L,
                anchor = anchorOf(candidate),
            ),
        )
        return session
    }

    suspend fun select(watchKey: WatchKey, candidate: PlayableCandidate): SessionId =
        beginWatchAttempt(watchKey, candidate)

    suspend fun onCandidateFailed(
        failure: dev.infusion.core.model.playback.PlaybackFailure,
        current: PlayableCandidate,
        nextEligible: PlayableCandidate?,
    ): FallbackDecision {
        val session = sessionId ?: return FallbackDecision.Stop("no active lineage")

        if (policy != SelectionPolicy.AUTOPLAY_WITH_FALLBACK) {
            return FallbackDecision.Stop("fallback not enabled")
        }
        val decision = FallbackPolicy.decide(
            fallbackEnabled = true,
            meaningfulPlaybackStarted = meaningfulPlaybackStarted,
            cancelled = cancelled,
            failure = failure,
            attemptsUsed = attemptsUsed,
            sameCandidateRetries = sameCandidateRetries[current.candidateId.value.toString()] ?: 0,
            budget = budget,
            current = current,
            nextEligible = nextEligible,
        )
        when (decision) {
            is FallbackDecision.RetrySame -> {
                sameCandidateRetries[current.candidateId.value.toString()] =
                    (sameCandidateRetries[current.candidateId.value.toString()] ?: 0) + 1
                recordFailureAndRestart(session, current)
            }
            is FallbackDecision.TryNext -> {
                attemptsUsed++
                recordFailureAndRestart(session, decision.candidate)
            }
            is FallbackDecision.Stop -> {

                if (!cancelled) {
                    ledger.append(
                        WatchEvent.Local(
                            eventId = EventId(UUID.randomUUID()),
                            watchKey = watchKey!!,
                            kind = WatchEvent.Kind.SOURCE_FAILURE,
                            occurredAt = now(),
                            sessionId = session,
                        ),
                    )
                }
            }
        }
        return decision
    }

    private suspend fun recordFailureAndRestart(session: SessionId, next: PlayableCandidate) {
        ledger.append(
            WatchEvent.Local(
                eventId = EventId(UUID.randomUUID()),
                watchKey = watchKey!!,
                kind = WatchEvent.Kind.SOURCE_FAILURE,
                occurredAt = now(),
                sessionId = session,
            ),
        )
        handoffStore.put(next)
        ledger.append(
            WatchEvent.Local(
                eventId = EventId(UUID.randomUUID()),
                watchKey = watchKey!!,
                kind = WatchEvent.Kind.START,
                occurredAt = now(),
                sessionId = session,
                positionMs = 0L,
                anchor = anchorOf(next),
            ),
        )
    }

    fun cancel() {
        cancelled = true
        val session = sessionId ?: return
        val key = watchKey ?: return
        ledger.append(
            WatchEvent.Local(
                eventId = EventId(UUID.randomUUID()),
                watchKey = key,
                kind = WatchEvent.Kind.CANCELLED,
                occurredAt = now(),
                sessionId = session,
            ),
        )
    }

    fun onMeaningfulPlaybackStarted() {
        meaningfulPlaybackStarted = true
    }

    fun complete(positionMs: Long, durationMs: Long?) {
        if (completed) return
        completed = true
        val session = sessionId ?: return
        val key = watchKey ?: return
        ledger.append(
            WatchEvent.Local(
                eventId = EventId(UUID.randomUUID()),
                watchKey = key,
                kind = WatchEvent.Kind.COMPLETED,
                occurredAt = now(),
                sessionId = session,
                positionMs = positionMs,
                durationMs = durationMs,
            ),
        )
    }

    private fun anchorOf(candidate: PlayableCandidate): ResumeAnchor = ResumeAnchor(
        nativeItemId = candidate.nativeItemId,
        familyId = candidate.familyId,
        versionId = candidate.originalLabel,
        positionMs = 0L,
        anchoredAt = now(),
    )
}
