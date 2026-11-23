package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.tracking.TrackingActivityStamp
import dev.infusion.core.domain.tracking.TrackingAuthorizationPoll
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingDeliveryResult
import dev.infusion.core.domain.tracking.TrackingDeviceAuthorization
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingInboundReadPort
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingPlaybackPage
import dev.infusion.core.domain.tracking.TrackingPlaybackRequest
import dev.infusion.core.domain.tracking.TrackingProviderCapabilities
import dev.infusion.core.domain.tracking.TrackingProviderPort
import dev.infusion.core.domain.tracking.TrackingSavedPlayback
import dev.infusion.core.domain.tracking.TrackingScrobbleEvent
import dev.infusion.core.domain.tracking.TrackingWatchedAnswer
import dev.infusion.core.domain.tracking.TrackingWatchedLookupPort
import dev.infusion.core.domain.tracking.TrackingWatchedQuery
import dev.infusion.core.domain.tracking.TrackingWatchedVerdict
import dev.infusion.core.domain.upnext.UpNextReadPort
import dev.infusion.core.domain.upnext.UpNextReadRequest
import dev.infusion.core.domain.upnext.UpNextTrackerPage
import kotlinx.coroutines.CancellationException

class WeTrakrTrackingProvider(
    private val configuration: WeTrakrConfiguration,
    private val client: WeTrakrHttpClient,
    private val auth: WeTrakrAuthRepository,
) : TrackingProviderPort, TrackingInboundReadPort, TrackingWatchedLookupPort, UpNextReadPort {

    override val providerId: String = WeTrakrAuthRepository.WETRAKR_PROVIDER_ID

    override val displayName: String = "WeTrakr"

    private var playbackPage: Int = 1

    private val resolutionMemo = LinkedHashMap<String, Long>()

    override fun capabilities(): TrackingProviderCapabilities = TrackingProviderCapabilities(
        providerId = providerId,

        deviceAuthorization = true,

        scrobbling = true,

        historyWrite = true,

        playbackImport = true,

        watchedImport = true,

        watchedHistory = true,

        upNext = true,

        watchlist = true,

        personalRating = setOf(
            dev.infusion.core.model.sync.PersonalRatingKind.MOVIE,
            dev.infusion.core.model.sync.PersonalRatingKind.EPISODE,
        ),
        historyImport = false,
        ratings = false,
        lists = false,

        reviews = true,
    )

    override fun connectionState(): TrackingConnectionState = auth.connectionState()

    override suspend fun beginAuthorization(): TrackingDeviceAuthorization = auth.beginAuthorization()

    override suspend fun pollAuthorization(): TrackingAuthorizationPoll = auth.pollAuthorization()

    override fun cancelAuthorization() = auth.cancelAuthorization()

    override suspend fun disconnect() = auth.disconnect()

    fun registered(): Boolean = configuration.hasRegistration

    suspend fun accountIdentity(): WeTrakrIdentityOutcome = auth.resolveAccountIdentity()

    fun accountId(): String? = auth.accountId()

    override suspend fun deliver(event: TrackingScrobbleEvent): TrackingDeliveryResult {
        val request = WeTrakrScrobbleMapping.scrobbleRequest(event, configuration.appVersion)
            ?: return deliverWatched(event)
        return when (val outcome = send(request)) {
            is WeTrakrOutcome.Success ->
                WeTrakrScrobbleMapping.deliveryResult(WeTrakrScrobbleMapping.scrobbleOutcome(outcome.response))
            is WeTrakrOutcome.Retry -> TrackingDeliveryResult.Retryable(outcome.reason)
            is WeTrakrOutcome.Rejected -> TrackingDeliveryResult.Rejected(outcome.reason)
            is WeTrakrOutcome.Unauthorized -> TrackingDeliveryResult.Unauthorized(outcome.reason)
        }
    }

    private suspend fun deliverWatched(event: TrackingScrobbleEvent): TrackingDeliveryResult =
        when (val outcome = send(WeTrakrScrobbleMapping.watchedWriteRequest(event.media, event.occurredAt))) {
            is WeTrakrOutcome.Success ->
                WeTrakrScrobbleMapping.deliveryResult(WeTrakrScrobbleMapping.watchedWriteOutcome(outcome.response))
            is WeTrakrOutcome.Retry -> TrackingDeliveryResult.Retryable(outcome.reason)
            is WeTrakrOutcome.Rejected -> TrackingDeliveryResult.Rejected(outcome.reason)
            is WeTrakrOutcome.Unauthorized -> TrackingDeliveryResult.Unauthorized(outcome.reason)
        }

    override suspend fun readActivityStamp(): TrackingInboundOutcome<TrackingActivityStamp> =
        when (val outcome = send(WeTrakrInboundMapping.activitiesRequest())) {
            is WeTrakrOutcome.Success -> {
                val stamp = WeTrakrInboundMapping.activityStamp(outcome.response.body)
                if (stamp == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(stamp)
                }
            }
            is WeTrakrOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is WeTrakrOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is WeTrakrOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }

    override suspend fun readSavedPlaybacks(
        request: TrackingPlaybackRequest,
    ): TrackingInboundOutcome<TrackingPlaybackPage> {
        val page = if (request.dateFrom == null) 1 else playbackPage + 1
        playbackPage = page
        val limit = request.limit.coerceIn(1, PLAYBACK_PAGE_LIMIT)
        val items = ArrayList<TrackingSavedPlayback>()
        var malformed = 0
        var truncated = false
        for (target in PLAYBACK_TARGETS) {
            val outcome = send(WeTrakrInboundMapping.playingRequest(target, page, limit))
            when (outcome) {
                is WeTrakrOutcome.Success -> {
                    val parsed = WeTrakrInboundMapping.playbacks(
                        body = outcome.response.body,
                        target = target,
                        pageCount = WeTrakrInboundMapping.pageCount(outcome.response.headers),
                        page = page,
                        limit = limit,
                    ) ?: return TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                    items += parsed.items
                    malformed += parsed.malformed
                    truncated = truncated || parsed.truncated
                }
                is WeTrakrOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
                is WeTrakrOutcome.Rejected -> return TrackingInboundOutcome.Rejected(outcome.reason)
                is WeTrakrOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            }
        }
        return TrackingInboundOutcome.Ok(
            TrackingPlaybackPage(items = items, malformed = malformed, truncated = truncated),
        )
    }

    override suspend fun readWatched(
        queries: List<TrackingWatchedQuery>,
    ): TrackingInboundOutcome<List<TrackingWatchedAnswer>> {
        require(queries.isNotEmpty()) { "a watched lookup is never sent empty" }
        require(queries.size <= TrackingWatchedLookupPort.MAX_BATCH) {
            "a watched lookup batch must not exceed ${TrackingWatchedLookupPort.MAX_BATCH} items"
        }
        val answers = arrayOfNulls<TrackingWatchedAnswer>(queries.size)
        val movies = LinkedHashMap<Long, MutableList<Int>>()
        val shows = LinkedHashMap<Long, MutableList<Int>>()
        val budget = LookupBudget(limit = MAX_LOOKUP_REQUESTS)
        for ((index, query) in queries.withIndex()) {
            when (val target = resolveWeTrakrId(query, budget)) {
                is Target.Failed -> return when (val outcome = target.outcome) {
                    is WeTrakrOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
                    is WeTrakrOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
                    is WeTrakrOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
                    is WeTrakrOutcome.Success -> TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                }

                Target.Unknown -> answers[index] = NOT_FOUND_ANSWER

                Target.Undecidable -> answers[index] = MALFORMED_ANSWER
                is Target.Value -> when (query.kind) {
                    TrackingMediaKind.MOVIE -> movies.getOrPut(target.id) { mutableListOf() } += index
                    TrackingMediaKind.SHOW -> shows.getOrPut(target.id) { mutableListOf() } += index
                }
            }
        }
        for ((movieId, indices) in movies) {
            if (!budget.charge()) {
                indices.forEach { answers[it] = MALFORMED_ANSWER }
                continue
            }
            when (val outcome = send(WeTrakrInboundMapping.movieHistoryRequest(movieId, page = 1, limit = 1))) {
                is WeTrakrOutcome.Success -> {
                    val answer = WeTrakrInboundMapping.movieWatchedAnswer(outcome.response.body)
                    indices.forEach { answers[it] = answer }
                }

                is WeTrakrOutcome.Rejected ->
                    if (isNotFoundReason(outcome.reason)) {
                        indices.forEach { answers[it] = NOT_FOUND_ANSWER }
                    } else {
                        return TrackingInboundOutcome.Rejected(outcome.reason)
                    }
                is WeTrakrOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
                is WeTrakrOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
            }
        }
        for ((showId, indices) in shows) {
            val pending = indices.toMutableSet()
            var page = 1
            var more = false
            while (pending.isNotEmpty() && page <= MAX_HISTORY_PAGES_PER_LOOKUP) {
                if (!budget.charge()) {
                    pending.forEach { answers[it] = MALFORMED_ANSWER }
                    pending.clear()
                    break
                }
                when (
                    val outcome = send(
                        WeTrakrInboundMapping.showEpisodeHistoryRequest(showId, page, HISTORY_PAGE_LIMIT),
                    )
                ) {
                    is WeTrakrOutcome.Success -> {
                        val body = outcome.response.body
                        more = WeTrakrInboundMapping.hasMorePages(body, page)
                        val atPageCap = page == MAX_HISTORY_PAGES_PER_LOOKUP
                        val answeredNow = mutableListOf<Int>()
                        for (index in pending) {
                            val query = queries[index]
                            val answer = WeTrakrInboundMapping.episodeWatchedAnswer(
                                body = body,
                                seasonNumber = query.seasonNumber!!,
                                episodeNumber = query.episodeNumber!!,

                                hasMorePages = atPageCap && more,
                            )
                            if (answer.verdict != TrackingWatchedVerdict.NOT_WATCHED) {
                                answers[index] = answer
                                answeredNow += index
                            }
                        }
                        pending.removeAll(answeredNow.toSet())
                        if (pending.isEmpty() || !more) break
                        page++
                    }
                    is WeTrakrOutcome.Rejected ->
                        if (isNotFoundReason(outcome.reason)) {
                            pending.forEach { answers[it] = NOT_FOUND_ANSWER }
                            pending.clear()
                        } else {
                            return TrackingInboundOutcome.Rejected(outcome.reason)
                        }
                    is WeTrakrOutcome.Retry -> return TrackingInboundOutcome.Unavailable(outcome.reason)
                    is WeTrakrOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)
                }
            }
            pending.forEach { answers[it] = NOT_WATCHED_ANSWER }
        }

        val resolved = answers.map { it ?: MALFORMED_ANSWER }
        return TrackingInboundOutcome.Ok(resolved)
    }

    override suspend fun readUpNext(
        request: UpNextReadRequest,
    ): TrackingInboundOutcome<UpNextTrackerPage> =
        when (
            val outcome = send(WeTrakrInboundMapping.upNextRequest(request.page, request.limit))
        ) {
            is WeTrakrOutcome.Success -> {
                val parsed = WeTrakrInboundMapping.upNextPage(
                    body = outcome.response.body,
                    pageCount = WeTrakrInboundMapping.pageCount(outcome.response.headers),
                    page = request.page,
                    limit = request.limit,
                )
                if (parsed == null) {
                    TrackingInboundOutcome.Rejected(MALFORMED_RESPONSE)
                } else {
                    TrackingInboundOutcome.Ok(parsed)
                }
            }
            is WeTrakrOutcome.Retry -> TrackingInboundOutcome.Unavailable(outcome.reason)
            is WeTrakrOutcome.Rejected -> TrackingInboundOutcome.Rejected(outcome.reason)
            is WeTrakrOutcome.Unauthorized -> TrackingInboundOutcome.Unauthorized(outcome.reason)
        }

    private suspend fun resolveWeTrakrId(query: TrackingWatchedQuery, budget: LookupBudget): Target {
        var sawNotFound = false
        for (authority in LOOKUP_LADDER) {
            val value = aliasFor(query, authority) ?: continue

            if (!PATH_SAFE.matches(value)) {
                return Target.Undecidable
            }
            val key = memoKey(query.kind, authority, value)
            memoGet(key)?.let { return Target.Value(it) }
            if (!budget.charge()) return Target.Undecidable
            when (val outcome = send(WeTrakrInboundMapping.externalIdRequest(authority, value, query.kind))) {
                is WeTrakrOutcome.Success -> {
                    val id = WeTrakrInboundMapping.externalIdOf(outcome.response.body)
                        ?: return Target.Undecidable
                    memoPut(key, id)
                    return Target.Value(id)
                }
                is WeTrakrOutcome.Rejected -> when {

                    isNotFoundReason(outcome.reason) -> {
                        sawNotFound = true
                    }

                    outcome.reason.contains(AMBIGUOUS_STATUS) -> return Target.Undecidable

                    outcome.reason.contains(BAD_REQUEST_STATUS) -> Unit
                    else -> return Target.Failed(outcome)
                }
                is WeTrakrOutcome.Retry -> return Target.Failed(outcome)
                is WeTrakrOutcome.Unauthorized -> return Target.Failed(outcome)
            }
        }
        return if (sawNotFound) Target.Unknown else Target.Undecidable
    }

    private fun aliasFor(query: TrackingWatchedQuery, authority: String): String? {
        val ids = query.externalIds
        return when (authority) {
            AUTHORITY_IMDB -> ids.imdbId
            AUTHORITY_TMDB -> ids.tmdbId?.toString()
            AUTHORITY_TVDB -> ids.tvdbId?.toString()
            else -> null
        }
    }

    private fun memoKey(kind: TrackingMediaKind, authority: String, value: String): String =
        "${kind.name}|$authority|$value"

    private fun memoGet(key: String): Long? = synchronized(resolutionMemo) { resolutionMemo[key] }

    private fun memoPut(key: String, id: Long) = synchronized(resolutionMemo) {
        if (resolutionMemo.size >= RESOLUTION_MEMO_MAX) {
            resolutionMemo.keys.firstOrNull()?.let { resolutionMemo.remove(it) }
        }
        resolutionMemo[key] = id
    }

    private suspend fun send(request: WeTrakrRequest): WeTrakrOutcome {
        if (request.authenticated) {
            val token = readToken { auth.accessToken() }
                ?: return WeTrakrOutcome.Unauthorized(NO_AUTHORIZATION)
            val outcome = client.execute(request, token)
            if (outcome !is WeTrakrOutcome.Unauthorized) return outcome

            val refreshed = readToken { auth.refresh() } ?: return outcome
            return client.execute(request, refreshed)
        }

        return client.execute(request, null)
    }

    private suspend fun readToken(read: suspend () -> String?): String? = try {
        read()?.takeIf { it.isNotBlank() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }

    private sealed interface Target {
        data class Value(val id: Long) : Target
        data object Unknown : Target
        data object Undecidable : Target
        data class Failed(val outcome: WeTrakrOutcome) : Target
    }

    private class LookupBudget(private val limit: Int) {
        private var issued: Int = 0

        fun charge(): Boolean {
            if (issued >= limit) return false
            issued++
            return true
        }
    }

    private companion object {
        const val NO_AUTHORIZATION = "no valid WeTrakr authorization for this profile"
        const val MALFORMED_RESPONSE = "wetrakr answered a payload this client cannot use"

        const val NOT_FOUND_REASON = "http_404"

        fun isNotFoundReason(reason: String): Boolean = reason.startsWith(NOT_FOUND_REASON)

        const val AMBIGUOUS_STATUS = "http_409"

        const val BAD_REQUEST_STATUS = "http_400"

        val LOOKUP_LADDER = listOf(AUTHORITY_IMDB, AUTHORITY_TMDB, AUTHORITY_TVDB)

        const val AUTHORITY_IMDB = "imdb"
        const val AUTHORITY_TMDB = "tmdb"
        const val AUTHORITY_TVDB = "tvdb"

        val PATH_SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

        val PLAYBACK_TARGETS = listOf(
            WeTrakrInboundMapping.TARGET_MOVIES,
            WeTrakrInboundMapping.TARGET_EPISODES,
        )

        const val PLAYBACK_PAGE_LIMIT = 100

        const val HISTORY_PAGE_LIMIT = 100

        const val MAX_HISTORY_PAGES_PER_LOOKUP = 3

        const val MAX_LOOKUP_REQUESTS = TrackingWatchedLookupPort.MAX_BATCH

        const val RESOLUTION_MEMO_MAX = 512

        val MALFORMED_ANSWER = TrackingWatchedAnswer(TrackingWatchedVerdict.MALFORMED)
        val NOT_FOUND_ANSWER = TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_FOUND)
        val NOT_WATCHED_ANSWER = TrackingWatchedAnswer(TrackingWatchedVerdict.NOT_WATCHED)
    }
}
