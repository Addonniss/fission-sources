package dev.infusion.feature.vertical.player

import dev.infusion.core.playback.api.PlaybackSegment
import dev.infusion.core.playback.api.PlaybackSegmentTimeline
import dev.infusion.core.playback.api.SegmentEvidenceInput
import dev.infusion.core.playback.api.SegmentEvidenceSource
import dev.infusion.core.playback.api.SegmentLookupIdentity
import dev.infusion.core.playback.api.SegmentReconciliation
import dev.infusion.core.playback.api.SegmentSourceOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PlaybackSegmentCoordinator(
    private val sources: List<SegmentEvidenceSource>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val lookupTimeoutMs: Long = 6_000L,
) {

    private val _timeline = MutableStateFlow<PlaybackSegmentTimeline?>(null)

    val timeline: StateFlow<PlaybackSegmentTimeline?> = _timeline.asStateFlow()

    private val _lastOutcomes = MutableStateFlow<List<Pair<String, SegmentSourceOutcome>>>(emptyList())
    val lastOutcomes: StateFlow<List<Pair<String, SegmentSourceOutcome>>> = _lastOutcomes.asStateFlow()

    private var attemptToken: Int = 0
    private var job: Job? = null

    private var latestInput: SegmentEvidenceInput? = null

    private val cache = mutableMapOf<String, List<PlaybackSegment>>()

    fun begin(input: SegmentEvidenceInput) {
        cancelJob()
        val token = ++attemptToken
        latestInput = input
        _timeline.value = null
        val key = cacheKey(input.identity)
        val cached = synchronized(cache) { cache[key] }
        if (cached != null) {
            _timeline.value = SegmentReconciliation.reconcile(input, cached)
            _lastOutcomes.value = emptyList()
            return
        }
        job = scope.launch(ioDispatcher) {
            val outcomes = sources.map { source -> source.id to fetchBounded(source, input.identity) }
            if (token != attemptToken) return@launch
            _lastOutcomes.value = outcomes
            val providerSegments = outcomes
                .filter { it.second is SegmentSourceOutcome.Evidence }
                .flatMap { (it.second as SegmentSourceOutcome.Evidence).segments }

            val anySucceeded = outcomes.any {
                it.second is SegmentSourceOutcome.Evidence || it.second is SegmentSourceOutcome.Absent
            }
            if (anySucceeded) {
                synchronized(cache) { cache[key] = providerSegments }
            }
            lastProviderSegments = providerSegments

            val timeline = SegmentReconciliation.reconcile(latestInput ?: input, providerSegments)
            if (token == attemptToken) _timeline.value = timeline
        }
    }

    fun end() {
        attemptToken++
        cancelJob()
        latestInput = null
        _timeline.value = null
        _lastOutcomes.value = emptyList()
    }

    fun reconcileWith(input: SegmentEvidenceInput) {
        if (latestInput == null) return
        latestInput = input
        val key = cacheKey(input.identity)
        val segments = synchronized(cache) { cache[key] } ?: lastProviderSegments
        _timeline.value = SegmentReconciliation.reconcile(input, segments)
    }

    private var lastProviderSegments: List<PlaybackSegment> = emptyList()

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    private suspend fun fetchBounded(
        source: SegmentEvidenceSource,
        identity: SegmentLookupIdentity,
    ): SegmentSourceOutcome = try {
        withTimeoutOrNull(lookupTimeoutMs) {
            try {
                source.fetch(identity)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                SegmentSourceOutcome.Failed("${source.id}-error")
            }
        } ?: SegmentSourceOutcome.Failed("${source.id}-timeout")
    } catch (e: CancellationException) {
        throw e
    }

    internal fun cacheKey(identity: SegmentLookupIdentity): String = listOf(
        identity.tmdbId,
        identity.imdbId,
        identity.showImdbId,
        identity.seasonNumber,
        identity.episodeNumber,
        identity.isMovie,
    ).joinToString("|")
}
