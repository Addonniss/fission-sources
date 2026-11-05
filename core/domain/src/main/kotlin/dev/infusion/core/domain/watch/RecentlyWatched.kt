package dev.infusion.core.domain.watch

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch

enum class RecentlyWatchedKind { MOVIE, SHOW }

enum class WatchedHistoryFactKind {

    WATCHED,

    UNWATCHED,
}

data class WatchedHistoryFact(
    val watchKey: WatchKey,
    val kind: WatchedHistoryFactKind,
    val watchedAt: Instant?,
    val ledgerAt: Instant,
    val providerId: String? = null,
) {
    init {
        require(kind == WatchedHistoryFactKind.WATCHED || watchedAt == null) {
            "an unwatched tombstone carries no viewing instant"
        }
    }

    val evidenceAt: Instant get() = watchedAt ?: ledgerAt
}

enum class WatchedHistoryShape { MOVIE, EPISODE }

data class WatchedHistoryCursor(

    val undated: Boolean = false,

    val watchedAtMs: Long? = null,

    val watchKey: String,

    val eventId: String,
) {

    fun precedes(other: WatchedHistoryCursor): Boolean = when {

        undated != other.undated -> !undated
        undated -> watchKey > other.watchKey ||
            (watchKey == other.watchKey && eventId > other.eventId)

        watchedAtMs != other.watchedAtMs -> (watchedAtMs ?: Long.MIN_VALUE) > (other.watchedAtMs ?: Long.MIN_VALUE)
        watchKey != other.watchKey -> watchKey > other.watchKey
        else -> eventId > other.eventId
    }
}

data class WatchedHistoryPage(
    val facts: List<WatchedHistoryFact>,
    val next: WatchedHistoryCursor?,
    val last: WatchedHistoryCursor?,
)

interface WatchedHistorySource {

    fun observeHistoryPublications(): Flow<Unit>

    suspend fun readWatchedFactsPage(
        shape: WatchedHistoryShape,
        after: WatchedHistoryCursor?,
    ): WatchedHistoryPage
}

data class RecentlyWatchedEntry(
    val kind: RecentlyWatchedKind,
    val titleId: TitleId,
    val watchedAt: Instant?,
    val providerId: String?,
    val episode: RecentlyWatchedEpisode? = null,
    val watchedUnits: Int = 1,
) {
    init {
        require(watchedUnits >= 1) { "an entry stands for at least one watched unit" }
    }

    val entryKey: String get() = "${kind.name}:${titleId.value}"
}

data class RecentlyWatchedEpisode(val seasonNumber: Int, val episodeNumber: Int)

data class RecentlyWatchedRows(
    val movies: List<RecentlyWatchedEntry> = emptyList(),
    val shows: List<RecentlyWatchedEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = movies.isEmpty() && shows.isEmpty()
}

data class RecentlyWatchedAuthority(
    val activeTrackerId: String? = null,
) {

    val trackerGoverns: Boolean get() = activeTrackerId != null

    companion object {

        val OnDeviceOnly: RecentlyWatchedAuthority = RecentlyWatchedAuthority()
    }
}

object RecentlyWatchedHistory {

    const val DEFAULT_ENTRIES_PER_PAGE: Int = 20

    fun project(
        facts: List<WatchedHistoryFact>,
        authority: RecentlyWatchedAuthority,
    ): RecentlyWatchedRows {
        if (facts.isEmpty()) return RecentlyWatchedRows()

        val activeTrackerId = authority.activeTrackerId
        val eligible = facts.filter { fact ->
            fact.providerId == null || fact.providerId == activeTrackerId
        }
        if (eligible.isEmpty()) return RecentlyWatchedRows()

        val tombstones = HashMap<WatchKey, Instant>()
        for (fact in facts) {
            if (fact.kind != WatchedHistoryFactKind.UNWATCHED) continue
            if (fact.providerId != null && fact.providerId != activeTrackerId) continue
            val previous = tombstones[fact.watchKey]
            if (previous == null || fact.ledgerAt.isAfter(previous)) tombstones[fact.watchKey] = fact.ledgerAt
        }

        val movies = LinkedHashMap<TitleId, Accumulator>()
        val shows = LinkedHashMap<TitleId, Accumulator>()
        for (fact in eligible) {
            if (fact.kind != WatchedHistoryFactKind.WATCHED) continue
            val tombstone = tombstones[fact.watchKey]
            if (tombstone != null && !fact.evidenceAt.isAfter(tombstone)) continue

            val key = fact.watchKey
            val seriesId = key.seriesId
            if (seriesId != null) {

                val accumulator = shows.getOrPut(seriesId) { Accumulator() }
                accumulator.add(
                    watchedAt = fact.watchedAt,
                    ledgerAt = fact.ledgerAt,
                    providerId = fact.providerId,
                    unitKey = key,
                    episode = key.seasonNumber?.let { season ->
                        key.episodeNumber?.let { episode -> RecentlyWatchedEpisode(season, episode) }
                    },
                )
            } else {
                val titleId = key.titleId ?: continue
                movies.getOrPut(titleId) { Accumulator() }.add(
                    watchedAt = fact.watchedAt,
                    ledgerAt = fact.ledgerAt,
                    providerId = fact.providerId,
                    unitKey = key,
                    episode = null,
                )
            }
        }

        return RecentlyWatchedRows(
            movies = movies.map { (titleId, acc) -> acc.toEntry(RecentlyWatchedKind.MOVIE, titleId) }
                .sortedWith(EntryOrder),
            shows = shows.map { (titleId, acc) -> acc.toEntry(RecentlyWatchedKind.SHOW, titleId) }
                .sortedWith(EntryOrder),
        )
    }

    private val EntryOrder: Comparator<RecentlyWatchedEntry> = Comparator { left, right ->
        val leftAt = left.watchedAt
        val rightAt = right.watchedAt
        val byRecency = when {
            leftAt != null && rightAt != null -> rightAt.compareTo(leftAt)
            leftAt == null && rightAt == null -> 0

            leftAt == null -> 1
            else -> -1
        }
        if (byRecency != 0) byRecency else left.titleId.value.compareTo(right.titleId.value)
    }

    private class Accumulator {
        private var bestWatchAt: Instant? = null
        private var bestLedgerAt: Instant? = null
        private var bestProviderId: String? = null
        private var bestEpisode: RecentlyWatchedEpisode? = null
        private val units = LinkedHashSet<String>()

        fun add(
            watchedAt: Instant?,
            ledgerAt: Instant,
            providerId: String?,
            unitKey: WatchKey,
            episode: RecentlyWatchedEpisode?,
        ) {
            units += unitKey.toString()

            val bettersExisting = when {
                bestWatchAt == null -> watchedAt != null
                watchedAt == null -> false
                watchedAt.isAfter(bestWatchAt!!) -> true
                watchedAt == bestWatchAt -> ledgerAt.isAfter(bestLedgerAt)
                else -> false
            }
            if (!bettersExisting) return
            bestWatchAt = watchedAt
            bestLedgerAt = ledgerAt
            bestProviderId = providerId
            bestEpisode = episode
        }

        fun toEntry(kind: RecentlyWatchedKind, titleId: TitleId): RecentlyWatchedEntry =
            RecentlyWatchedEntry(
                kind = kind,
                titleId = titleId,
                watchedAt = bestWatchAt,
                providerId = bestProviderId,
                episode = bestEpisode,
                watchedUnits = units.size,
            )
    }
}

sealed interface RecentlyWatchedDemand {

    data object Refresh : RecentlyWatchedDemand

    data class More(val kind: RecentlyWatchedKind) : RecentlyWatchedDemand
}

data class RecentlyWatchedSnapshot(
    val rows: RecentlyWatchedRows = RecentlyWatchedRows(),
    val loaded: Boolean = false,
    val moviesHasMore: Boolean = false,
    val showsHasMore: Boolean = false,
)

class GetRecentlyWatchedUseCase(
    private val history: WatchedHistorySource,
    private val authority: () -> RecentlyWatchedAuthority = { RecentlyWatchedAuthority.OnDeviceOnly },

    private val entriesPerPage: Int = RecentlyWatchedHistory.DEFAULT_ENTRIES_PER_PAGE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    init {
        require(entriesPerPage > 0) { "entriesPerPage must be positive" }
    }

    fun observe(
        demands: Flow<RecentlyWatchedDemand>,
        surfacePresent: StateFlow<Boolean> = MutableStateFlow(true),
        playbackActive: StateFlow<Boolean> = MutableStateFlow(false),
    ): Flow<RecentlyWatchedSnapshot> =
        merge(

            history.observeHistoryPublications()
                .filter { surfacePresent.value && !playbackActive.value }
                .conflate()
                .map { RecentlyWatchedDemand.Refresh },
            demands,

            playbackActive
                .drop(1)
                .filter { active -> !active }
                .map { RecentlyWatchedDemand.Refresh },
        )
            .scan(Load()) { state, demand -> advance(state, demand, playbackActive) }

            .distinctUntilChanged()
            .map { load -> load.snapshot() }
            .flowOn(ioDispatcher)

    private suspend fun advance(
        state: Load,
        demand: RecentlyWatchedDemand,
        playbackActive: StateFlow<Boolean>,
    ): Load {

        if (playbackActive.value) return state
        val derived = workUnlessPlaybackStarts(playbackActive) { derive(state, demand) } ?: return state
        return derived
    }

    private suspend fun <T> workUnlessPlaybackStarts(
        playbackActive: StateFlow<Boolean>,
        work: suspend () -> T,
    ): T? = coroutineScope {
        val running = async { work() }
        val guard = launch {
            playbackActive.first { active -> active }
            running.cancel()
        }
        try {
            running.await()
        } catch (cancelled: CancellationException) {

            currentCoroutineContext().ensureActive()
            null
        } finally {
            guard.cancel()
        }
    }

    private suspend fun derive(state: Load, demand: RecentlyWatchedDemand): Load {
        val current = authority()

        val revisionChanged = state.loaded && state.authority != current
        var movies = state.movies
        var shows = state.shows
        when {
            revisionChanged || demand is RecentlyWatchedDemand.Refresh -> {
                movies = reload(WatchedHistoryShape.MOVIE, movies, current)
                shows = reload(WatchedHistoryShape.EPISODE, shows, current)
            }

            demand is RecentlyWatchedDemand.More -> when (demand.kind) {
                RecentlyWatchedKind.MOVIE ->
                    movies = extend(WatchedHistoryShape.MOVIE, movies, current)

                RecentlyWatchedKind.SHOW ->
                    shows = extend(WatchedHistoryShape.EPISODE, shows, current)
            }
        }
        return Load(
            movies = movies,
            shows = shows,
            authority = current,
            loaded = true,
            rows = RecentlyWatchedHistory.project(movies.facts + shows.facts, current),
        )
    }

    private suspend fun reload(
        shape: WatchedHistoryShape,
        loaded: Loaded,
        current: RecentlyWatchedAuthority,
    ): Loaded = read(
        shape = shape,
        from = loaded,
        startAt = null,
        current = current,
        stopAtHorizon = loaded.horizon,
    )

    private suspend fun extend(
        shape: WatchedHistoryShape,
        loaded: Loaded,
        current: RecentlyWatchedAuthority,
    ): Loaded {
        if (loaded.exhausted) return loaded
        return read(
            shape = shape,
            from = loaded,
            startAt = loaded.next,
            current = current,
            stopAtHorizon = null,
        )
    }

    private suspend fun read(
        shape: WatchedHistoryShape,
        from: Loaded,
        startAt: WatchedHistoryCursor?,
        current: RecentlyWatchedAuthority,
        stopAtHorizon: WatchedHistoryCursor?,
    ): Loaded {
        val kind = shape.rowKind
        val before = rowKeys(kind, from.facts, current)
        val facts = ArrayList(from.facts)
        var cursor = startAt
        var last = from.horizon
        while (true) {
            val page = history.readWatchedFactsPage(shape, cursor)
            facts += page.facts
            last = page.last ?: last
            cursor = page.next

            if (cursor == null) break
            if (stopAtHorizon != null) {

                if (!cursor.precedes(stopAtHorizon)) break
            } else {
                val gained = rowKeys(kind, facts, current).count { key -> key !in before }
                if (gained >= entriesPerPage) break
            }
        }
        return Loaded(
            facts = facts,
            next = cursor,
            horizon = last,
            exhausted = cursor == null,
        )
    }

    private fun rowKeys(
        kind: RecentlyWatchedKind,
        facts: List<WatchedHistoryFact>,
        current: RecentlyWatchedAuthority,
    ): Set<String> {
        val rows = RecentlyWatchedHistory.project(facts, current)
        val entries = when (kind) {
            RecentlyWatchedKind.MOVIE -> rows.movies
            RecentlyWatchedKind.SHOW -> rows.shows
        }
        return entries.mapTo(HashSet(entries.size)) { entry -> entry.entryKey }
    }

    private class Loaded(
        val facts: List<WatchedHistoryFact> = emptyList(),

        val next: WatchedHistoryCursor? = null,

        val horizon: WatchedHistoryCursor? = null,
        val exhausted: Boolean = false,
    )

    private class Load(
        val movies: Loaded = Loaded(),
        val shows: Loaded = Loaded(),
        val authority: RecentlyWatchedAuthority? = null,
        val loaded: Boolean = false,
        val rows: RecentlyWatchedRows = RecentlyWatchedRows(),
    ) {
        fun snapshot(): RecentlyWatchedSnapshot = RecentlyWatchedSnapshot(
            rows = rows,
            loaded = loaded,
            moviesHasMore = loaded && !movies.exhausted,
            showsHasMore = loaded && !shows.exhausted,
        )
    }

    private val WatchedHistoryShape.rowKind: RecentlyWatchedKind
        get() = when (this) {
            WatchedHistoryShape.MOVIE -> RecentlyWatchedKind.MOVIE
            WatchedHistoryShape.EPISODE -> RecentlyWatchedKind.SHOW
        }

}
