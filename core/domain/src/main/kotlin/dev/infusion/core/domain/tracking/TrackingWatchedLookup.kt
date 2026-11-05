package dev.infusion.core.domain.tracking

import dev.infusion.core.model.watch.WatchKey
import java.time.Instant

data class TrackingWatchedQuery(
    val externalIds: TrackingExternalIds,
    val kind: TrackingMediaKind,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    init {
        require(!externalIds.isEmpty) {
            "a watched query requires at least one proven external identifier to be matchable"
        }
        when (kind) {
            TrackingMediaKind.MOVIE -> require(seasonNumber == null && episodeNumber == null) {
                "a movie watched query carries no season/episode coordinates"
            }
            TrackingMediaKind.SHOW -> {
                require(seasonNumber != null && episodeNumber != null) {
                    "an episode watched query requires BOTH season and episode coordinates"
                }
                require(seasonNumber > 0 && episodeNumber > 0) {
                    "season/episode coordinates must be positive"
                }
            }
        }
    }
}

enum class TrackingWatchedVerdict { WATCHED, NOT_WATCHED, NOT_FOUND, MALFORMED }

data class TrackingWatchedAnswer(
    val verdict: TrackingWatchedVerdict,
    val watchedAt: Instant? = null,
) {
    init {
        require(verdict == TrackingWatchedVerdict.WATCHED || watchedAt == null) {
            "only a watched answer carries an observation timestamp"
        }
    }
}

interface TrackingWatchedLookupPort {

    val providerId: String

    suspend fun readWatched(queries: List<TrackingWatchedQuery>): TrackingInboundOutcome<List<TrackingWatchedAnswer>>

    companion object {

        const val MAX_BATCH: Int = 100
    }
}

enum class TrackingWatchedCacheVerdict { WATCHED, NOT_WATCHED, UNRESOLVED }

data class TrackingWatchedCacheEntry(
    val key: String,
    val verdict: TrackingWatchedCacheVerdict,
    val watchedAt: Instant? = null,
    val resolvedUnderGate: Instant? = null,
) {
    init {
        require(key.isNotBlank()) { "a cache entry requires its canonical key" }
        require(verdict == TrackingWatchedCacheVerdict.WATCHED || watchedAt == null) {
            "only a watched cache entry carries an observation timestamp"
        }
    }
}

interface TrackingWatchedCacheStore {

    fun entries(providerId: String, keys: Collection<String>): Map<String, TrackingWatchedCacheEntry>

    fun put(providerId: String, entries: List<TrackingWatchedCacheEntry>)

    fun clear(providerId: String)
}

object TrackingWatchedKeys {

    fun of(key: WatchKey): String {
        if (key.isEpisode) {
            val series = key.seriesId!!.value
            val alias = key.alternateOrderAlias
            return if (alias != null) {
                "e|$series|a:${alias.authority}:${alias.value}"
            } else {
                "e|$series|${key.seasonNumber}x${key.episodeNumber}"
            }
        }
        val title = key.titleId!!.value
        val edition = key.editionId?.value
        return if (edition == null) "m|$title" else "m|$title|e:$edition"
    }
}

data class TrackingWatchedLookupResult(

    val demanded: Int = 0,

    val cached: Int = 0,

    val unresolved: Int = 0,

    val queried: Int = 0,

    val requests: Int = 0,

    val imported: Int = 0,

    val notWatched: Int = 0,

    val skipped: Map<TrackingInboundSkipReason, Int> = emptyMap(),

    val failure: String? = null,
) {
    val anySkipped: Boolean get() = skipped.isNotEmpty()
}
