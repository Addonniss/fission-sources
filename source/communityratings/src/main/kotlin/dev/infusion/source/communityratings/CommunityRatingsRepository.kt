package dev.infusion.source.communityratings

import dev.infusion.core.domain.metadata.MetadataCacheStore
import dev.infusion.core.domain.ratings.CommunityRatingsPort
import dev.infusion.core.domain.ratings.OmdbTestOutcome
import dev.infusion.core.domain.ratings.RatingsPreferences
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaRating
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.core.model.metadata.RatingSupplier
import dev.infusion.core.network.HardenedHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CommunityRatingsRepository(
    http: HardenedHttpClient,
    cache: MetadataCacheStore,
    omdbKeyProvider: () -> String? = { null },
    traktClientIdProvider: () -> String? = { null },
    simklClientIdProvider: () -> String? = { null },

    private val mdbListMediaInfo: MdbListMediaInfoReader? = null,

    private val mdbListConnected: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = RatingsCache.DEFAULT_TTL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : CommunityRatingsPort {

    private val httpHelper = SupplierHttp(http)
    private val cacheStore = RatingsCache(cache, clock, ttlMs)
    private val omdbKey = omdbKeyProvider

    private val adapters: Map<RatingSupplier, SupplierAdapter> = listOf(
        OmdbAdapter(httpHelper, omdbKeyProvider),
        TraktAdapter(httpHelper, traktClientIdProvider),
        SimklAdapter(httpHelper, simklClientIdProvider),
        MdbListAdapter(mdbListMediaInfo, mdbListConnected),
    ).associateBy { it.supplier }

    private val inFlightLock = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<List<MediaRating>>>()

    private val episodeInFlight = mutableMapOf<String, Deferred<Map<Int, List<MediaRating>>>>()

    override fun availableSuppliers(): Set<RatingSupplier> =
        adapters.values.filter { it.available() }.map { it.supplier }.toSet()

    override fun availableEpisodeMetrics(): Set<RatingMetric> =
        adapters.values.filter { it.available() }.flatMap { it.episodeMetrics }.toSet()

    override suspend fun ratingsFor(
        identity: MediaIdentity,
        type: MediaType,
        preferences: RatingsPreferences,
        coveredMetrics: Set<RatingMetric>,
    ): List<MediaRating> {
        val wanted = preferences.displayedMetrics

        if (wanted.isEmpty()) return emptyList()
        val key = identity.cacheKey() ?: return emptyList()
        val lookup = RatingLookup(
            type = type,
            imdbId = identity.externalIds.imdbId,
            tmdbId = identity.externalIds.tmdbId,
            tvdbId = identity.externalIds.tvdbId,
        )
        return singleFlight(key) { load(key, lookup, preferences, wanted, coveredMetrics) }
    }

    private suspend fun singleFlight(key: String, block: suspend () -> List<MediaRating>): List<MediaRating> {
        val existing = inFlightLock.withLock { inFlight[key] }
        if (existing != null) return existing.await()
        val deferred = scope.async { block() }
        inFlightLock.withLock { inFlight[key] = deferred }
        return try {
            deferred.await()
        } finally {
            inFlightLock.withLock { inFlight.remove(key) }
        }
    }

    private suspend fun load(
        identityKey: String,
        lookup: RatingLookup,
        preferences: RatingsPreferences,
        wanted: Set<RatingMetric>,
        covered: Set<RatingMetric>,
    ): List<MediaRating> {
        val now = clock()
        val cached = cacheStore.load(identityKey)
        val coveredMetrics = covered.toMutableSet()
        val accumulated = LinkedHashMap<RatingSupplier, List<MediaRating>>()
        val updated = LinkedHashMap<RatingSupplier, RatingsCache.Entry>()
        var mutated = false

        for (supplier in preferences.walkOrder) {
            if (coveredMetrics.containsAll(wanted)) break
            val adapter = adapters[supplier] ?: continue
            if (!adapter.available()) continue

            val entry = cached[supplier]
            val canContribute = adapter.metrics.any { it in wanted && it !in coveredMetrics }

            if (entry != null && entry.isFresh(now, ttlMs)) {

                accumulated[supplier] = entry.ratings
                updated[supplier] = entry
                entry.ratings.forEach { it.ratedMetric?.let(coveredMetrics::add) }
                continue
            }

            if (!canContribute) {

                if (entry != null) {
                    accumulated[supplier] = entry.ratings
                    updated[supplier] = entry
                    entry.ratings.forEach { it.ratedMetric?.let(coveredMetrics::add) }
                }
                continue
            }

            val fetched = runCatching { adapter.fetch(lookup) }.getOrNull()
            if (fetched != null && fetched.isNotEmpty()) {
                accumulated[supplier] = fetched
                val newEntry = RatingsCache.Entry(now, fetched)
                if (newEntry != entry) mutated = true
                updated[supplier] = newEntry
                fetched.forEach { it.ratedMetric?.let(coveredMetrics::add) }
            } else {

                if (entry != null) {
                    accumulated[supplier] = entry.ratings
                    updated[supplier] = entry
                    entry.ratings.forEach { it.ratedMetric?.let(coveredMetrics::add) }
                }
            }
        }

        if (mutated || updated.keys.any { cached[it] != updated[it] }) {

            val merged = LinkedHashMap(cached)
            merged.putAll(updated)
            cacheStore.store(identityKey, merged)
        }
        return accumulated.values.flatten()
    }

    override suspend fun episodeRatingsFor(
        identity: MediaIdentity,
        seasonNumber: Int,
        preferences: RatingsPreferences,
        wantedMetrics: Set<RatingMetric>,
    ): Map<Int, List<MediaRating>> {
        if (wantedMetrics.isEmpty() || seasonNumber < 0) return emptyMap()
        val key = identity.cacheKey() ?: return emptyMap()
        val lookup = RatingLookup(
            type = identity.type,
            imdbId = identity.externalIds.imdbId,
            tmdbId = identity.externalIds.tmdbId,
            tvdbId = identity.externalIds.tvdbId,
        )
        val seasonKey = "$key#s$seasonNumber"
        return singleFlightEpisodes(seasonKey) {
            loadSeason(seasonKey, lookup, seasonNumber, preferences, wantedMetrics)
        }
    }

    private suspend fun singleFlightEpisodes(
        key: String,
        block: suspend () -> Map<Int, List<MediaRating>>,
    ): Map<Int, List<MediaRating>> {
        val existing = inFlightLock.withLock { episodeInFlight[key] }
        if (existing != null) return existing.await()
        val deferred = scope.async { block() }
        inFlightLock.withLock { episodeInFlight[key] = deferred }
        return try {
            deferred.await()
        } finally {
            inFlightLock.withLock { episodeInFlight.remove(key) }
        }
    }

    private suspend fun loadSeason(
        seasonKey: String,
        lookup: RatingLookup,
        seasonNumber: Int,
        preferences: RatingsPreferences,
        wanted: Set<RatingMetric>,
    ): Map<Int, List<MediaRating>> {
        val now = clock()
        val cached = cacheStore.loadSeason(seasonKey)
        val accumulated = LinkedHashMap<Int, MutableList<MediaRating>>()
        val updated = LinkedHashMap<RatingSupplier, RatingsCache.SeasonEntry>()
        var mutated = false

        for (supplier in preferences.walkOrder) {
            val adapter = adapters[supplier] ?: continue

            if (adapter.episodeMetrics.none { it in wanted }) continue
            if (!adapter.available()) continue

            val entry = cached[supplier]
            if (entry != null && entry.isFresh(now, ttlMs)) {

                mergeEpisodes(accumulated, entry.episodes)
                updated[supplier] = entry
                continue
            }

            val fetched = runCatching { adapter.fetchSeasonEpisodes(lookup, seasonNumber) }.getOrNull()
            if (fetched != null && fetched.isNotEmpty()) {
                val newEntry = RatingsCache.SeasonEntry(now, fetched)
                if (newEntry != entry) mutated = true
                updated[supplier] = newEntry
                mergeEpisodes(accumulated, fetched)
            } else if (entry != null) {

                updated[supplier] = entry
                mergeEpisodes(accumulated, entry.episodes)
            }
        }

        if (mutated || updated.keys.any { cached[it] != updated[it] }) {
            val merged = LinkedHashMap(cached)
            merged.putAll(updated)
            cacheStore.storeSeason(seasonKey, merged)
        }
        return accumulated
    }

    private fun mergeEpisodes(
        target: LinkedHashMap<Int, MutableList<MediaRating>>,
        source: Map<Int, List<MediaRating>>,
    ) {
        source.forEach { (number, ratings) ->
            val list = target.getOrPut(number) { mutableListOf() }
            val existing = list.mapNotNull { it.ratedMetric }.toMutableSet()
            ratings.forEach { rating -> rating.ratedMetric?.let { if (existing.add(it)) list.add(rating) } }
        }
    }

    override suspend fun testOmdb(candidateKey: String?): OmdbTestOutcome {
        val key = candidateKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: omdbKey()?.takeIf { it.isNotBlank() }
            ?: return OmdbTestOutcome.MissingKey
        val answer = httpHelper.get("https://www.omdbapi.com/?i=$OMDB_TEST_ID&apikey=$key")
            ?: return OmdbTestOutcome.Unreachable("no usable response")

        val body = answer.body
        val responseFalse = runCatching {
            val json = org.json.JSONObject(body)
            !json.optBoolean("Response", true)
        }.getOrDefault(false)
        val error = runCatching {
            org.json.JSONObject(body).optString("Error")
        }.getOrDefault("")
        val errorLower = error.lowercase()
        return when {
            answer.status == 200 && !responseFalse -> OmdbTestOutcome.Valid
            errorLower.contains("invalid api key") || errorLower.contains("no api key") -> OmdbTestOutcome.InvalidKey
            errorLower.contains("limit") || errorLower.contains("exceed") || errorLower.contains("quota") ->
                OmdbTestOutcome.QuotaExhausted
            answer.status == 401 || answer.status == 403 -> OmdbTestOutcome.InvalidKey
            answer.status == 429 -> OmdbTestOutcome.QuotaExhausted
            responseFalse -> OmdbTestOutcome.Unreachable(error.ifBlank { "OMDb reported an error" })
            else -> OmdbTestOutcome.Unreachable("HTTP ${answer.status}")
        }
    }

    companion object {

        internal const val OMDB_TEST_ID = "tt3896198"
    }
}
