package dev.infusion.feature.vertical.player

import dev.infusion.core.playback.api.StingerEvidence
import dev.infusion.core.playback.api.StingerEvidenceSource
import dev.infusion.core.playback.api.StingerLookupIdentity
import dev.infusion.core.playback.api.StingerReconciliation
import dev.infusion.core.playback.api.StingerSourceOutcome
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

class PlaybackStingerCoordinator(
    private val sources: List<StingerEvidenceSource>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val lookupTimeoutMs: Long = 6_000L,
) {

    private val _evidence = MutableStateFlow<StingerEvidence?>(null)

    val evidence: StateFlow<StingerEvidence?> = _evidence.asStateFlow()

    private var attemptToken: Int = 0
    private var job: Job? = null

    private val cache = mutableMapOf<String, StingerEvidence?>()

    fun begin(identity: StingerLookupIdentity) {
        cancelJob()
        val token = ++attemptToken
        _evidence.value = null
        if (!identity.hasTmdbCoordinates && !identity.hasTitleCoordinates) return
        val key = cacheKey(identity)
        if (cache.containsKey(key)) {
            _evidence.value = cache[key]
            return
        }
        job = scope.launch(ioDispatcher) {
            val outcomes = sources.map { source -> fetchBounded(source, identity) }
            if (token != attemptToken) return@launch
            val resolved = StingerReconciliation.reconcile(outcomes)
            synchronized(cache) { cache[key] = resolved }
            if (token == attemptToken) _evidence.value = resolved
        }
    }

    fun end() {
        attemptToken++
        cancelJob()
        _evidence.value = null
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    private suspend fun fetchBounded(
        source: StingerEvidenceSource,
        identity: StingerLookupIdentity,
    ): StingerSourceOutcome = try {
        withTimeoutOrNull(lookupTimeoutMs) {
            try {
                source.fetch(identity)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                StingerSourceOutcome.Failed("${source.id}-error")
            }
        } ?: StingerSourceOutcome.Failed("${source.id}-timeout")
    } catch (e: CancellationException) {
        throw e
    }

    internal fun cacheKey(identity: StingerLookupIdentity): String = listOf(
        identity.tmdbId,
        identity.imdbId,
        identity.title,
        identity.year,
    ).joinToString("|")
}
